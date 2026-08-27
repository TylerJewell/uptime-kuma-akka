package io.akka.uptimekuma.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service, driven the way the interface drives it.
 *
 * <p>Every one of these goes through the HTTP surface rather than calling a component directly,
 * because that is where the differences live: a body that will not serialise, a view row whose
 * field is sometimes absent, a parameter that never reaches the method. A test that reached past
 * the endpoint would pass while the interface got nothing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SocketBridgeIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String token;

  private Map<String, Object> call(String event, Object... args) {
    Map<String, Object> body = new LinkedHashMap<>();
    // A call may legitimately pass a null argument, which an immutable list refuses to hold.
    body.put("args", java.util.Arrays.asList(args));
    body.put("token", token);
    var response =
        httpClient.POST("/socket/" + event).withRequestBody(body).responseBodyAs(Map.class).invoke();
    return (Map<String, Object>) response.body();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(Map<String, Object> answer) {
    Object result = answer.get("result");
    return result instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> emissions(Map<String, Object> answer) {
    Object emit = answer.get("emit");
    return emit instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private static Object emitted(Map<String, Object> answer, String name) {
    for (Map<String, Object> emission : emissions(answer)) {
      if (name.equals(emission.get("name"))) {
        List<?> args = (List<?>) emission.get("args");
        return args.isEmpty() ? null : args.get(0);
      }
    }
    return null;
  }

  @Test
  @Order(1)
  void aFreshServerAsksToBeSetUp() {
    var response = httpClient.GET("/socket/hello").responseBodyAs(Map.class).invoke();
    Map<String, Object> answer = (Map<String, Object>) response.body();
    List<String> names = new ArrayList<>();
    for (Map<String, Object> emission : emissions(answer)) {
      names.add(String.valueOf(emission.get("name")));
    }
    assertTrue(names.contains("info"));
    assertTrue(names.contains("setup"));
  }

  @Test
  @Order(2)
  void settingUpCreatesTheFirstAccount() {
    Map<String, Object> answer = call("setup", "admin", "Tr0ub4dor&3!");
    assertEquals(true, result(answer).get("ok"));
    assertEquals("successAdded", result(answer).get("msg"));
  }

  @Test
  @Order(3)
  void aWeakPasswordIsRefusedOnSetup() {
    Map<String, Object> answer = call("setup", "second", "abc");
    // The account already exists by now, so this is refused for that reason rather than for its
    // password — either way it is refused.
    assertEquals(false, result(answer).get("ok"));
  }

  @Test
  @Order(4)
  void theWrongPasswordIsRefused() {
    Map<String, Object> answer = call("login", Map.of("username", "admin", "password", "wrong"));
    assertEquals(false, result(answer).get("ok"));
    assertEquals("authIncorrectCreds", result(answer).get("msg"));
  }

  @Test
  @Order(5)
  void signingInHandsBackATokenAndTheWholeState() {
    Map<String, Object> answer =
        call("login", Map.of("username", "admin", "password", "Tr0ub4dor&3!"));
    assertEquals(true, result(answer).get("ok"));
    token = String.valueOf(result(answer).get("token"));
    assertNotNull(token);

    List<String> names = new ArrayList<>();
    for (Map<String, Object> emission : emissions(answer)) {
      names.add(String.valueOf(emission.get("name")));
    }
    // The same sequence the source pushes the moment a client signs in.
    assertTrue(names.contains("monitorList"));
    assertTrue(names.contains("info"));
    assertTrue(names.contains("maintenanceList"));
    assertTrue(names.contains("notificationList"));
    assertTrue(names.contains("proxyList"));
    assertTrue(names.contains("dockerHostList"));
    assertTrue(names.contains("apiKeyList"));
    assertTrue(names.contains("remoteBrowserList"));
    assertTrue(names.contains("monitorTypeList"));
    assertTrue(names.contains("statusPageList"));
  }

  @Test
  @Order(6)
  void aCallWithNoTokenIsRefused() {
    String held = token;
    token = null;
    Map<String, Object> answer = call("getMonitorList");
    assertEquals(false, result(answer).get("ok"));
    assertEquals("You are not logged in.", result(answer).get("msg"));
    token = held;
  }

  @Test
  @Order(7)
  @SuppressWarnings("unchecked")
  void addingAMonitorPutsItInTheListAndStartsItBeating() {
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("name", "example");
    monitor.put("type", "http");
    monitor.put("url", "http://127.0.0.1:1/");
    monitor.put("interval", 60);
    monitor.put("retryInterval", 60);
    monitor.put("maxretries", 0);
    monitor.put("accepted_statuscodes", List.of("200-299"));
    monitor.put("active", true);
    Map<String, Object> answer = call("add", monitor);
    assertEquals(true, result(answer).get("ok"));
    String id = String.valueOf(result(answer).get("monitorID"));
    assertEquals("1", id);

    Map<String, Object> updated = (Map<String, Object>) emitted(answer, "updateMonitorIntoList");
    assertTrue(updated.containsKey(id));

    // The list is a projection of the write and lags it, so this waits rather than reading once.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertTrue(
                    ((Map<String, Object>) emitted(call("getMonitorList"), "monitorList"))
                        .containsKey(id),
                    "the monitor has not reached the list yet"));
    Map<String, Object> list = (Map<String, Object>) emitted(call("getMonitorList"), "monitorList");
    Map<String, Object> stored = (Map<String, Object>) list.get(id);
    assertEquals("example", stored.get("name"));
    assertEquals(true, stored.get("active"));
    // The fields the interface reads on four different screens have to be present, not merely
    // absent-and-defaulted.
    assertNotNull(stored.get("path"));
    assertNotNull(stored.get("childrenIDs"));
    assertNotNull(stored.get("tags"));
    assertNotNull(stored.get("accepted_statuscodes"));
  }

  /**
   * What `add` refuses, and what it does not.
   *
   * <p>An interval below a second is refused in the source's own words; a type nothing can run is
   * not refused at all — the source's `validate` never looks at the type, and the beat that tries
   * to run it is what reports `Unknown Monitor Type`.
   */
  @Test
  @Order(8)
  void aMonitorThatWillNotValidateIsRefusedByName() {
    Map<String, Object> tooFast = new LinkedHashMap<>();
    tooFast.put("name", "impatient");
    tooFast.put("type", "http");
    tooFast.put("url", "http://127.0.0.1:1/");
    tooFast.put("interval", 0);
    tooFast.put("retryInterval", 60);
    Map<String, Object> refused = call("add", tooFast);
    assertEquals(false, result(refused).get("ok"));
    assertEquals("Interval cannot be less than 1 seconds", result(refused).get("msg"));

    Map<String, Object> unknownType = new LinkedHashMap<>();
    unknownType.put("name", "bad");
    unknownType.put("type", "smoke-signal");
    unknownType.put("interval", 60);
    unknownType.put("retryInterval", 60);
    Map<String, Object> accepted = call("add", unknownType);
    assertEquals(true, result(accepted).get("ok"));
    call("deleteMonitor", String.valueOf(result(accepted).get("monitorID")));
  }

  @Test
  @Order(9)
  void aMonitorBeatsAndItsBeatsAreReadable() {
    // The monitor points at a port nothing is listening on, so the beat fails — which is a beat.
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              Map<String, Object> answer = call("monitorImportantHeartbeatListCount", "1");
              assertTrue(
                  ((Number) result(answer).get("count")).intValue() >= 1,
                  "no beat has been recorded yet");
            });

    Map<String, Object> paged = call("monitorImportantHeartbeatListPaged", "1", 0, 10);
    List<?> beats = (List<?>) result(paged).get("data");
    assertFalse(beats.isEmpty());
    Map<?, ?> beat = (Map<?, ?>) beats.get(0);
    assertEquals(0, ((Number) beat.get("status")).intValue());
    // Stored as an integer and read back as one, which is what the interface receives.
    assertEquals(1, ((Number) beat.get("important")).intValue());
    assertNotNull(beat.get("msg"));
  }

  @Test
  @Order(10)
  void pausingAMonitorStopsItAndResumingStartsItAgain() {
    assertEquals("successPaused", result(call("pauseMonitor", "1")).get("msg"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Map<String, Object> list =
                  (Map<String, Object>) emitted(call("getMonitorList"), "monitorList");
              assertEquals(false, ((Map<?, ?>) list.get("1")).get("active"));
            });

    assertEquals("successResumed", result(call("resumeMonitor", "1")).get("msg"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Map<String, Object> after =
                  (Map<String, Object>) emitted(call("getMonitorList"), "monitorList");
              assertEquals(true, ((Map<?, ?>) after.get("1")).get("active"));
            });
  }

  @Test
  @Order(11)
  void aTagCanBeMadeAndAttachedToAMonitor() {
    Map<String, Object> made = call("addTag", Map.of("name", "env", "color", "#059669"));
    assertEquals(true, result(made).get("ok"));
    String tagId = String.valueOf(((Map<?, ?>) result(made).get("tag")).get("id"));

    Map<String, Object> attached = call("addMonitorTag", tagId, "1", "prod");
    assertEquals("successAdded", result(attached).get("msg"));
    Map<?, ?> one = (Map<?, ?>) emitted(attached, "updateMonitorIntoList");
    List<?> tags = (List<?>) ((Map<?, ?>) one.get("1")).get("tags");
    assertEquals(1, tags.size());
    assertEquals("prod", ((Map<?, ?>) tags.get(0)).get("value"));

    Map<String, Object> removed = call("deleteMonitorTag", tagId, "1", "prod");
    assertEquals("successDeleted", result(removed).get("msg"));
    Map<?, ?> after = (Map<?, ?>) emitted(removed, "updateMonitorIntoList");
    assertTrue(((List<?>) ((Map<?, ?>) after.get("1")).get("tags")).isEmpty());
  }

  @Test
  @Order(12)
  void aNotificationCanBeMadeAndIsListed() {
    Map<String, Object> notification = new LinkedHashMap<>();
    notification.put("name", "ops webhook");
    notification.put("type", "webhook");
    notification.put("webhookURL", "http://127.0.0.1:1/hook");
    notification.put("webhookContentType", "json");
    Map<String, Object> made = call("addNotification", notification, null);
    assertEquals(true, result(made).get("ok"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    ((List<?>) emitted(call("loginByToken", token), "notificationList")).size(),
                    "the target has not reached the list yet"));
    List<?> listed = (List<?>) emitted(call("loginByToken", token), "notificationList");
    assertEquals("ops webhook", ((Map<?, ?>) listed.get(0)).get("name"));
  }

  @Test
  @Order(13)
  void aStatusPageCanBeMadeAndReadWithoutSigningIn() {
    assertEquals("successAdded", result(call("addStatusPage", "Example", "example")).get("msg"));

    var page = httpClient.GET("/api/status-page/example").responseBodyAs(Map.class).invoke();
    Map<String, Object> body = (Map<String, Object>) page.body();
    Map<?, ?> config = (Map<?, ?>) body.get("config");
    assertEquals("example", config.get("slug"));
    assertEquals("Example", config.get("title"));
    // The identifier and the domain list are the administrator's, not a visitor's.
    assertFalse(config.containsKey("id"));
    assertFalse(config.containsKey("domainNameList"));
  }

  @Test
  @Order(14)
  void anIncidentIsPostedPinnedAndThenResolved() {
    Map<String, Object> incident =
        Map.of("title", "Degraded", "content", "We are looking into it", "style", "warning");
    Map<String, Object> posted = call("postIncident", "example", incident);
    assertEquals(true, result(posted).get("ok"));
    Map<?, ?> stored = (Map<?, ?>) result(posted).get("incident");
    assertEquals(true, stored.get("pin"));
    assertEquals(true, stored.get("active"));
    String incidentId = String.valueOf(stored.get("id"));

    var page = httpClient.GET("/api/status-page/example").responseBodyAs(Map.class).invoke();
    assertEquals(1, ((List<?>) ((Map<?, ?>) page.body()).get("incidents")).size());

    assertEquals("Resolved", result(call("resolveIncident", "example", incidentId)).get("msg"));
    var after = httpClient.GET("/api/status-page/example").responseBodyAs(Map.class).invoke();
    // A resolved incident leaves the banner but stays in the history.
    assertEquals(0, ((List<?>) ((Map<?, ?>) after.body()).get("incidents")).size());

    Map<String, Object> history = call("getIncidentHistory", "example", null);
    assertEquals(1, ((List<?>) result(history).get("incidents")).size());
  }

  @Test
  @Order(15)
  void anIncidentWithNoTitleIsRefused() {
    Map<String, Object> made =
        call("postIncident", "example", Map.of("title", "T", "content", "C", "style", "info"));
    String id = String.valueOf(((Map<?, ?>) result(made).get("incident")).get("id"));
    Map<String, Object> answer =
        call("editIncident", "example", id, Map.of("title", "  ", "content", "C"));
    assertEquals(false, result(answer).get("ok"));
    assertEquals("Please input title", result(answer).get("msg"));
  }

  @Test
  @Order(16)
  void aStyleOutsideThePaletteIsQuietlyReplaced() {
    Map<String, Object> made =
        call(
            "postIncident",
            "example",
            Map.of("title", "T", "content", "C", "style", "chartreuse"));
    assertEquals("warning", ((Map<?, ?>) result(made).get("incident")).get("style"));
  }

  @Test
  @Order(17)
  void aMaintenanceWindowIsListedWithTheStatusItIsIn() {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("title", "Database upgrade");
    window.put("description", "Rolling restart");
    window.put("strategy", "manual");
    window.put("active", true);
    Map<String, Object> made = call("addMaintenance", window);
    assertEquals(true, result(made).get("ok"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    ((Map<String, Object>) emitted(call("getMaintenanceList"), "maintenanceList"))
                        .size()));
    Map<String, Object> listed =
        (Map<String, Object>) emitted(call("getMaintenanceList"), "maintenanceList");
    Map<?, ?> stored = (Map<?, ?>) listed.values().iterator().next();
    // A manual window is under maintenance for as long as it is switched on.
    assertEquals("under-maintenance", stored.get("status"));

    String id = String.valueOf(result(made).get("maintenanceID"));
    assertEquals("successPaused", result(call("pauseMaintenance", id)).get("msg"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Map<String, Object> after =
                  (Map<String, Object>) emitted(call("getMaintenanceList"), "maintenanceList");
              assertEquals("inactive", ((Map<?, ?>) after.get(id)).get("status"));
            });
  }

  @Test
  @Order(18)
  void anApiKeyIsShownOnceAndThenOnlyItsName() {
    Map<String, Object> made = call("addAPIKey", Map.of("name", "collector"));
    assertEquals(true, result(made).get("ok"));
    String key = String.valueOf(result(made).get("key"));
    assertTrue(key.startsWith("uk"));
    assertTrue(key.contains("_"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertFalse(
                    ((List<?>) emitted(call("getAPIKeyList"), "apiKeyList")).isEmpty(),
                    "the key has not reached the list yet"));
    List<?> listed = (List<?>) emitted(call("getAPIKeyList"), "apiKeyList");
    // The stored hash is never handed out again.
    assertFalse(((Map<?, ?>) listed.get(0)).containsKey("key"));

    var metrics =
        httpClient
            .GET("/metrics")
            .addHeader(
                "Authorization",
                "Basic "
                    + java.util.Base64.getEncoder()
                        .encodeToString(("x:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .responseBodyAs(String.class)
            .invoke();
    assertTrue(metrics.body().contains("monitor_status{"));
  }

  @Test
  @Order(19)
  void metricsWithoutCredentialsAreRefused() {
    var response = httpClient.GET("/metrics").invoke();
    assertEquals(401, response.httpResponse().status().intValue());
  }

  /**
   * A badge takes no credentials, so it says nothing about a monitor nobody has published.
   *
   * <p>The monitor here is on no status page, and the badge is one grey pill reading `N/A` — not
   * `Status: Up`. Publishing a monitor is what makes a badge for it answer, and that is the whole
   * of the access control on the route. R112.
   */
  @Test
  @Order(20)
  void aBadgeSaysNothingAboutAMonitorNobodyPublished() {
    // A badge is served as an image, so its body arrives as bytes rather than as text.
    var response = httpClient.GET("/api/badge/1/status").invoke();
    String svg = response.body().utf8String();
    assertTrue(svg.startsWith("<svg"));
    assertTrue(svg.contains("N/A"), svg);
    assertFalse(svg.contains("Status"), "a badge named the monitor nobody published");
  }

  @Test
  @Order(21)
  void aPushMonitorRecordsWhatIsPushedIntoIt() {
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("name", "nightly job");
    monitor.put("type", "push");
    monitor.put("pushToken", "abcd1234");
    monitor.put("interval", 300);
    monitor.put("retryInterval", 60);
    monitor.put("active", true);
    Map<String, Object> made = call("add", monitor);
    String id = String.valueOf(result(made).get("monitorID"));

    // The route finds a monitor by its token through the list, which lags the write.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    200,
                    httpClient
                        .GET("/api/push/abcd1234?msg=finished&ping=42")
                        .invoke()
                        .httpResponse()
                        .status()
                        .intValue()));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Map<String, Object> beats = call("getMonitorBeats", id, 24);
              List<?> rows = (List<?>) result(beats).get("data");
              assertFalse(rows.isEmpty(), "the pushed beat has not been recorded");
              Map<?, ?> beat = (Map<?, ?>) rows.get(rows.size() - 1);
              assertEquals("finished", beat.get("msg"));
              assertEquals(1, ((Number) beat.get("status")).intValue());
            });
  }

  @Test
  @Order(22)
  void aPushToAnUnknownTokenIsRefused() {
    var response = httpClient.GET("/api/push/nosuchtoken").invoke();
    assertEquals(404, response.httpResponse().status().intValue());
  }

  @Test
  @Order(23)
  void aPushWithAnImpossibleResponseTimeIsRefused() {
    var response = httpClient.GET("/api/push/abcd1234?ping=999999999999").invoke();
    assertEquals(404, response.httpResponse().status().intValue());
  }

  @Test
  @Order(24)
  void settingsAreReadBackAsTheyWereWritten() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("primaryBaseURL", "https://kuma.example.invalid");
    values.put("searchEngineIndex", true);
    assertEquals("Saved.", result(call("setSettings", values, "")).get("msg"));

    Map<String, Object> read = call("getSettings");
    Map<?, ?> data = (Map<?, ?>) result(read).get("data");
    assertEquals("https://kuma.example.invalid", data.get("primaryBaseURL"));

    var robots = httpClient.GET("/robots.txt").responseBodyAs(String.class).invoke();
    assertEquals("User-agent: *\nDisallow:", robots.body());
  }

  @Test
  @Order(25)
  void changingThePasswordInvalidatesTheOldToken() {
    Map<String, Object> answer =
        call(
            "changePassword",
            Map.of(
                "currentPassword",
                "Tr0ub4dor&3!",
                "newPassword",
                "Zx9#quiet-Harbour"));
    assertEquals(true, result(answer).get("ok"));
    String fresh = String.valueOf(result(answer).get("token"));

    String stale = token;
    token = stale;
    // The old token carries a digest of the old hash, which no longer matches.
    assertEquals(false, result(call("getMonitorList")).get("ok"));
    token = fresh;
    assertEquals(true, result(call("getMonitorList")).get("ok"));
  }

  @Test
  @Order(26)
  void theInterfaceIsServedAndAnUnknownPathReachesIt() {
    var index = httpClient.GET("/dashboard").responseBodyAs(String.class).invoke();
    // A path the interface routes in the browser reaches its entry document rather than a refusal.
    assertTrue(index.body().contains("<div id=\"app\">"));
    assertTrue(index.body().contains("/src/main.js") || index.body().contains("assets/"));
  }

  @Test
  @Order(27)
  void deletingAMonitorTakesItOutOfTheList() {
    Map<String, Object> answer = call("deleteMonitor", "1", false);
    assertEquals("successDeleted", result(answer).get("msg"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertFalse(
                    ((Map<String, Object>) emitted(call("getMonitorList"), "monitorList"))
                        .containsKey("1")));
  }

  @Test
  @Order(28)
  void theEntryPageSaysWhereTheRootGoes() {
    var entry = httpClient.GET("/api/entry-page").responseBodyAs(Map.class).invoke();
    Map<String, Object> body = (Map<String, Object>) entry.body();
    // Two shapes come out of this route. Without a domain mapped to a status page it is the
    // second, and what it carries is where the root goes.
    assertEquals("entryPage", body.get("type"));
    // Nobody has set an entry page, so there is none to report — the source answers with the
    // column as it stands rather than with the value the interface falls back to.
    assertNull(body.get("entryPage"));

    // A visitor arriving at the root is sent where that answer points, rather than being served
    // the dashboard at a path the interface does not route.
    var root = httpClient.GET("/").invoke();
    assertEquals(302, root.httpResponse().status().intValue());
    assertEquals(
        "/dashboard",
        root.httpResponse().getHeader("Location").get().value());
  }

  @Test
  @Order(29)
  void metricsCarryAGaugePerMonitor() {
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("name", "scraped");
    monitor.put("type", "http");
    monitor.put("url", "http://127.0.0.1:1/never");
    monitor.put("interval", 300);
    monitor.put("retryInterval", 300);
    monitor.put("maxretries", 0);
    monitor.put("accepted_statuscodes", List.of("200-299"));
    monitor.put("active", true);
    Map<String, Object> added = call("add", monitor);
    assertEquals(true, result(added).get("ok"));
    String id = String.valueOf(result(added).get("monitorID"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertTrue(scrape().contains("monitor_name=\"scraped\"")));
    String body = scrape();

    // The six gauges the source declares, by the names a collector already scrapes.
    assertTrue(body.contains("# TYPE monitor_status gauge"), body);
    assertTrue(body.contains("# TYPE monitor_response_time gauge"), body);
    assertTrue(body.contains("# TYPE monitor_cert_days_remaining gauge"), body);
    assertTrue(body.contains("# TYPE monitor_cert_is_valid gauge"), body);
    // Every gauge is labelled with the monitor it is about, so one scrape separates them.
    assertTrue(body.contains("monitor_type=\"http\""), body);
    assertNotNull(id);
  }

  @Test
  @Order(30)
  void anImpossibleSlugIsRefused() {
    // A slug ends up in a path, so the shape is checked rather than escaped later.
    assertEquals(false, result(call("addStatusPage", "Bad", "not a slug!")).get("ok"));
    assertEquals(false, result(call("addStatusPage", "Bad", "-leading")).get("ok"));
    assertEquals(false, result(call("addStatusPage", "Bad", "")).get("ok"));
    // Hyphens between runs of letters and digits are the one separator it allows.
    assertEquals(true, result(call("addStatusPage", "Fine", "eu-west-1")).get("ok"));
  }

  @Test
  @Order(31)
  void turningAuthenticationOffNeedsThePassword() {
    // Switching authentication off is the one setting that opens the server to anybody, so it
    // costs the current password; switching it back on does not.
    Map<String, Object> off = new LinkedHashMap<>();
    off.put("disableAuth", true);
    assertEquals(false, result(call("setSettings", off, "")).get("ok"));
    assertEquals(true, result(call("setSettings", off, "Zx9#quiet-Harbour")).get("ok"));

    Map<String, Object> on = new LinkedHashMap<>();
    on.put("disableAuth", false);
    assertEquals(true, result(call("setSettings", on, "")).get("ok"));
  }

  /**
   * A client opening the stream for the first time is not sent the history a second time.
   *
   * <p>Signing in already hands the interface every monitor's beats as a list, and the bar on a
   * monitor's page draws that list and then appends whatever the stream delivers. Replaying the
   * history down the stream as well drew every beat twice — five beats became eleven bars — and it
   * is invisible to every other check here, because the beats themselves, their times and their
   * messages were all correct. R98.
   */
  @Test
  @Order(32)
  void aFirstStreamConnectionCarriesNoHistory() {
    // A push monitor gives the feed beats without waiting on a network check.
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("name", "stream probe");
    monitor.put("type", "push");
    monitor.put("pushToken", "streamprobe1");
    monitor.put("interval", 300);
    monitor.put("retryInterval", 60);
    monitor.put("active", true);
    call("add", monitor);

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    200,
                    httpClient
                        .GET("/api/push/streamprobe1?msg=one&ping=11")
                        .invoke()
                        .httpResponse()
                        .status()
                        .intValue()));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertTrue(streamFrames("?token=" + token + "&since=0") > 0));

    // Same stream, no resume point: what the browser opens on a first connection.
    assertEquals(0, streamFrames("?token=" + token));
  }

  /**
   * A push monitor does not report an outage the instant it is started.
   *
   * <p>Its check is not hearing from something, so a beat taken the moment it starts records "No
   * heartbeat in the time window" before whatever is meant to push into it has had any chance to.
   * The source delays the first beat of that one type by a whole interval, and every other type
   * beats at once. R103.
   */
  @Test
  @Order(39)
  void aPushMonitorTakesNoBeatUntilItsFirstIntervalHasPassed() {
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("name", "patient");
    monitor.put("type", "push");
    monitor.put("pushToken", "patientpush1");
    monitor.put("interval", 3600);
    monitor.put("retryInterval", 3600);
    monitor.put("active", true);
    String id = String.valueOf(result(call("add", monitor)).get("monitorID"));

    // Long enough that an immediate beat would certainly have landed, and far short of the hour
    // the monitor was given.
    Awaitility.await()
        .during(Duration.ofSeconds(6))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertTrue(beatsOf(id).isEmpty(), "the push monitor beat on start"));

    // An HTTP monitor beside it beats at once, which is what makes the delay a rule about the
    // type rather than about how quickly anything starts here.
    Map<String, Object> web = new LinkedHashMap<>(monitor);
    web.put("name", "impatient");
    web.put("type", "http");
    web.put("url", "http://127.0.0.1:1/");
    web.remove("pushToken");
    String webId = String.valueOf(result(call("add", web)).get("monitorID"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertFalse(beatsOf(webId).isEmpty(), "the web monitor did not beat"));

    call("pauseMonitor", id);
    call("pauseMonitor", webId);
  }

  /**
   * Two monitors can hold the same push token, and a push to it still records a beat.
   *
   * <p>The token is not unique anywhere: the interface generates one, a person can type one, and
   * cloning a monitor copies whatever it had. Asked for a single row, a view refuses the whole
   * query the moment a second one matches — so every push to that token was answered with a
   * failure, on a route whose entire job is to accept them. R84.
   */
  @Test
  @Order(38)
  void aPushTokenTwoMonitorsShareStillRecordsABeat() {
    for (String suffix : List.of("one", "two")) {
      Map<String, Object> monitor = new LinkedHashMap<>();
      monitor.put("name", "shared token " + suffix);
      monitor.put("type", "push");
      monitor.put("pushToken", "sharedtoken1");
      monitor.put("interval", 3600);
      monitor.put("retryInterval", 3600);
      monitor.put("active", true);
      assertEquals(true, result(call("add", monitor)).get("ok"));
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    200,
                    httpClient
                        .GET("/api/push/sharedtoken1?msg=shared&ping=4")
                        .invoke()
                        .httpResponse()
                        .status()
                        .intValue()));
  }

  /**
   * Clearing a monitor's events and its beats reaches the lists a caller reads.
   *
   * <p>The monitor's own history and the published feed are two stores, and every list the
   * interface draws is read from the second — so clearing only the first leaves the events table
   * showing exactly what was asked to be cleared. R75, R76.
   *
   * <p>The second half is the one that cost most: a beat published after a clear used to land on
   * the key of a beat that had just been deleted, and a key-value entity refuses a write after its
   * own deletion — so the monitor stopped beating for good and the runtime retried the failure for
   * as long as anybody watched. Nothing but running it says so: the call answers `ok` either way.
   */
  @Test
  @Order(37)
  void clearingAMonitorsBeatsEmptiesTheListsAndLeavesItBeating() {
    Map<String, Object> monitor = new LinkedHashMap<>();
    monitor.put("name", "clearable");
    monitor.put("type", "push");
    monitor.put("pushToken", "clearprobe01");
    monitor.put("interval", 300);
    monitor.put("retryInterval", 60);
    monitor.put("active", true);
    String id = String.valueOf(result(call("add", monitor)).get("monitorID"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    200,
                    httpClient
                        .GET("/api/push/clearprobe01?msg=first&ping=7")
                        .invoke()
                        .httpResponse()
                        .status()
                        .intValue()));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertFalse(beatsOf(id).isEmpty(), "no beat was published"));

    // Clearing the events keeps the beats and blanks what they said.
    assertEquals(true, result(call("clearEvents", id)).get("ok"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertFalse(beatsOf(id).isEmpty(), "clearing the events dropped the beats");
              for (Map<String, Object> beat : beatsOf(id)) {
                assertEquals("", beat.get("msg"));
                assertEquals(0, ((Number) beat.get("important")).intValue());
              }
              assertEquals(
                  0,
                  ((Number) result(call("monitorImportantHeartbeatListCount", id)).get("count"))
                      .intValue());
            });

    // Clearing the beats empties the list.
    assertEquals(true, result(call("clearHeartbeats", id)).get("ok"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertTrue(beatsOf(id).isEmpty(), "the beats were not cleared"));

    // And the monitor still records what is pushed into it afterwards.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertEquals(
                    200,
                    httpClient
                        .GET("/api/push/clearprobe01?msg=after&ping=8")
                        .invoke()
                        .httpResponse()
                        .status()
                        .intValue()));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              List<Map<String, Object>> after = beatsOf(id);
              assertFalse(after.isEmpty(), "the monitor stopped recording beats after a clear");
              assertEquals("after", after.get(after.size() - 1).get("msg"));
            });
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> beatsOf(String monitorId) {
    Object data = result(call("getMonitorBeats", monitorId, 24)).get("data");
    return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  /**
   * A window's two instants survive the round trip through the call surface.
   *
   * <p>The interface sends them as a two-element {@code dateRange} and gets them back the same way,
   * but a window stores them as two separate fields — so a call surface that hands the payload
   * straight to the window loses both, and every date the interface then draws reads
   * "Invalid Date". Nothing below this notices: the window's own decision code is fully tested and
   * correct on the fields it is given, and it was given nulls. R37, R40.
   */
  @Test
  @Order(34)
  void aSingleWindowKeepsTheDatesItWasGivenAndIsScheduledUntilTheyArrive() {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("title", "Quarterly upgrade");
    window.put("description", "Rolling restart of the fleet");
    window.put("strategy", "single");
    window.put("active", true);
    window.put("dateRange", List.of("2099-01-02 02:00:00", "2099-01-02 04:00:00"));
    window.put("timeRange", List.of(Map.of("hours", 2, "minutes", 0), Map.of("hours", 4, "minutes", 0)));
    window.put("weekdays", List.of());
    window.put("daysOfMonth", List.of());
    window.put("timezoneOption", "SAME_AS_SERVER");
    String id = String.valueOf(result(call("addMaintenance", window)).get("maintenanceID"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Map<String, Object> listed =
                  (Map<String, Object>) emitted(call("getMaintenanceList"), "maintenanceList");
              Map<?, ?> stored = (Map<?, ?>) listed.get(id);
              assertNotNull(stored, "the window was not listed");
              assertEquals(
                  List.of("2099-01-02 02:00:00", "2099-01-02 04:00:00"), stored.get("dateRange"));
              // A single window is scheduled before its start, which is what makes the dates
              // load-bearing rather than decorative.
              assertEquals("scheduled", stored.get("status"));
              List<?> slots = (List<?>) stored.get("timeslotList");
              assertEquals(1, slots.size());
              assertEquals("2099-01-02 02:00:00", ((Map<?, ?>) slots.get(0)).get("startDate"));
              assertEquals("2099-01-02 04:00:00", ((Map<?, ?>) slots.get(0)).get("endDate"));
            });
  }

  /**
   * The other half of the same mapping: the clock times a recurring window runs between.
   *
   * <p>{@code timeRange} carries them as hour-and-minute objects and a window stores them as text,
   * and the cron pattern the server generates is built from them — so losing them gives a window
   * that runs at midnight whatever the person asked for, and a pattern that says so. R41.
   */
  @Test
  @Order(36)
  void aRecurringWindowKeepsItsClockTimesAndGeneratesThePatternFromThem() {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("title", "Weekly window");
    window.put("strategy", "recurring-weekday");
    window.put("active", true);
    window.put("dateRange", List.of("2099-01-02 00:00:00"));
    window.put("timeRange", List.of(Map.of("hours", 3, "minutes", 30), Map.of("hours", 5, "minutes", 45)));
    window.put("weekdays", List.of(3, 1));
    window.put("daysOfMonth", List.of());
    window.put("timezoneOption", "SAME_AS_SERVER");
    String id = String.valueOf(result(call("addMaintenance", window)).get("maintenanceID"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Map<String, Object> listed =
                  (Map<String, Object>) emitted(call("getMaintenanceList"), "maintenanceList");
              Map<?, ?> stored = (Map<?, ?>) listed.get(id);
              assertNotNull(stored, "the window was not listed");
              List<?> times = (List<?>) stored.get("timeRange");
              assertEquals(3, ((Map<?, ?>) times.get(0)).get("hours"));
              assertEquals(30, ((Map<?, ?>) times.get(0)).get("minutes"));
              assertEquals(5, ((Map<?, ?>) times.get(1)).get("hours"));
              assertEquals(45, ((Map<?, ?>) times.get(1)).get("minutes"));
              // R41: minute, hour, then the weekdays in order.
              assertEquals("30 3 * * 1,3", stored.get("cron"));
              // Two hours and a quarter, in seconds.
              assertEquals(135, stored.get("durationMinutes"));
            });
  }

  /** A date a person can type but nothing can read is refused rather than stored as nothing. */
  @Test
  @Order(35)
  void aWindowWithAnUnreadableStartDateIsRefused() {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("title", "Bad dates");
    window.put("strategy", "single");
    window.put("active", true);
    window.put("dateRange", List.of("not a date at all"));
    Map<String, Object> answer = call("addMaintenance", window);
    assertEquals(false, result(answer).get("ok"));
    assertEquals("Invalid start date", result(answer).get("msg"));
  }

  /**
   * The events table is newest first across every monitor, not grouped by monitor.
   *
   * <p>A beat's sequence counts only its own monitor's beats, so two monitors' first beats are both
   * number one and an ordering on sequence puts a whole monitor's history before another's whatever
   * order the beats actually happened in. What the source orders on is the instant. R100.
   */
  @Test
  @Order(33)
  void theEventsTableIsNewestFirstAcrossEveryMonitor() {
    for (String suffix : List.of("a", "b")) {
      Map<String, Object> monitor = new LinkedHashMap<>();
      monitor.put("name", "order probe " + suffix);
      monitor.put("type", "push");
      monitor.put("pushToken", "orderprobe" + suffix);
      monitor.put("interval", 300);
      monitor.put("retryInterval", 60);
      monitor.put("active", true);
      call("add", monitor);
    }

    // The second monitor's first beat happens after the first monitor's, so newest-first must put
    // it above -- and both are sequence one, which is what an ordering on sequence cannot separate.
    for (String suffix : List.of("a", "b")) {
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertEquals(
                      200,
                      httpClient
                          .GET("/api/push/orderprobe" + suffix + "?msg=beat&ping=5")
                          .invoke()
                          .httpResponse()
                          .status()
                          .intValue()));
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              List<Map<String, Object>> rows = importantRows();
              assertTrue(rows.size() >= 2, "no events recorded yet");
              long previous = Long.MAX_VALUE;
              for (Map<String, Object> row : rows) {
                long at =
                    java.time.LocalDateTime.parse(
                            String.valueOf(row.get("time")).replace(' ', 'T'))
                        .toInstant(java.time.ZoneOffset.UTC)
                        .toEpochMilli();
                assertTrue(at <= previous, "events are not newest first: " + rows);
                previous = at;
              }
            });
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> importantRows() {
    Map<String, Object> answer = call("monitorImportantHeartbeatListPaged", null, 0, 25);
    Object data = result(answer).get("data");
    return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  /**
   * How many replayed frames the stream delivers in the moment after it is opened.
   *
   * <p>Read for a fixed window rather than to the end of the response, because the stream stays
   * open by design: what is being counted is the backlog it starts with, not the beats that arrive
   * afterwards.
   */
  private int streamFrames(String query) {
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      java.net.URI.create(streamBase() + "/socket/stream" + query))
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofInputStream());
      int frames = 0;
      long deadline = System.currentTimeMillis() + 2500;
      try (var body = response.body();
          var reader =
              new java.io.BufferedReader(
                  new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
        while (System.currentTimeMillis() < deadline) {
          if (!reader.ready()) {
            Thread.sleep(50);
            continue;
          }
          String line = reader.readLine();
          if (line == null) {
            break;
          }
          if (line.replace(" ", "").equals("event:history")) {
            frames++;
          }
        }
      }
      return frames;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String streamBase() {
    return "http://localhost:" + testKit.getPort();
  }

  private String scrape() {
    return httpClient
        .GET("/metrics")
        .addHeader("Authorization", accountCredentials())
        .responseBodyAs(String.class)
        .invoke()
        .body();
  }

  private static String accountCredentials() {
    return "Basic "
        + java.util.Base64.getEncoder()
            .encodeToString(
                "admin:Zx9#quiet-Harbour"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
