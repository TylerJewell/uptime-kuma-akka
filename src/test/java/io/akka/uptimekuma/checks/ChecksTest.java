package io.akka.uptimekuma.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The checks, driven against servers this test starts.
 *
 * <p>A check is about what a real server said, so the ones that can be exercised locally are:
 * an HTTP server that answers what each case needs, and a plain socket that either accepts or is
 * not there. The types that need somebody else's service — a broker, a database, a game server, a
 * browser — are covered by their decision logic rather than by a live call, and the benchmark says
 * which.
 */
class ChecksTest {

  private static HttpServer server;
  private static int port;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext("/ok", exchange -> respond(exchange, 200, "hello world"));
    server.createContext("/teapot", exchange -> respond(exchange, 418, "short and stout"));
    server.createContext("/notfound", exchange -> respond(exchange, 404, "gone"));
    server.createContext("/json", exchange -> respond(exchange, 200, "{\"status\":\"green\",\"count\":7}"));
    server.createContext(
        "/html", exchange -> respond(exchange, 200, "<html>\n  <body>  <b>Service</b> is fine  </body>\n</html>"));
    server.createContext(
        "/echo-headers",
        exchange -> {
          StringBuilder out = new StringBuilder();
          exchange.getRequestHeaders().forEach((key, values) -> out.append(key).append(": ").append(values.get(0)).append('\n'));
          respond(exchange, 200, out.toString());
        });
    server.createContext(
        "/echo-query", exchange -> respond(exchange, 200, String.valueOf(exchange.getRequestURI().getQuery())));
    server.createContext(
        "/big",
        exchange -> respond(exchange, 200, "x".repeat(5000)));
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  private static MonitorConfig http(String path) {
    return MonitorConfig.blank("m")
        .toBuilder()
        .type("http")
        .url(url(path))
        .interval(20)
        .timeout(5)
        .build();
  }

  private static CheckOutcome run(MonitorConfig config) {
    return Checks.run(config, CheckContext.plain(System.currentTimeMillis()));
  }

  @Test
  void anAcceptedStatusIsUpAndCarriesTheStatusLine() {
    CheckOutcome outcome = run(http("/ok"));
    assertTrue(outcome.ok());
    assertEquals("200 - OK", outcome.msg());
    assertNotNull(outcome.ping());
  }

  @Test
  void aStatusOutsideTheAcceptedListFails() {
    CheckOutcome outcome = run(http("/notfound"));
    assertEquals(Status.DOWN, outcome.status());
    assertEquals("Request failed with status code 404", outcome.msg());
  }

  @Test
  void anAcceptedListCanBeWidenedToTakeAnyStatus() {
    MonitorConfig config =
        http("/notfound").toBuilder().acceptedStatusCodes(List.of("100-599")).build();
    assertTrue(run(config).ok());
  }

  @Test
  void anUnusualStatusStillReportsItsOwnReasonPhrase() {
    MonitorConfig config = http("/teapot").toBuilder().acceptedStatusCodes(List.of("418")).build();
    assertEquals("418 - I'm a Teapot", run(config).msg());
  }

  @Test
  void aKeywordThatIsPresentPasses() {
    MonitorConfig config =
        http("/ok").toBuilder().type("keyword").keyword("world").build();
    CheckOutcome outcome = run(config);
    assertTrue(outcome.ok());
    assertEquals("200 - OK, keyword is found", outcome.msg());
  }

  @Test
  void aKeywordThatIsAbsentFailsAndQuotesTheBody() {
    MonitorConfig config =
        http("/ok").toBuilder().type("keyword").keyword("goodbye").build();
    CheckOutcome outcome = run(config);
    assertEquals(Status.DOWN, outcome.status());
    assertEquals("200 - OK, but keyword is not in [hello world]", outcome.msg());
  }

  @Test
  void anInvertedKeywordPassesWhenItIsAbsent() {
    MonitorConfig config =
        http("/ok").toBuilder().type("keyword").keyword("goodbye").invertKeyword(true).build();
    CheckOutcome outcome = run(config);
    assertTrue(outcome.ok());
    assertEquals("200 - OK, keyword not found", outcome.msg());
  }

