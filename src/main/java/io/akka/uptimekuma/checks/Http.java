package io.akka.uptimekuma.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.WeakHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The one outbound HTTP client every check that speaks HTTP goes through.
 *
 * <p>Built per call rather than shared, because four of the things a monitor configures — whether
 * to verify certificates, which address family to use, which proxy to go through, how long to wait
 * — are properties of the client rather than of the request, and a shared client would silently
 * apply one monitor's settings to another's check.
 */
public final class Http {

  private Http() {}

  public static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The Accept header the source sends on every http-family check.
   *
   * <p>A browser's header rather than a monitoring tool's, which is deliberate: a server that
   * content-negotiates gives the check the same body it would give a visitor.
   */
  public static final String ACCEPT =
      "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

  /**
   * @param followRedirects zero means a redirect is judged against the accepted status codes
   *     instead of being followed
   */
  public static HttpClient client(
      boolean verifyTls,
      String ipFamily,
      CheckContext.ProxyConfig proxy,
      int followRedirects,
      Duration connectTimeout,
      String clientCertPem,
      String clientKeyPem,
      String caPem) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            // HTTP/1.1, because that is the only version the original ever speaks. Left at the
            // platform default the client offers an upgrade to HTTP/2 on every plaintext request,
            // and a server that does not expect the offer closes the connection instead of
            // answering — which reads as an outage on a service that is up.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .followRedirects(
                followRedirects > 0 ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);

    // A context is installed on every client, not only on the ones that configure certificates,
    // because the recording trust manager inside it is what lets a failed handshake be described
    // in the source's words rather than in PKIX's -- see TransportErrors.
    ChainRecorder recorder = new ChainRecorder();
    builder.sslContext(sslContext(verifyTls, clientCertPem, clientKeyPem, caPem, recorder));
    if ("ipv4".equals(ipFamily)) {
      builder.localAddress(new InetSocketAddress(0).getAddress());
    }
    if (proxy != null && proxy.host() != null && !proxy.host().isBlank()) {
      builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
      if (proxy.auth()) {
        builder.authenticator(
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                    proxy.username(),
                    proxy.password() == null ? new char[0] : proxy.password().toCharArray());
              }
            });
      }
    }
    HttpClient client = builder.build();
    RECORDERS.put(client, recorder);
    return client;
  }

  /**
   * The certificate chain the server last presented to this client, or null if none was seen.
   *
   * <p>Weakly keyed: a client is built per check and the chain it saw dies with it.
   */
  static X509Certificate[] chainOf(HttpClient client) {
    ChainRecorder recorder = client == null ? null : RECORDERS.get(client);
    return recorder == null ? null : recorder.chain;
  }

  /** The names the server's own certificate offered, comma-separated the way the source lists them. */
  static String altNames(HttpClient client) {
    X509Certificate[] chain = chainOf(client);
    if (chain == null || chain.length == 0) {
      return null;
    }
    try {
      Collection<List<?>> names = chain[0].getSubjectAlternativeNames();
      if (names == null || names.isEmpty()) {
        return null;
      }
      StringBuilder out = new StringBuilder();
      for (List<?> entry : names) {
        if (out.length() > 0) {
          out.append(", ");
        }
        // The general-name tag: 2 is a DNS name and 7 an IP address, which are the two the
        // source's own client prints.
        int tag = ((Number) entry.get(0)).intValue();
        out.append(tag == 7 ? "IP Address:" : "DNS:").append(entry.get(1));
      }
      return out.toString();
    } catch (Exception e) {
      return null;
    }
  }

  private static final Map<HttpClient, ChainRecorder> RECORDERS =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * A trust manager that keeps what it was shown before deciding about it.
   *
   * <p>It delegates every verdict; the only thing it adds is the record, and the record is read
   * only after the delegate has refused.
   */
  static final class ChainRecorder implements X509TrustManager {

    private volatile X509Certificate[] chain;
    private X509TrustManager delegate;

    void delegateTo(X509TrustManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      this.chain = chain;
      delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }

  /**
   * A context that trusts everything.
   *
   * <p>Reached only when a monitor is configured to ignore certificate problems, which is what
   * "Ignore TLS/SSL error" means in the interface and is the point of the option — a check against
   * a device with a self-signed certificate is still a useful check. It also disables certificate
   * expiry notifications, which is the source's rule and is enforced where those are sent.
   */
  static SSLContext sslContext(
      boolean verifyTls, String clientCertPem, String clientKeyPem, String caPem) {
    return sslContext(verifyTls, clientCertPem, clientKeyPem, caPem, new ChainRecorder());
  }

  static SSLContext sslContext(
      boolean verifyTls,
      String clientCertPem,
      String clientKeyPem,
      String caPem,
      ChainRecorder recorder) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      recorder.delegateTo(
          verifyTls
              ? platformTrustManager(Pem.trustManagers(caPem))
              : new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                  return new X509Certificate[0];
                }
              });
      context.init(
          Pem.keyManagers(clientCertPem, clientKeyPem),
          new TrustManager[] {recorder},
          new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("cannot build TLS context: " + e.getMessage(), e);
    }
  }

  /**
   * The authorities a check verifies against: the ones the monitor supplied, or the platform's own
   * where it supplied none.
   *
   * <p>{@code Pem.trustManagers} answers null for the second case, which is what an SSLContext
   * initialised with a null array already means — but the recorder has to wrap a real delegate, so
   * the platform set is built here rather than left implicit.
   */
  private static X509TrustManager platformTrustManager(TrustManager[] configured) throws Exception {
    TrustManager[] managers = configured;
    if (managers == null) {
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init((java.security.KeyStore) null);
      managers = factory.getTrustManagers();
    }
    for (TrustManager manager : managers) {
      if (manager instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new IllegalStateException("no X509 trust manager available");
  }

  public static String basicAuth(String user, String pass) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                ((user == null ? "" : user) + ":" + (pass == null ? "" : pass))
                    .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Fetch a client-credentials access token.
   *
   * @param authMethod {@code client_secret_basic} puts the credentials in an Authorization header;
   *     anything else puts them in the form body, which is what the source's {@code
   *     client_secret_post} does
   */
  public static JsonNode oauthClientCredentials(
      HttpClient client,
      String tokenUrl,
      String clientId,
      String clientSecret,
      String scopes,
      String audience,
      String authMethod)
      throws IOException, InterruptedException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "client_credentials");
    if (scopes != null && !scopes.isBlank()) {
      form.put("scope", scopes);
    }
    if (audience != null && !audience.isBlank()) {
      form.put("audience", audience);
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if ("client_secret_basic".equals(authMethod)) {
      request.header("Authorization", basicAuth(clientId, clientSecret));
    } else {
      form.put("client_id", clientId);
      form.put("client_secret", clientSecret);
    }
    request.POST(HttpRequest.BodyPublishers.ofString(formEncode(form)));
    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new IOException("token endpoint answered " + response.statusCode());
    }
    return MAPPER.readTree(response.body());
  }

  public static String formEncode(Map<String, String> form) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> entry : form.entrySet()) {
      if (out.length() > 0) {
        out.append('&');
      }
      out.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
    }
    return out.toString();
  }

  public static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  /** Append a query parameter to a URL that may or may not already carry one. */
  public static String withParam(String url, String key, String value) {
    return url + (url.contains("?") ? "&" : "?") + urlEncode(key) + "=" + urlEncode(value);
  }
}
