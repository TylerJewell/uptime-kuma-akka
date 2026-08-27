package io.akka.uptimekuma.checks;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLHandshakeException;

/**
 * A transport failure written the way the source writes it.
 *
 * <p>The source puts {@code error.message} straight into the heartbeat and the interface shows that
 * string in the events table, on the monitor's page and in every notification. Its client is Node's,
 * so its vocabulary is Node's — {@code connect ECONNREFUSED 127.0.0.1:1}, {@code getaddrinfo
 * ENOTFOUND host}, {@code self-signed certificate}. A Java client's own exceptions say the same
 * things in different words, and a message that says {@code ClosedChannelException} where the
 * source says {@code connect ECONNREFUSED} is a different answer to the same question.
 *
 * <p>Both halves of the table below were measured rather than remembered:
 * {@code probes/data/source-transport-errors.json} is the source's client driven against each
 * condition, and {@code probes/data/port-transport-errors.json} is this client driven against the
 * same ones.
 */
public final class TransportErrors {

  private TransportErrors() {}

  /**
   * Three certificate-path failures Java reports identically and the source distinguishes.
   *
   * <p>{@code SunCertPathBuilderException} says only that no path to a trusted root exists; whether
   * that is a self-signed leaf, a self-signed root the platform does not carry, or a chain missing
   * its intermediate is a fact about the certificates the server sent, not about the exception. So
   * the chain is recorded during the handshake and read back here.
   */
  public static String message(Throwable error, String url, HttpClient client) {
    for (Throwable current = error; current != null; current = next(current)) {
      String mapped = classify(current, url, client);
      if (mapped != null) {
        return mapped;
      }
    }
    return deepestMessage(error);
  }

  /** The same question where no client is at hand — a raw socket check, a driver, a resolver. */
  public static String message(Throwable error, String url) {
    return message(error, url, null);
  }

  private static Throwable next(Throwable current) {
    Throwable cause = current.getCause();
    return cause == current ? null : cause;
  }

  private static String classify(Throwable current, String url, HttpClient client) {
    if (current instanceof java.nio.channels.ClosedChannelException) {
      return "connect ECONNREFUSED " + authority(url);
    }
    if (current instanceof java.nio.channels.UnresolvedAddressException
        || current instanceof java.net.UnknownHostException) {
      return "getaddrinfo ENOTFOUND " + host(url);
    }
    if (current instanceof java.net.http.HttpConnectTimeoutException
        || current instanceof java.net.SocketTimeoutException) {
      return "connect ETIMEDOUT " + authority(url);
    }
    if (current instanceof java.net.NoRouteToHostException) {
      return "connect EHOSTUNREACH " + authority(url);
    }
    if (current instanceof CertificateExpiredException) {
      return "certificate has expired";
    }
    if (current instanceof CertificateNotYetValidException) {
      return "certificate is not yet valid";
    }
    if (current instanceof java.security.cert.CertificateException
        && current.getMessage() != null
        && current.getMessage().startsWith("No subject alternative")) {
      return altNameMessage(current.getMessage(), url, client);
    }
    if (current instanceof java.security.cert.CertPathBuilderException) {
      return untrustedChainMessage(client);
    }
    if (current instanceof java.net.ConnectException && current.getCause() == null) {
      // A ConnectException carrying no cause and no message is how a refusal reaches the caller
      // on some platforms; where the platform did give a reason, the message below reads it.
      String message = current.getMessage();
      if (message == null || message.isBlank()) {
        return "connect ECONNREFUSED " + authority(url);
      }
      if (message.contains("refused")) {
        return "connect ECONNREFUSED " + authority(url);
      }
      if (message.contains("timed out")) {
        return "connect ETIMEDOUT " + authority(url);
      }
      if (message.contains("unreachable")) {
        return "connect EHOSTUNREACH " + authority(url);
      }
    }
    if (current instanceof java.net.SocketException
        && current.getMessage() != null
        && current.getMessage().contains("reset")) {
      return "read ECONNRESET";
    }
    if (current instanceof java.io.EOFException || isHeaderParserEof(current)) {
      return "socket hang up";
    }
    if (current instanceof SSLHandshakeException && current.getCause() == null) {
      return "write EPROTO";
    }
    return null;
  }

  private static boolean isHeaderParserEof(Throwable current) {
    return current instanceof java.io.IOException
        && current.getMessage() != null
        && current.getMessage().contains("header parser received no bytes");
  }

  /**
   * The source names the host it asked for and lists what the certificate offered instead; the Java
   * exception names only the host. Where the chain was recorded the list is real, and where it was
   * not the clause is left off rather than invented.
   */
  private static String altNameMessage(String javaMessage, String url, HttpClient client) {
    String wanted = host(url);
    String offered = Http.altNames(client);
    String head = "Hostname/IP does not match certificate's altnames: Host: " + wanted + ". is not in the cert's altnames";
    return offered == null ? head : head + ": " + offered;
  }

  /**
   * Which of the source's three untrusted-chain messages a chain earns.
   *
   * <p>A single certificate that issued itself is a self-signed leaf. A chain whose topmost
   * certificate issued itself is a self-signed root the platform does not carry. A chain that ends
   * on something issued by somebody else is missing the rest of its path.
   */
  private static String untrustedChainMessage(HttpClient client) {
    X509Certificate[] chain = Http.chainOf(client);
    if (chain == null || chain.length == 0) {
      return "unable to verify the first certificate";
    }
    X509Certificate top = chain[chain.length - 1];
    boolean topIsSelfIssued = top.getSubjectX500Principal().equals(top.getIssuerX500Principal());
    if (!topIsSelfIssued) {
      return "unable to verify the first certificate";
    }
    return chain.length == 1
        ? "self-signed certificate"
        : "self-signed certificate in certificate chain";
  }

  /** {@code host:port}, with the scheme's own port supplied where the URL left it out. */
  static String authority(String url) {
    if (url == null) {
      return "";
    }
    try {
      URI uri = URI.create(url);
      String host = uri.getHost() == null ? url : uri.getHost();
      int port = uri.getPort();
      if (port < 0) {
        port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
      }
      return host + ":" + port;
    } catch (Exception e) {
      return url;
    }
  }

  static String host(String url) {
    if (url == null) {
      return "";
    }
    try {
      URI uri = URI.create(url);
      return uri.getHost() == null ? url : uri.getHost();
    } catch (Exception e) {
      return url;
    }
  }

  /**
   * The deepest cause's message, which is what a failure with no entry in the table above reports.
   *
   * <p>The outer exception says only that a request failed; the cause names the host, the refusal or
   * the certificate problem, and that is what a person reading the events table needs.
   */
  public static String deepestMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }
}