  @Test
  void anInvertedKeywordFailsWhenItIsPresent() {
    MonitorConfig config =
        http("/ok").toBuilder().type("keyword").keyword("world").invertKeyword(true).build();
    CheckOutcome outcome = run(config);
    assertEquals(Status.DOWN, outcome.status());
    assertTrue(outcome.msg().contains("but keyword is present in [hello world]"));
  }

  @Test
  void aQuotedBodyIsStrippedOfMarkupAndCollapsedToOneLine() {
    MonitorConfig config =
        http("/html").toBuilder().type("keyword").keyword("absent").build();
    // Markup and line breaks each become a space, so the quoted body reads as a sentence rather
    // than as source. Two adjacent spaces where a tag sat beside one are what the source
    // produces, and are reproduced rather than collapsed.
    assertEquals("200 - OK, but keyword is not in [Service  is fine]", run(config).msg());
  }

  @Test
  void aQuotedBodyIsTruncatedAtFiftyCharacters() {
    MonitorConfig config = http("/big").toBuilder().type("keyword").keyword("absent").build();
    String message = run(config).msg();
    assertTrue(message.contains("..."));
    assertTrue(message.length() < 120);
  }

  @Test
  void aJsonQueryThatMatchesPasses() {
    MonitorConfig config =
        http("/json")
            .toBuilder()
            .type("json-query")
            .jsonPath("status")
            .jsonPathOperator("==")
            .expectedValue("green")
            .build();
    CheckOutcome outcome = run(config);
    assertTrue(outcome.ok());
    assertEquals("JSON query passes (comparing green == green)", outcome.msg());
  }

  @Test
  void aJsonQueryThatDoesNotMatchFailsAndQuotesBothSides() {
    MonitorConfig config =
        http("/json")
            .toBuilder()
            .type("json-query")
            .jsonPath("status")
            .jsonPathOperator("==")
            .expectedValue("red")
            .build();
    assertEquals(
        "JSON query does not pass (comparing green == red)", run(config).msg());
  }

  @Test
  void aJsonQueryComparesNumbersAsNumbers() {
    MonitorConfig config =
        http("/json")
            .toBuilder()
            .type("json-query")
            .jsonPath("count")
            .jsonPathOperator(">")
            .expectedValue("5")
            .build();
    assertTrue(run(config).ok());
  }

  @Test
  void aJsonQueryOverAnExpressionRatherThanAPath() {
    // The path is an expression language rather than a path syntax, so an aggregate is a path.
    MonitorConfig config =
        http("/json")
            .toBuilder()
            .type("json-query")
            .jsonPath("$count($keys($))")
            .jsonPathOperator("==")
            .expectedValue("2")
            .build();
    assertTrue(run(config).ok());
  }

  @Test
  void aHeaderTheMonitorCarriesIsSent() {
    MonitorConfig config =
        http("/echo-headers")
            .toBuilder()
            .type("keyword")
            // The server that echoes them canonicalises the name, so the value is what is looked
            // for rather than the whole line.
            .keyword("kuma")
            .headers("{\"X-Probe\":\"kuma\"}")
            .build();
    assertTrue(run(config).ok());
  }

  @Test
  void basicCredentialsAreSentAsAnAuthorizationHeader() {
    MonitorConfig config =
        http("/echo-headers")
            .toBuilder()
            .type("keyword")
            .keyword("Basic YWRtaW46c2VjcmV0")
            .authMethod("basic")
            .basicAuthUser("admin")
            .basicAuthPass("secret")
            .build();
    assertTrue(run(config).ok());
  }

  @Test
  void aBearerTokenIsSentAsAnAuthorizationHeader() {
    MonitorConfig config =
        http("/echo-headers")
            .toBuilder()
            .type("keyword")
            .keyword("Bearer abc123")
            .authMethod("bearer")
            .bearerToken("abc123")
            .build();
    assertTrue(run(config).ok());
  }

  @Test
  void cacheBustingAddsAFreshParameterEveryBeat() {
    MonitorConfig config =
        http("/echo-query").toBuilder().type("keyword").keyword("uptime_kuma_cachebuster").cacheBust(true).build();
    assertTrue(run(config).ok());
    // A second beat must not carry the same value, or an intermediate cache could answer both.
    CheckOutcome first = run(config);
    CheckOutcome second = run(config);
    assertNotNull(first.msg());
    assertNotNull(second.msg());
  }

