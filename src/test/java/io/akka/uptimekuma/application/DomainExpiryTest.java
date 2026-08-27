package io.akka.uptimekuma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When a registration is read, and when a warning about one is armed again.
 *
 * <p>The registry directory and the registry itself are both HTTP, so both are stood up here: the
 * directory is seeded straight into the field that caches it, and the registry is a server this
 * test answers on. What that buys is the one rule that cannot be seen any other way — a renewal
 * clearing the warnings that were sent about the registration it replaced.
 */
class DomainExpiryTest {

  private static HttpServer registry;
  private static final AtomicReference<String> EXPIRY = new AtomicReference<>();

  @BeforeAll
  static void startRegistry() throws IOException {
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registry.createContext(
        "/domain/",
        exchange -> {
          String body =
              "{\"events\":[{\"eventAction\":\"expiration\",\"eventDate\":\""
                  + EXPIRY.get()
                  + "\"}]}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    registry.start();
  }

  @AfterAll
  static void stopRegistry() {
    registry.stop(0);
  }

  @BeforeEach
  void seedDirectory() throws Exception {
    String base = "http://127.0.0.1:" + registry.getAddress().getPort() + "/";
    set("bootstrapCache", Map.of("services", List.of(List.of(List.of("test"), List.of(base)))));
    set("bootstrapFetchedAt", Instant.now());
    cache().clear();
  }

  @Test
  void aNameIsReducedToWhatARegistryWouldKnow() {
    assertEquals("example.com", DomainExpiry.registrableDomain("https://www.example.com/status"));
    assertEquals("example.com", DomainExpiry.registrableDomain("example.com:8443"));
    // A suffix with a country under it needs three labels to name something registrable.
    assertEquals("example.com.br", DomainExpiry.registrableDomain("shop.example.com.br"));
    // One label names no registration at all.
    assertNull(DomainExpiry.registrableDomain("localhost"));
  }

  @Test
  void aMonitorWithNothingToLookUpSaysWhichKindOfNothing() {
    MonitorConfig noType =
        MonitorConfig.blank("m").toBuilder().type("push").build();
    assertEquals("domain_expiry_unsupported_monitor_type", DomainExpiry.unsupportedReason(noType));

    MonitorConfig noTarget = MonitorConfig.blank("m").toBuilder().type("http").url("").build();
    assertEquals(
        "domain_expiry_unsupported_missing_target", DomainExpiry.unsupportedReason(noTarget));

    MonitorConfig notRegistrable =
        MonitorConfig.blank("m").toBuilder().type("http").url("http://localhost:3000").build();
    assertEquals("domain_expiry_unsupported_is_icann", DomainExpiry.unsupportedReason(notRegistrable));

    MonitorConfig fine =
        MonitorConfig.blank("m").toBuilder().type("http").url("https://example.com").build();
    assertNull(DomainExpiry.unsupportedReason(fine));
  }

  @Test
  void aRegistrationIsReadOnceAndThenTakenFromTheCache() {
    EXPIRY.set("2027-01-01T00:00:00Z");
    assertEquals(Instant.parse("2027-01-01T00:00:00Z"), DomainExpiry.expiryOf("example.test"));

    // The registry now says something else, but the cached answer is a day old at most, so it is
    // the cached one that comes back.
    EXPIRY.set("2029-01-01T00:00:00Z");
    assertEquals(Instant.parse("2027-01-01T00:00:00Z"), DomainExpiry.expiryOf("example.test"));
  }

  @Test
  void aRegistrationMovingLaterReArmsTheWarnings() throws Exception {
    EXPIRY.set("2027-01-01T00:00:00Z");
    assertNotNull(DomainExpiry.expiryOf("example.test"));

    // Stand where the code would be after a warning had gone out, with the cached answer old
    // enough to be read again.
    reseat("example.test", Instant.parse("2027-01-01T00:00:00Z"), 7);
    assertEquals(7, notifiedDays("example.test"));

    // The same date read again leaves the warning where it is: nothing has changed.
    assertEquals(Instant.parse("2027-01-01T00:00:00Z"), DomainExpiry.expiryOf("example.test"));
    assertEquals(7, notifiedDays("example.test"));

    // A renewal moves the date out, and the thresholds already passed were about a registration
    // that no longer exists — so they are armed again.
    reseat("example.test", Instant.parse("2027-01-01T00:00:00Z"), 7);
    EXPIRY.set("2029-01-01T00:00:00Z");
    assertEquals(Instant.parse("2029-01-01T00:00:00Z"), DomainExpiry.expiryOf("example.test"));
    assertNull(notifiedDays("example.test"));
  }

  // ---- reaching into the cache -------------------------------------------------------------
  //
  // The cache is the whole of what these two rules are about and it is deliberately not part of
  // any interface, so the test reads and reseats it directly rather than the class growing a way
  // to be told about it that nothing else would use.

  private static void set(String field, Object value) throws Exception {
    Field f = DomainExpiry.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(null, value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cache() throws Exception {
    Field f = DomainExpiry.class.getDeclaredField("DOMAIN_CACHE");
    f.setAccessible(true);
    return (Map<String, Object>) f.get(null);
  }

  /** Put an entry back holding the given expiry, a warning already sent, and an aged read. */
  private static void reseat(String domain, Instant expiry, Integer notifiedDays) throws Exception {
    Class<?> entry = Class.forName("io.akka.uptimekuma.application.DomainExpiry$CachedExpiry");
    var constructor = entry.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    cache()
        .put(
            domain,
            constructor.newInstance(
                expiry, Instant.now().minusSeconds(3 * 24 * 3600), notifiedDays));
  }

  private static Integer notifiedDays(String domain) throws Exception {
    Object entry = cache().get(domain);
    if (entry == null) {
      return null;
    }
    var method = entry.getClass().getDeclaredMethod("lastNotifiedDays");
    method.setAccessible(true);
    return (Integer) method.invoke(entry);
  }
}
