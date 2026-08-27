package io.akka.uptimekuma.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

/**
 * The checks that talk a protocol, driven against a server this test speaks it back on.
 *
 * <p>Each of these types is a real exchange rather than a decision over a value handed in, so the
 * only way to hold it to the contract is to be the thing on the other end. The servers here answer
 * exactly what a case needs and nothing more: a container daemon that reports one state, a time
 * server whose stratum is whatever the case is about, a name server holding four records.
 *
 * <p>The types that need somebody else's software rather than somebody else's answer — a database
 * engine, a message broker's own wire protocol, a browser — are not here. The specification's
 * section 7 names them.
 */
class LocalServiceChecksTest {

  private static HttpServer http;
  private static int httpPort;

  @BeforeAll
  static void startHttp() throws IOException {
    http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpPort = http.getAddress().getPort();
    container("web", "{\"State\":{\"Status\":\"running\",\"Health\":{\"Status\":\"healthy\"}}}");
    container("sick", "{\"State\":{\"Status\":\"running\",\"Health\":{\"Status\":\"unhealthy\"}}}");
    container("warming", "{\"State\":{\"Status\":\"running\",\"Health\":{\"Status\":\"starting\"}}}");
    container("bare", "{\"State\":{\"Status\":\"running\"}}");
    container("restarting", "{\"State\":{\"Status\":\"restarting\"}}");
    container("paused", "{\"State\":{\"Status\":\"paused\"}}");
    container("exited", "{\"State\":{\"Status\":\"exited\"}}");
    http.createContext("/api/health/checks/alarms/", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
    http.createContext("/broken/api/health/checks/alarms/", exchange -> respond(exchange, 503, "down"));
    http.start();
  }

  @AfterAll
  static void stopHttp() {
    http.stop(0);
  }

  private static void container(String name, String json) {
    http.createContext("/containers/" + name + "/json", exchange -> respond(exchange, 200, json));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static CheckOutcome run(MonitorConfig config, CheckContext context) {
    return Checks.run(config, context);
  }

  // ---- a container daemon ------------------------------------------------------------------

  private static CheckOutcome docker(String containerName) {
    MonitorConfig config =
        MonitorConfig.blank("m")
            .toBuilder()
            .type("docker")
            .dockerContainer(containerName)
            .dockerHost("h1")
            .interval(20)
            .build();
    CheckContext context =
        new CheckContext(
            Map.of(),
            new CheckContext.DockerHostConfig(
                "h1", "local", "http://127.0.0.1:" + httpPort, "tcp"),
            null,
            null,
            List.of(),
            null,
            System.currentTimeMillis());
    return run(config, context);
  }

  @Test
  void aContainerIsUpOnlyForTheStatesTheSourceAccepts() {
    assertEquals("healthy", docker("web").msg());
    assertEquals(Status.UP, docker("web").status());

    // A health check that says the container is sick outranks the fact that it is running.
    assertEquals(Status.DOWN, docker("sick").status());
    assertEquals(
        "Container State is unhealthy according to its healthcheck", docker("sick").msg());

    // Any other health word is neither: the container has not settled yet.
    assertEquals(Status.PENDING, docker("warming").status());
    assertEquals("starting", docker("warming").msg());

    // Running with no health check at all is up, and the source says why at length.
    assertEquals(Status.UP, docker("bare").status());
    assertTrue(docker("bare").msg().startsWith("Container has not reported health"));

    assertEquals(Status.PENDING, docker("restarting").status());
    assertEquals("Container is reporting it is currently restarting", docker("restarting").msg());

    assertEquals(Status.DOWN, docker("paused").status());
    assertEquals("Container is in a paused state", docker("paused").msg());

    // Anything else names the state rather than guessing at what it means.
    assertEquals("Container State is exited", docker("exited").msg());
  }

  @Test
  void aContainerMonitorWithNoDaemonSaysSoRatherThanFailingToConnect() {
    MonitorConfig config =
        MonitorConfig.blank("m").toBuilder().type("docker").dockerContainer("web").build();
    assertEquals(
        "Docker host not found", run(config, CheckContext.plain(System.currentTimeMillis())).msg());
  }

  // ---- a broker's management interface -----------------------------------------------------

  @Test
  void oneHealthyRabbitNodeIsEnough() {
    String healthy = "http://127.0.0.1:" + httpPort;
    String broken = "http://127.0.0.1:" + httpPort + "/broken";

    MonitorConfig single =
        MonitorConfig.blank("m")
            .toBuilder()
            .type("rabbitmq")
            .rabbitmqNodes(List.of(healthy))
            .interval(20)
            .timeout(5)
            .build();
    assertEquals("Node is reachable and there are no alerts in the cluster", run(single, plain()).msg());

    // With more than one node the wording changes, and the first that answers ends the loop —
    // so a broken node listed ahead of a healthy one still passes.
    MonitorConfig cluster =
        single.toBuilder().rabbitmqNodes(List.of(broken, healthy)).build();
    CheckOutcome outcome = run(cluster, plain());
    assertEquals(Status.UP, outcome.status());
    assertEquals("One of the 2 nodes is reachable and there are no alerts in the cluster", outcome.msg());

    // Every node failing reports how many were tried and what each said.
    MonitorConfig allBroken = single.toBuilder().rabbitmqNodes(List.of(broken)).build();
    CheckOutcome failed = run(allBroken, plain());
    assertEquals(Status.DOWN, failed.status());
    assertTrue(failed.msg().startsWith("All 1 nodes failed because "));
    assertTrue(failed.msg().contains("503"));
  }

  // ---- a time server -----------------------------------------------------------------------

  /**
   * An SNTP responder whose readings the case chooses.
   *
   * <p>Offset is produced by claiming a time that far from the caller's, which is what the
   * protocol's arithmetic reduces to when the round trip is negligible.
   */
  private static final class FakeTimeServer implements AutoCloseable {
    private final DatagramSocket socket;
    private final Thread thread;
    final int port;

    FakeTimeServer(int stratum, long claimedOffsetMillis, double rootDispersionMillis)
        throws IOException {
      socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
      port = socket.getLocalPort();
      thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[48];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                try {
                  while (!socket.isClosed()) {
                    socket.receive(request);
                    byte[] reply = new byte[48];
                    reply[0] = 0x1c;
                    reply[1] = (byte) stratum;
                    long dispersionFixed = (long) (rootDispersionMillis / 1000.0 * 65536.0);
                    reply[8] = (byte) ((dispersionFixed >> 24) & 0xff);
                    reply[9] = (byte) ((dispersionFixed >> 16) & 0xff);
                    reply[10] = (byte) ((dispersionFixed >> 8) & 0xff);
                    reply[11] = (byte) (dispersionFixed & 0xff);
                    reply[12] = 'L';
                    reply[13] = 'O';
                    reply[14] = 'C';
                    reply[15] = 'L';
                    writeTimestamp(reply, 32, System.currentTimeMillis() + claimedOffsetMillis);
                    writeTimestamp(reply, 40, System.currentTimeMillis() + claimedOffsetMillis);
                    socket.send(
                        new DatagramPacket(
                            reply, reply.length, request.getAddress(), request.getPort()));
                  }
                } catch (IOException closed) {
                  // The socket closing is how this thread is asked to stop.
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    private static void writeTimestamp(byte[] packet, int offset, long epochMillis) {
      long seconds = epochMillis / 1000L + 2208988800L;
      long fraction = (long) ((epochMillis % 1000L) / 1000.0 * 0x100000000L);
      for (int i = 0; i < 4; i++) {
        packet[offset + i] = (byte) ((seconds >> (24 - 8 * i)) & 0xff);
        packet[offset + 4 + i] = (byte) ((fraction >> (24 - 8 * i)) & 0xff);
      }
    }

    @Override
    public void close() {
      socket.close();
    }
  }

  private static MonitorConfig ntp(int port, int stratum, int offset, int dispersion) {
    return MonitorConfig.blank("m")
        .toBuilder()
        .type("ntp")
        .hostname("127.0.0.1")
        .port(port)
        .timeout(3)
        .ntpThresholds(stratum, offset, dispersion)
        .build();
  }

  @Test
  void everyNtpThresholdIsCheckedSeparately() throws Exception {
    try (FakeTimeServer clock = new FakeTimeServer(2, 0, 10)) {
      CheckOutcome outcome = run(ntp(clock.port, 4, 500, 500), plain());
      assertEquals(Status.UP, outcome.status());
      assertTrue(outcome.msg().startsWith("Stratum: 2, RefID: 76.79.67.76, Offset: "));
      assertTrue(outcome.msg().contains("Dispersion: "));

      // The stratum test is "meets or exceeds", so a threshold equal to the reading fails.
      assertEquals(
          "Stratum 2 meets or exceeds threshold 2", run(ntp(clock.port, 2, 500, 500), plain()).msg());
      // Dispersion travels as a fixed-point fraction of a second, so the number that comes back
      // is the nearest that encoding can hold rather than the one that went out.
      assertEquals(
          "Root dispersion 9.995ms exceeds threshold 5ms",
          run(ntp(clock.port, 4, 500, 5), plain()).msg());
    }

    // A server that has not synchronised says so through its stratum rather than by failing.
    try (FakeTimeServer unsynchronised = new FakeTimeServer(16, 0, 1)) {
      assertEquals(
          "NTP server is unsynchronized (stratum 16)",
          run(ntp(unsynchronised.port, 20, 500, 500), plain()).msg());
    }

    try (FakeTimeServer adrift = new FakeTimeServer(2, 5000, 1)) {
      String message = run(ntp(adrift.port, 4, 100, 500), plain()).msg();
      assertTrue(message.startsWith("Time offset "), message);
      assertTrue(message.endsWith("ms exceeds threshold 100ms"), message);
    }
  }

  @Test
  void aTimeServerThatDoesNotAnswerIsAnOutageRatherThanAnError() {
    // Port zero is never listening, and the check has to say the request timed out rather than
    // letting the socket error reach the beat.
    CheckOutcome outcome = run(ntp(9, 4, 500, 500).toBuilder().timeout(1).build(), plain());
    assertEquals(Status.DOWN, outcome.status());
    assertNotNull(outcome.msg());
  }

  // ---- a name server -----------------------------------------------------------------------

  /** A resolver that answers one question with records the case supplies. */
  private static final class FakeNameServer implements AutoCloseable {
    private final DatagramSocket socket;
    final int port;

    FakeNameServer(List<org.xbill.DNS.Record> answers) throws IOException {
      socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
      port = socket.getLocalPort();
      Thread thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[512];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                try {
                  while (!socket.isClosed()) {
                    socket.receive(request);
                    Message query =
                        new Message(java.util.Arrays.copyOf(request.getData(), request.getLength()));
                    Message reply = new Message(query.getHeader().getID());
                    reply.getHeader().setFlag(org.xbill.DNS.Flags.QR);
                    reply.addRecord(query.getQuestion(), Section.QUESTION);
                    for (org.xbill.DNS.Record answer : answers) {
                      reply.addRecord(answer, Section.ANSWER);
                    }
                    byte[] bytes = reply.toWire();
                    socket.send(
                        new DatagramPacket(
                            bytes, bytes.length, request.getAddress(), request.getPort()));
                  }
                } catch (IOException closed) {
                  // Closing the socket is how this thread is stopped.
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    @Override
    public void close() {
      socket.close();
    }
  }

  private static MonitorConfig dns(int port, String type) {
    return MonitorConfig.blank("m")
        .toBuilder()
        .type("dns")
        .hostname("example.test.")
        .port(port)
        .dnsResolveServer("127.0.0.1")
        .dnsResolveType(type)
        .interval(20)
        .timeout(3)
        .build();
  }

  @Test
  void aDnsAnswerIsRenderedPerRecordType() throws Exception {
    Name name = Name.fromString("example.test.");

    try (FakeNameServer server =
        new FakeNameServer(
            List.of(
                new ARecord(name, DClass.IN, 60, InetAddress.getByName("10.0.0.1")),
                new ARecord(name, DClass.IN, 60, InetAddress.getByName("10.0.0.2"))))) {
      // Addresses are listed under one word and separated by a pipe. The resolver is free to
      // rotate equal answers between lookups, so the pair is what is asserted rather than an
      // order nothing promises.
      String message = run(dns(server.port, "A"), plain()).msg();
      assertTrue(message.startsWith("Records: "), message);
      assertEquals(
          java.util.Set.of("10.0.0.1", "10.0.0.2"),
          java.util.Set.of(message.substring("Records: ".length()).split(" \\| ")));
    }

    try (FakeNameServer server =
        new FakeNameServer(List.of(new TXTRecord(name, DClass.IN, 60, "v=spf1 -all")))) {
      // The quotes a text record arrives in are presentation, not part of the value.
      assertEquals("Records: v=spf1 -all", run(dns(server.port, "TXT"), plain()).msg());
    }

    try (FakeNameServer server =
        new FakeNameServer(
            List.of(new MXRecord(name, DClass.IN, 60, 10, Name.fromString("mail.example.test."))))) {
      assertEquals(
          "Hostname: mail.example.test. - Priority: 10", run(dns(server.port, "MX"), plain()).msg());
    }

    try (FakeNameServer server =
        new FakeNameServer(
            List.of(
                new org.xbill.DNS.NSRecord(
                    name, DClass.IN, 60, Name.fromString("ns1.example.test."))))) {
      // Name servers are listed under a different word from addresses, and lose the trailing dot.
      assertEquals("Servers: ns1.example.test", run(dns(server.port, "NS"), plain()).msg());
    }
  }

  @Test
  void aDnsConditionPassesIfAnySingleRecordSatisfiesIt() throws Exception {
    Name name = Name.fromString("example.test.");
    try (FakeNameServer server =
        new FakeNameServer(
            List.of(
                new ARecord(name, DClass.IN, 60, InetAddress.getByName("10.0.0.1")),
                new ARecord(name, DClass.IN, 60, InetAddress.getByName("10.0.0.2"))))) {

      MonitorConfig wants = dns(server.port, "A").toBuilder().conditions(condition("10.0.0.2")).build();
      assertEquals(Status.UP, run(wants, plain()).status());

      MonitorConfig wantsSomethingElse =
          dns(server.port, "A").toBuilder().conditions(condition("10.0.0.9")).build();
      CheckOutcome outcome = run(wantsSomethingElse, plain());
      assertEquals(Status.DOWN, outcome.status());
      // A failure still reports what was found rather than what was wanted.
      assertTrue(outcome.msg().contains("10.0.0.1"), outcome.msg());
      assertTrue(outcome.msg().contains("10.0.0.2"), outcome.msg());
    }
  }

  private static List<Map<String, Object>> condition(String value) {
    Map<String, Object> expression = new LinkedHashMap<>();
    expression.put("type", "expression");
    expression.put("andOr", "and");
    expression.put("variable", "record");
    expression.put("operator", "equals");
    expression.put("value", value);
    return List.of(expression);
  }

  // ---- a mail server -----------------------------------------------------------------------

  /** A server that greets, lists nothing, and says goodbye. */
  private static final class FakeMailServer implements AutoCloseable {
    private final ServerSocket socket;
    final int port;

    FakeMailServer(String greeting) throws IOException {
      socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      port = socket.getLocalPort();
      Thread thread =
          new Thread(
              () -> {
                while (!socket.isClosed()) {
                  try (Socket client = socket.accept()) {
                    BufferedReader in =
                        new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = client.getOutputStream();
                    out.write((greeting + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    String line;
                    while ((line = in.readLine()) != null) {
                      String reply =
                          line.toUpperCase(java.util.Locale.ROOT).startsWith("QUIT")
                              ? "221 Bye"
                              : "250 OK";
                      out.write((reply + "\r\n").getBytes(StandardCharsets.UTF_8));
                      out.flush();
                      if (reply.startsWith("221")) {
                        break;
                      }
                    }
                  } catch (IOException closed) {
                    return;
                  }
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private static MonitorConfig smtp(int port) {
    return MonitorConfig.blank("m")
        .toBuilder()
        .type("smtp")
        .hostname("127.0.0.1")
        .port(port)
        .interval(20)
        .timeout(3)
        .smtpSecurity("nostarttls")
        .build();
  }

  @Test
  void anSmtpServerThatGreetsBackIsUp() throws Exception {
    try (FakeMailServer server = new FakeMailServer("220 fake ESMTP")) {
      CheckOutcome outcome = run(smtp(server.port), plain());
      assertEquals(Status.UP, outcome.status());
      assertEquals("SMTP connection verifies successfully", outcome.msg());
      assertNotNull(outcome.ping());
    }
  }

  @Test
  void anSmtpServerThatRefusesItsOwnGreetingIsDown() throws Exception {
    // A server answering anything but 220 has not opened a session, and the check says so in the
    // source's words with the reason attached.
    try (FakeMailServer server = new FakeMailServer("554 no service here")) {
      CheckOutcome outcome = run(smtp(server.port), plain());
      assertEquals(Status.DOWN, outcome.status());
      assertTrue(outcome.msg().startsWith("SMTP connection doesn't verify: "), outcome.msg());
    }
  }

  // ---- the host's own network stack --------------------------------------------------------

  @Test
  void aPingRecordsItsRoundTrip() {
    MonitorConfig config =
        MonitorConfig.blank("m")
            .toBuilder()
            .type("ping")
            .hostname("127.0.0.1")
            .pingCount(1)
            .packetSize(56)
            .pingPerRequestTimeout(2)
            .interval(20)
            .build();
    CheckOutcome outcome = run(config, plain());
    assertEquals(Status.UP, outcome.status());
    // The loopback address always answers, so this is the one ping that can be asserted on.
    assertNotNull(outcome.ping());
    assertTrue(outcome.ping() >= 0);
    // A successful ping carries no message of its own.
    assertEquals("", outcome.msg());
  }

  private static CheckContext plain() {
    return CheckContext.plain(System.currentTimeMillis());
  }

  // ---- somebody else's network -------------------------------------------------------------

  @Test
  void aGlobalpingCheckReportsWhichProbeAnswered() {
    Map<String, Object> probe = new LinkedHashMap<>();
    probe.put("continent", "EU");
    probe.put("country", "DE");
    probe.put("city", "Frankfurt");
    probe.put("network", "Example AS");
    probe.put("asn", 64500);

    // The location is what a reader identifies the measurement by, so it is spelled out in full
    // rather than left as a probe id.
    String location = ExternalChecks.GlobalpingCheck.formatProbeLocation(probe);
    assertTrue(location.contains("Frankfurt"), location);
    assertTrue(location.contains("DE"), location);
    assertTrue(location.contains("Example AS"), location);

    assertEquals(location + " : 200 OK", ExternalChecks.GlobalpingCheck.formatResponse(probe, "200 OK"));

    // A refusal from the measurement service is reported as itself rather than as a monitor
    // outage with no explanation.
    assertNotNull(ExternalChecks.GlobalpingCheck.formatApiError("{\"error\":{\"message\":\"bad\"}}"));
    assertTrue(ExternalChecks.GlobalpingCheck.tooManyRequests(false).contains("globalping"));
    assertTrue(ExternalChecks.GlobalpingCheck.tooManyRequests(true).contains("globalping"));
  }

  // ---- a query that has to answer with one value -------------------------------------------

  @Test
  void aQueryMonitorRefusesAnAnswerThatIsNotOneCell() throws Exception {
    // Which value was meant is not knowable from more than one, so the source refuses rather
    // than picking. Driven against a result set standing in for a database, because the rule is
    // about the shape of the answer rather than about any one engine.
    assertEquals("green", DatabaseChecks.SingleValueQuery.singleValue(Fakes.rows(1, "green")));

    assertEquals(
        "Query returned no results",
        assertThrows(CheckFailed.class, () -> DatabaseChecks.SingleValueQuery.singleValue(Fakes.rows(1)))
            .getMessage());

    assertEquals(
        "Multiple columns were found, expected only one value",
        assertThrows(
                CheckFailed.class,
                () -> DatabaseChecks.SingleValueQuery.singleValue(Fakes.rows(2, "green")))
            .getMessage());

    assertEquals(
        "Multiple values were found, expected only one value",
        assertThrows(
                CheckFailed.class,
                () -> DatabaseChecks.SingleValueQuery.singleValue(Fakes.rows(1, "green", "amber")))
            .getMessage());
  }
}