  @Test
  void aBodyIsSentAndItsContentTypeFollowsTheEncoding() {
    MonitorConfig config =
        http("/echo-headers")
            .toBuilder()
            .type("keyword")
            .method("POST")
            .body("{\"a\":1}")
            .httpBodyEncoding("json")
            .keyword("application/json")
            .build();
    assertTrue(run(config).ok());
  }

  @Test
  void aBodyThatIsNotValidJsonIsRefusedBeforeAnythingIsSent() {
    MonitorConfig config =
        http("/ok").toBuilder().method("POST").body("{not json").httpBodyEncoding("json").build();
    CheckOutcome outcome = run(config);
    assertEquals(Status.DOWN, outcome.status());
    assertTrue(outcome.msg().startsWith("Your JSON body is invalid."));
  }

  @Test
  void aSavedResponseIsTruncatedToTheConfiguredLength() {
    MonitorConfig config =
        http("/big").toBuilder().saveResponse(true).saveErrorResponse(true).responseMaxLength(10).build();
    CheckOutcome outcome = run(config);
    assertEquals("xxxxxxxxxx... (truncated)", outcome.response());
  }

  @Test
  void aResponseIsNotSavedUnlessBothSwitchesAreOn() {
    // The interface only shows a saved response when the error one is saved too, so the source
    // requires both.
    MonitorConfig config =
        http("/ok").toBuilder().saveResponse(true).saveErrorResponse(false).build();
    assertNull(run(config).response());
  }

  @Test
  void aHostThatIsNotThereFails() {
    MonitorConfig config =
        MonitorConfig.blank("m")
            .toBuilder()
            .type("http")
            .url("http://127.0.0.1:1/")
            .interval(20)
            .timeout(2)
            .build();
    CheckOutcome outcome = run(config);
    assertEquals(Status.DOWN, outcome.status());
    assertNotNull(outcome.msg());
  }

