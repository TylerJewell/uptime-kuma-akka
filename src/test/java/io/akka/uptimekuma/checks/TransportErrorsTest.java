package io.akka.uptimekuma.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * A transport failure is written the way the source writes it.
 *
 * <p>The right-hand side of every case here is a string that was **read off the original**, not
 * invented: `probes/data/source-transport-errors.json` and `-2.json` record what the source's own
 * client produced for the same seven conditions, and the left-hand side is the exception this
 * client produced for each of them, recorded in the same run. R97.
 */
class TransportErrorsTest {

  private static final String URL = "http://127.0.0.1:1/";

  /** How the platform delivers a refusal: a ConnectException with a closed channel underneath. */
  @Test
  void aRefusalReadsAsEconnrefusedNamingHostAndPort() {
    Throwable refused = new ConnectException();
    refused.initCause(new ClosedChannelException());
    assertEquals("connect ECONNREFUSED 127.0.0.1:1", TransportErrors.message(refused, URL));
  }

  @Test
  void aRefusalOnAUrlWithNoPortNamesTheSchemeDefault() {
    Throwable refused = new ConnectException();
    refused.initCause(new ClosedChannelException());
    assertEquals(
        "connect ECONNREFUSED example.test:80", TransportErrors.message(refused, "http://example.test/"));
    assertEquals(
        "connect ECONNREFUSED example.test:443",
        TransportErrors.message(refused, "https://example.test/"));
  }

  @Test
  void anUnresolvedNameReadsAsEnotfoundNamingOnlyTheHost() {
    Throwable unresolved = new ConnectException();
    unresolved.initCause(new UnresolvedAddressException());
    assertEquals(
        "getaddrinfo ENOTFOUND no-such-host.invalid",
        TransportErrors.message(unresolved, "http://no-such-host.invalid/"));
    assertEquals(
        "getaddrinfo ENOTFOUND no-such-host.invalid",
        TransportErrors.message(
            new UnknownHostException("no-such-host.invalid"), "http://no-such-host.invalid/"));
  }

  @Test
  void aConnectTimeoutReadsAsEtimedout() {
    Throwable timedOut =
        new java.net.http.HttpConnectTimeoutException("HTTP connect timed out");
    assertEquals(
        "connect ETIMEDOUT 10.255.255.1:80", TransportErrors.message(timedOut, "http://10.255.255.1/"));
  }

  @Test
  void anUnreachableHostReadsAsEhostunreach() {
    assertEquals(
        "connect EHOSTUNREACH 10.0.0.9:80",
        TransportErrors.message(new NoRouteToHostException("No route to host"), "http://10.0.0.9/"));
  }

  @Test
  void anExpiredCertificateReadsAsTheSourceWritesIt() {
    Throwable handshake = new SSLHandshakeException("(certificate_expired) PKIX path validation failed");
    handshake.initCause(new CertificateExpiredException("NotAfter: Sun Apr 12 16:59:59 PDT 2015"));
    assertEquals("certificate has expired", TransportErrors.message(handshake, "https://expired.test/"));
  }

  @Test
  void aCertificateNotYetValidReadsAsItsOwnCondition() {
    Throwable handshake = new SSLHandshakeException("(certificate_expired)");
    handshake.initCause(new CertificateNotYetValidException("NotBefore: 2099"));
    assertEquals(
        "certificate is not yet valid", TransportErrors.message(handshake, "https://early.test/"));
  }

  /**
   * The source names the host it asked for; the clause listing what the certificate offered instead
   * is added only where the chain was recorded, and no client means no chain.
   */
  @Test
  void aHostnameMismatchNamesTheHostThatWasAskedFor() {
    Throwable handshake =
        new SSLHandshakeException(
            "(certificate_unknown) No subject alternative DNS name matching wrong.host.badssl.com found.");
    handshake.initCause(
        new CertificateException(
            "No subject alternative DNS name matching wrong.host.badssl.com found."));
    assertEquals(
        "Hostname/IP does not match certificate's altnames: Host: wrong.host.badssl.com."
            + " is not in the cert's altnames",
        TransportErrors.message(handshake, "https://wrong.host.badssl.com/"));
  }

  /**
   * PKIX reports the same exception for three conditions the source distinguishes, so with no chain
   * recorded the message is the one that assumes least: the path could not be completed.
   */
  @Test
  void anUncompletableCertificatePathWithNoChainRecordedReadsAsAnUnverifiableLeaf() {
    Throwable handshake = new SSLHandshakeException("(certificate_unknown) PKIX path building failed");
    handshake.initCause(
        new java.security.cert.CertPathBuilderException(
            "unable to find valid certification path to requested target"));
    assertEquals(
        "unable to verify the first certificate",
        TransportErrors.message(handshake, "https://self-signed.test/"));
  }

  @Test
  void aResetReadsAsEconnreset() {
    assertEquals(
        "read ECONNRESET",
        TransportErrors.message(new SocketException("Connection reset"), "http://a.test/"));
  }

  @Test
  void aServerClosingWithoutAnsweringReadsAsAHangUp() {
    Throwable eof = new IOException("HTTP/1.1 header parser received no bytes");
    eof.initCause(new EOFException("EOF reached while reading"));
    assertEquals("socket hang up", TransportErrors.message(eof, "http://a.test/"));
  }

  /** Anything with no entry in the table keeps the deepest cause's own words. */
  @Test
  void anUnmappedFailureKeepsTheDeepestCausesMessage() {
    Throwable outer = new IOException("outer");
    outer.initCause(new IllegalStateException("the thing that actually went wrong"));
    assertEquals(
        "the thing that actually went wrong", TransportErrors.message(outer, "http://a.test/"));
  }

  /** A cause with no message at all names its class, which is better than an empty events row. */
  @Test
  void anUnmappedFailureWithNoMessageNamesItsClass() {
    assertEquals(
        "IllegalStateException",
        TransportErrors.message(new IllegalStateException(), "http://a.test/"));
  }

  @Test
  void aFailureWithNoUrlStillReadsTheTableAndLeavesTheAuthorityEmpty() {
    Throwable refused = new ConnectException();
    refused.initCause(new ClosedChannelException());
    assertEquals("connect ECONNREFUSED ", TransportErrors.message(refused, null));
  }

  /**
   * Driven rather than constructed: a real request to a port nothing is listening on, through the
   * same client every http-family check uses.
   */
  @Test
  void aRealRefusalThroughTheCheckClientReadsAsEconnrefused() throws Exception {
    String url = "http://127.0.0.1:1/";
    var client =
        Http.client(true, null, null, 10, java.time.Duration.ofSeconds(5), null, null, null);
    try {
      client.send(
          java.net.http.HttpRequest.newBuilder(URI.create(url)).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      throw new AssertionError("nothing should be listening on port 1");
    } catch (IOException e) {
      assertEquals("connect ECONNREFUSED 127.0.0.1:1", TransportErrors.message(e, url, client));
    }
  }

  /** The authority helper is what puts the source's `host:port` on the front of three messages. */
  @Test
  void theAuthorityIsHostAndPortAndTheHostIsTheHostAlone() {
    assertEquals("a.test:8080", TransportErrors.authority("http://a.test:8080/x?y=1"));
    assertEquals("a.test", TransportErrors.host("http://a.test:8080/x?y=1"));
    assertTrue(TransportErrors.authority(null).isEmpty());
  }
}
