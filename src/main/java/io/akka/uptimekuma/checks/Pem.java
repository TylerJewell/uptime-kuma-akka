package io.akka.uptimekuma.checks;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Reading the certificate, key and authority a monitor was pasted.
 *
 * <p>Three monitor fields hold PEM text — {@code tlsCert}, {@code tlsKey} and {@code tlsCa} — and
 * mutual-TLS checks are configured entirely through them. They arrive as text rather than as files,
 * so they are turned into key stores in memory here.
 */
final class Pem {

  private Pem() {}

  private static final Pattern BLOCK =
      Pattern.compile("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

  static KeyManager[] keyManagers(String certPem, String keyPem) throws Exception {
    if (certPem == null || certPem.isBlank() || keyPem == null || keyPem.isBlank()) {
      return null;
    }
    List<X509Certificate> chain = certificates(certPem);
    PrivateKey key = privateKey(keyPem);
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry("client", key, new char[0], chain.toArray(new X509Certificate[0]));
    KeyManagerFactory factory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store, new char[0]);
    return factory.getKeyManagers();
  }

  /** Null means the platform's own authorities, which is what a monitor with no CA field gets. */
  static TrustManager[] trustManagers(String caPem) throws Exception {
    if (caPem == null || caPem.isBlank()) {
      return null;
    }
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    int index = 0;
    for (X509Certificate certificate : certificates(caPem)) {
      store.setCertificateEntry("ca" + index++, certificate);
    }
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    return factory.getTrustManagers();
  }

  static List<X509Certificate> certificates(String pem) throws Exception {
    List<X509Certificate> out = new ArrayList<>();
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Matcher matcher = BLOCK.matcher(pem);
    while (matcher.find()) {
      if (!matcher.group(1).contains("CERTIFICATE")) {
        continue;
      }
      byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
      out.add(
          (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
    }
    return out;
  }

  static PrivateKey privateKey(String pem) throws Exception {
    Matcher matcher = BLOCK.matcher(pem);
    while (matcher.find()) {
      if (!matcher.group(1).endsWith("PRIVATE KEY")) {
        continue;
      }
      byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
      for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
        try {
          return KeyFactory.getInstance(algorithm).generatePrivate(spec);
        } catch (Exception ignored) {
          // Try the next algorithm: a PKCS#8 block does not say which one it holds without
          // parsing its DER, and there are only three worth trying.
        }
      }
    }
    throw new IllegalArgumentException("no readable private key in the supplied PEM");
  }

  static String toPem(X509Certificate certificate) throws Exception {
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(certificate.getEncoded())
        + "\n-----END CERTIFICATE-----\n";
  }
}
