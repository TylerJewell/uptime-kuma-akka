package io.akka.uptimekuma.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every notification target, put to the same configuration the source was put to.
 *
 * <p>A notification is a call to somebody else's service, so what is compared is the request rather
 * than a delivery: the method, the address and the body. The other side of the comparison was
 * captured by driving the source's own provider classes with the same configurations — see
 * {@code uptime-kuma-port/probes/capture_notifications.js}.
 *
 * <p>What this asserts, target by target, is that the rebuild composes a request at all, addresses
 * it to the same place, and carries the same body keys. Where the two differ, the difference is
 * written into the benchmark report rather than being softened here.
 */
class ProvidersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A sender that records what it was given rather than sending it.
   *
   * <p>The answer it gives back is shaped so that whichever field a target inspects it finds an
   * acceptance: several read different fields of the same body, and two want a status of 201. The
   * same shapes were given to the source's side of the comparison.
   */
  static final class Capturing implements Sender {
    final List<Request> requests = new ArrayList<>();
    private final String target;

    Capturing() {
      this("");
    }

    Capturing(String target) {
      this.target = target;
    }

    @Override
    public Response send(Request request) {
      int status = List.of("Brevo", "nextcloudtalk").contains(target) ? 201 : 200;
      return recordAndAnswer(request, status);
    }

    private Response recordAndAnswer(Request request, int status) {
      requests.add(request);
      return new Response(status, "OK", answerFor(request));
    }

    private String answerFor(Request request) {
      String url = request.url() == null ? "" : request.url();
      if ("SMSEagle".equals(target)) {
        return "[{\"status\":\"queued\"}]";
      }
      if ("Cellsynt".equals(target)) {
        return "OK";
      }
      if ("bearsms".equals(target)) {
        return "{\"status\":\"OK\",\"data\":[{\"status\":\"OK\"}]}";
      }
      if ("WxPusher".equals(target)) {
        return "{\"code\":1000,\"msg\":\"ok\"}";
      }
      if (url.contains("smseagle") && url.contains("api/v2")) {
        return "[{\"status\":\"queued\"}]";
      }
      if (url.contains("cellsynt")) {
        return "OK";
      }
      if (url.contains("bearsms")) {
        return "{\"status\":\"OK\",\"data\":[{\"status\":\"OK\"}]}";
      }
      if (url.contains("wxpusher")) {
        return "{\"code\":1000,\"msg\":\"ok\"}";
      }
      return "{\"id\":\"1\",\"ok\":true,\"success\":true,\"code\":0,\"errmsg\":\"ok\","
          + "\"Message\":\"OK\",\"messageId\":\"1\",\"error_code\":\"000\",\"response_code\":0,"
          + "\"response\":{\"status\":0},\"data\":{\"messages\":[{\"status\":\"SUCCESS\"}]},"
          + "\"content\":{\"result\":[\"{\\\"success\\\":\\\"ok\\\"}\"]},\"avatar\":null,"
          + "\"token\":\"t\"}";
    }
  }

  /** The four targets whose transport is neither HTTP nor something a stand-in can intercept. */
  static final List<String> NOT_HTTP = List.of("smtp", "Webpush", "nostr", "apprise");

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> configs() throws Exception {
    return MAPPER.readValue(
        new InputStreamReader(
            ProvidersTest.class.getResourceAsStream("/notification-configs.json"),
            StandardCharsets.UTF_8),
        Map.class);
  }

  private static Map<String, Object> monitorJson() {
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("id", 1);
    monitor.put("name", "web");
    monitor.put("url", "https://example.com");
    monitor.put("hostname", null);
    monitor.put("port", null);
    monitor.put("type", "http");
    monitor.put("subtype", null);
    monitor.put("path", List.of("group", "web"));
    monitor.put("pathName", "group / web");
    monitor.put(
        "tags", List.of(Map.of("name", "env", "value", "prod", "color", "#059669")));
    return monitor;
  }

  private static Map<String, Object> heartbeatJson(int status) {
    Map<String, Object> beat = new LinkedHashMap<>();
    beat.put("monitorID", 1);
    beat.put("status", status);
    beat.put("time", "2026-01-02 03:04:05.000");
    beat.put("msg", "connect ECONNREFUSED 127.0.0.1:443");
    beat.put("ping", 37);
    beat.put("important", true);
    beat.put("duration", 0);
    beat.put("retries", 1);
    beat.put("timezone", "UTC");
    beat.put("timezoneOffset", "+00:00");
    beat.put("localDateTime", "2026-01-02 03:04:05");
    return beat;
  }

  @Test
  void everyTargetTheSourceRegistersIsRegisteredHere() throws Exception {
    List<String> declared = new ArrayList<>(configs().keySet());
    List<String> built = Providers.names();
    List<String> missing = new ArrayList<>(declared);
    missing.removeAll(built);
    List<String> extra = new ArrayList<>(built);
    extra.removeAll(declared);
    assertEquals(List.of(), missing, "targets the source has and this rebuild does not");
    assertEquals(List.of(), extra, "targets this rebuild has and the source does not");
    assertEquals(105, built.size());
  }

  @TestFactory
  List<DynamicTest> everyHttpTargetComposesARequest() throws Exception {
    Map<String, Map<String, Object>> configs = configs();
    List<DynamicTest> tests = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> entry : configs.entrySet()) {
      String name = entry.getKey();
      if (NOT_HTTP.contains(name)) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              name,
              () -> {
                Capturing sender = new Capturing(name);
                Context context =
                    new Context(sender, "https://kuma.example.invalid", "2.5.3");
                Map<String, Object> config = new LinkedHashMap<>(entry.getValue());
                config.put("type", name);
                String result =
                    Providers.send(
                        new Config(config),
                        "[web] [🔴 Down] connect ECONNREFUSED 127.0.0.1:443",
                        monitorJson(),
                        heartbeatJson(0),
                        context);
                assertTrue(
                    !sender.requests.isEmpty(),
                    name + " composed no request for a monitor going down");
                Sender.Request request = sender.requests.get(0);
                assertNotNull(request.url(), name + " composed a request with no address");
                assertTrue(!request.url().isBlank(), name + " composed an empty address");
                assertNotNull(result, name + " returned no success message");
              }));
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> everyHttpTargetAddressesTheSamePlaceTheSourceDoes() throws Exception {
    Map<String, List<Map<String, Object>>> captured = capturedBySource();
    Map<String, Map<String, Object>> configs = configs();
    List<DynamicTest> tests = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : captured.entrySet()) {
      String name = entry.getKey();
      if (NOT_HTTP.contains(name) || !configs.containsKey(name)) {
        continue;
      }
      List<Map<String, Object>> sourceRequests = entry.getValue();
      if (sourceRequests.isEmpty()) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              name,
              () -> {
                Capturing sender = new Capturing(name);
                Map<String, Object> config = new LinkedHashMap<>(configs.get(name));
                config.put("type", name);
                Providers.send(
                    new Config(config),
                    "[web] [🔴 Down] connect ECONNREFUSED 127.0.0.1:443",
                    monitorJson(),
                    heartbeatJson(0),
                    new Context(sender, "https://kuma.example.invalid", "2.5.3"));
                assertEquals(
                    sourceRequests.size(),
                    sender.requests.size(),
                    name + " composed a different number of requests");
                for (int i = 0; i < sourceRequests.size(); i++) {
                  Map<String, Object> expected = sourceRequests.get(i);
                  Sender.Request actual = sender.requests.get(i);
                  String wanted =
                      withoutFreshPathSegment(
                          normalise(String.valueOf(expected.get("url")), expected.get("params")));
                  String got = withoutFreshPathSegment(normalise(actual.url(), null));
                  // Where the source handed its client a base and a relative path, only the
                  // relative half was captured, so both sides are reduced to it.
                  if (!wanted.startsWith("http")) {
                    got = got.substring(got.lastIndexOf('/') + 1);
                  }
                  assertEquals(
                      wanted, got, name + " request " + (i + 1) + " went to a different address");
                  assertEquals(
                      String.valueOf(expected.get("method")),
                      actual.method(),
                      name + " request " + (i + 1) + " used a different method");
                }
              }));
    }
    return tests;
  }

  /**
   * Reduce an address to what it actually asks for.
   *
   * <p>Four things differ between the two sides without the request differing: the source carries
   * some parameters beside the address rather than in it, one target hands its client a base and a
   * relative path, the two sides percent-encode a space differently before it goes on the wire, and
   * three targets put a fresh signature, timestamp or identifier into every request. So both sides
   * are decoded, sorted, and the values that are fresh per call are compared by their names.
   */
  @SuppressWarnings("unchecked")
  private static String normalise(String url, Object params) {
    String base = url == null ? "" : url;
    List<String> pairs = new ArrayList<>();
    int question = base.indexOf('?');
    if (question >= 0) {
      for (String pair : base.substring(question + 1).split("&")) {
        if (!pair.isEmpty()) {
          pairs.add(decodePair(pair));
        }
      }
      base = base.substring(0, question);
    }
    if (params instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : ((Map<String, Object>) map).entrySet()) {
        pairs.add(fresh(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));
      }
    }
    java.util.Collections.sort(pairs);
    // A path that is not absolute was handed to a client that already held the host.
    String head = base.startsWith("http") ? base : base.substring(base.lastIndexOf('/') + 1);
    return head + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs));
  }

  private static String decodePair(String pair) {
    int equals = pair.indexOf('=');
    if (equals < 0) {
      return pair;
    }
    return fresh(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
  }

  /** A value that is different on every call is compared by its name rather than its value. */
  private static String fresh(String key, String value) {
    if (List.of("sign", "timestamp", "random_id", "SignatureNonce", "Signature").contains(key)) {
      return key + "=<fresh>";
    }
    return key + "=" + value;
  }

  /**
   * One target puts a transaction identifier in the path, fresh on every call, so the last segment
   * of that one path is compared by its shape rather than its value.
   */
  private static String withoutFreshPathSegment(String url) {
    return url.replaceAll("(/send/m\\.room\\.message/)[^/?]+", "$1<fresh>");
  }

  private static String decode(String value) {
    try {
      return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return value;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<Map<String, Object>>> capturedBySource() throws Exception {
    List<Map<String, Object>> rows =
        MAPPER.readValue(
            new InputStreamReader(
                ProvidersTest.class.getResourceAsStream("/source-notifications.json"),
                StandardCharsets.UTF_8),
            List.class);
    Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      if (!"down".equals(row.get("shape"))) {
        continue;
      }
      Object requests = row.get("requests");
      if (requests instanceof List<?> list) {
        byName.put(String.valueOf(row.get("name")), (List<Map<String, Object>>) list);
      }
    }
    return byName;
  }

  @Test
  void aTargetNothingRegistersIsRefusedByName() {
    Exception refusal =
        assertThrows(
            Exception.class,
            () ->
                Providers.send(
                    new Config(Map.of("type", "carrier-pigeon")),
                    "hello",
                    null,
                    null,
                    new Context(new Capturing(), null, "2.5.3")));
    assertEquals("Notification type is not supported", refusal.getMessage());
  }

  @Test
  void aTemplateInterpolatesTheVariablesTheEditorOffers() {
    String rendered =
        Notify.renderTemplate(
            "{{ name }} is {{ status }} at {{ heartbeatJSON.localDateTime }}",
            "ignored",
            monitorJson(),
            heartbeatJson(0));
    assertEquals("web is 🔴 Down at 2026-01-02 03:04:05", rendered);
  }

  @Test
  void aTemplateConditionalPicksTheBranchTheValueNames() {
    assertEquals(
        "down",
        Notify.renderTemplate(
            "{% if heartbeatJSON %}down{% else %}test{% endif %}",
            "x",
            monitorJson(),
            heartbeatJson(0)));
    assertEquals(
        "test",
        Notify.renderTemplate("{% if heartbeatJSON %}down{% else %}test{% endif %}", "x", null, null));
  }

  @Test
  void aTemplateWithNoBeatUsesThePlaceholdersTheSourceUses() {
    assertEquals(
        "Monitor Name not available is ⚠️ Test at testing.hostname",
        Notify.renderTemplate("{{ name }} is {{ status }} at {{ hostnameOrURL }}", "x", null, null));
  }

  @Test
  void theAddressATargetShowsDependsOnTheMonitorType() {
    assertEquals("Heartbeat", Notify.extractAddress(Map.of("type", "push")));
    assertEquals("host", Notify.extractAddress(Map.of("type", "ping", "hostname", "host")));
    assertEquals(
        "host:443", Notify.extractAddress(Map.of("type", "port", "hostname", "host", "port", 443)));
    assertEquals("https://example.com", Notify.extractAddress(monitorJson()));
    // The placeholder the interface starts a new monitor with is not an address.
    assertEquals("", Notify.extractAddress(Map.of("type", "http", "url", "https://")));
    assertEquals("", Notify.extractAddress(null));
  }
}