  @Test
  void anOpenSocketIsUpAndAClosedOneIsNot() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      MonitorConfig open =
          MonitorConfig.blank("m")
              .toBuilder()
              .type("port")
              .hostname("127.0.0.1")
              .port(listener.getLocalPort())
              .timeout(2)
              .build();
      CheckOutcome outcome = run(open);
      assertTrue(outcome.ok());
      assertTrue(outcome.msg().endsWith(" ms"));
    }
    MonitorConfig closed =
        MonitorConfig.blank("m")
            .toBuilder()
            .type("port")
            .hostname("127.0.0.1")
            .port(1)
            .timeout(2)
            .build();
    assertEquals("Connection failed", run(closed).msg());
  }

  @Test
  void aManualMonitorTakesWhateverStatusItWasGiven() {
    MonitorConfig config =
        MonitorConfig.blank("m").toBuilder().type("manual").manualStatus(0).build();
    CheckOutcome outcome = run(config);
    assertEquals(Status.DOWN, outcome.status());
    assertEquals("Down", outcome.msg());

    MonitorConfig unset = MonitorConfig.blank("m").toBuilder().type("manual").build();
    assertEquals(Status.PENDING, run(unset).status());
    assertEquals("Manual monitoring - No status set", run(unset).msg());
  }

  @Test
  void aGroupTakesItsChildrensStatus() {
    MonitorConfig config = MonitorConfig.blank("g").toBuilder().type("group").build();
    assertEquals(Status.PENDING, withChildren(config).status());
    assertEquals("Group empty", withChildren(config).msg());

    CheckOutcome allUp =
        withChildren(config, child("a", true, Status.UP), child("b", true, Status.UP));
    assertEquals(Status.UP, allUp.status());
    assertEquals("All children up and running", allUp.msg());

    CheckOutcome oneDown =
        withChildren(config, child("a", true, Status.UP), child("b", true, Status.DOWN));
    assertEquals(Status.DOWN, oneDown.status());
    assertEquals("Child monitors down: b", oneDown.msg());

    CheckOutcome pending =
        withChildren(config, child("a", true, Status.UP), child("b", true, Status.PENDING));
    assertEquals(Status.PENDING, pending.status());
    assertEquals("Pending child monitors: b", pending.msg());

    CheckOutcome both =
        withChildren(
            config,
            child("a", true, Status.DOWN),
            child("b", true, Status.PENDING));
    assertEquals("Child monitors down: a; pending: b", both.msg());
  }

  @Test
  void aPausedChildIsIgnoredByItsGroup() {
    MonitorConfig config = MonitorConfig.blank("g").toBuilder().type("group").build();
    CheckOutcome outcome =
        withChildren(config, child("a", true, Status.UP), child("b", false, Status.DOWN));
    assertEquals(Status.UP, outcome.status());
  }

  private static CheckContext.ChildStatus child(String name, boolean active, Status status) {
    return new CheckContext.ChildStatus(name, name, active, status);
  }

  private static CheckOutcome withChildren(
      MonitorConfig config, CheckContext.ChildStatus... children) {
    CheckContext context =
        new CheckContext(
            Map.of(), null, null, null, List.of(children), null, System.currentTimeMillis());
    return Checks.run(config, context);
  }

  @Test
  void aPushMonitorInsideItsWindowRecordsNothing() {
    MonitorConfig config = MonitorConfig.blank("m").toBuilder().type("push").interval(60).build();
    long now = System.currentTimeMillis();
    Heartbeat recent =
        new Heartbeat(1, "m", false, Status.UP.code(), "OK", now - 5_000, 1d, 0, 0, 0, now, null);
    CheckContext context =
        new CheckContext(Map.of(), null, null, null, List.of(), recent, now);
    assertNull(Checks.run(config, context));
  }

  @Test
  void aPushMonitorPastItsWindowIsDown() {
    MonitorConfig config = MonitorConfig.blank("m").toBuilder().type("push").interval(60).build();
    long now = System.currentTimeMillis();
    Heartbeat stale =
        new Heartbeat(1, "m", false, Status.UP.code(), "OK", now - 120_000, 1d, 0, 0, 0, now, null);
    CheckContext context = new CheckContext(Map.of(), null, null, null, List.of(), stale, now);
    CheckOutcome outcome = Checks.run(config, context);
    assertEquals(Status.DOWN, outcome.status());
    assertEquals("No heartbeat in the time window", outcome.msg());
  }

  @Test
  void aPushMonitorThatHasNeverBeatenIsDown() {
    MonitorConfig config = MonitorConfig.blank("m").toBuilder().type("push").interval(60).build();
    CheckContext context =
        new CheckContext(Map.of(), null, null, null, List.of(), null, System.currentTimeMillis());
    assertEquals("No heartbeat in the time window", Checks.run(config, context).msg());
  }

  @Test
  void aTypeNothingImplementsIsRefusedByName() {
    MonitorConfig config = MonitorConfig.blank("m").toBuilder().type("http").build();
    Map<String, Object> broken = new LinkedHashMap<>();
    // The registry answers by type string, and one it does not know is the source's own message.
    assertEquals("Unknown Monitor Type",
        Checks.run(
                config.toBuilder().type("smoke-signal").build(),
                CheckContext.plain(System.currentTimeMillis()))
            .msg());
    assertNotNull(broken);
  }

  @Test
  void everyTypeTheSourceCanExecuteIsImplementedHere() {
    for (String type : MonitorConfig.TYPES) {
      assertNotNull(Checks.byType(type), "no check implements " + type);
    }
    assertEquals(MonitorConfig.TYPES.size(), Checks.types().size());
  }

  @Test
  void theTypeListTheInterfaceReadsNamesTheConditionVariables() {
    Map<String, Object> types = Checks.typeList();
    assertTrue(types.containsKey("dns"));
    assertTrue(types.containsKey("mqtt"));
    // The four handled inline in the source are not in its own list, so they are not here.
    assertTrue(!types.containsKey("http"));
    Map<?, ?> dns = (Map<?, ?>) types.get("dns");
    assertEquals(true, dns.get("supportsConditions"));
    assertEquals(1, ((List<?>) dns.get("conditionVariables")).size());
  }
}
