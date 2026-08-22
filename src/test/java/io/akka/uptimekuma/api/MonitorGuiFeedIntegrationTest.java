package io.akka.uptimekuma.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.uptimekuma.application.HeartbeatFeedView;
import io.akka.uptimekuma.application.MonitorSummaryView;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The surface a GUI client actually subscribes to — RENDERING.md R1, R7. Where {@link
 * MonitorEndpointIntegrationTest} drives the monitor's own lifecycle over HTTP, this drives what a
 * dashboard reads: the initial snapshot, the gap a reconnect fills, and the two live streams.
 */
public class MonitorGuiFeedIntegrationTest extends TestKitSupport {

  private static final class Target implements AutoCloseable {
    private final HttpServer server;

    Target() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  void aCreatedMonitorAppearsInTheListEndpointWithoutPolling() {
    var id = "list-" + UUID.randomUUID();
    httpClient
        .PUT("/monitors/" + id)
        .withRequestBody(new MonitorConfig("http://example.invalid/", 30, 30, 0, 0, List.of()))
        .invoke();

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var list =
                  httpClient
                      .GET("/monitors/")
                      .responseBodyAs(MonitorSummaryView.SummaryList.class)
                      .invoke()
                      .body();
              assertThat(list.monitors()).anyMatch(m -> m.id().equals(id));
            });
  }

  @Test
  void heartbeatsSinceFillsTheGapAReconnectingClientMissed() throws IOException {
    try (var target = new Target()) {
      var id = "replay-" + UUID.randomUUID();
      httpClient
          .PUT("/monitors/" + id)
          .withRequestBody(new MonitorConfig(target.url(), 1, 1, 0, 0, List.of()))
          .invoke();
      httpClient.POST("/monitors/" + id + "/start").invoke();

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(replay(id, 0).items()).hasSizeGreaterThanOrEqualTo(2));

      var all = replay(id, 0).items();
      var fromMiddle = replay(id, all.get(0).sequence());
      assertThat(fromMiddle.items()).hasSize(all.size() - 1);
      assertThat(fromMiddle.items().get(0).sequence()).isEqualTo(all.get(1).sequence());

      httpClient.POST("/monitors/" + id + "/stop").invoke();
    }
  }

  private HeartbeatFeedView.Announcements replay(String id, long since) {
    return httpClient
        .GET("/monitors/" + id + "/heartbeats?since=" + since)
        .responseBodyAs(HeartbeatFeedView.Announcements.class)
        .invoke()
        .body();
  }

  @Test
  void aDownBeatReachesTheFeedTheSameWayAnUpBeatDoes() {
    // A beat that never completed its check has no ping time, and a dashboard's whole
    // reason to exist is showing exactly those. The feed carries them or it carries the
    // half of the history nobody needed.
    var id = "down-" + UUID.randomUUID();
    httpClient
        .PUT("/monitors/" + id)
        .withRequestBody(
            new MonitorConfig("http://127.0.0.1:1/", 1, 1, 0, 0, List.of()))
        .invoke();
    httpClient.POST("/monitors/" + id + "/start").invoke();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var items = replay(id, 0).items();
              assertThat(items).hasSizeGreaterThanOrEqualTo(2);
              assertThat(items).allMatch(a -> a.status() == Status.DOWN);
              assertThat(items).allMatch(a -> a.pingMillis().isEmpty());
            });

    httpClient.POST("/monitors/" + id + "/stop").invoke();
  }

  @Test
  void aPerMonitorStreamPushesBeatsAsTheyHappen() throws IOException {
    try (var target = new Target()) {
      var id = "stream-" + UUID.randomUUID();
      httpClient
          .PUT("/monitors/" + id)
          .withRequestBody(new MonitorConfig(target.url(), 1, 1, 0, 0, List.of()))
          .invoke();

      try (var session = new SseSession(testKit.getPort(), "/monitors/" + id + "/heartbeats/stream")) {
        httpClient.POST("/monitors/" + id + "/start").invoke();
        var frames = session.awaitFrames(2, Duration.ofSeconds(20));
        assertThat(frames).hasSizeGreaterThanOrEqualTo(2);
        assertThat(frames.get(0).data()).contains("\"status\":\"UP\"");
      }

      httpClient.POST("/monitors/" + id + "/stop").invoke();
    }
  }

  @Test
  void reconnectingWithLastEventIdSkipsNothingAndRepeatsNothing() throws IOException {
    // R1.3: a client that reconnects converges on current state with nothing duplicated
    // and nothing missing.
    try (var target = new Target()) {
      var id = "reconnect-" + UUID.randomUUID();
      httpClient
          .PUT("/monitors/" + id)
          .withRequestBody(new MonitorConfig(target.url(), 1, 1, 0, 0, List.of()))
          .invoke();
      httpClient.POST("/monitors/" + id + "/start").invoke();

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(replay(id, 0).items()).hasSizeGreaterThanOrEqualTo(3));
      httpClient.POST("/monitors/" + id + "/stop").invoke();

      var all = replay(id, 0).items();
      var resumeFrom = all.get(0).sequence();

      try (var session =
          new SseSession(
              testKit.getPort(),
              "/monitors/" + id + "/heartbeats/stream",
              Long.toString(resumeFrom))) {
        var frames = session.awaitFrames(all.size() - 1, Duration.ofSeconds(10));
        assertThat(frames).extracting(SseSession.Frame::id)
            .containsExactlyElementsOf(
                all.stream().skip(1).map(a -> Long.toString(a.sequence())).toList());
      }
    }
  }

  @Test
  void theDashboardStreamCarriesBeatsFromEveryMonitor() throws IOException {
    try (var a = new Target();
        var b = new Target()) {
      var idA = "dash-a-" + UUID.randomUUID();
      var idB = "dash-b-" + UUID.randomUUID();
      httpClient
          .PUT("/monitors/" + idA)
          .withRequestBody(new MonitorConfig(a.url(), 1, 1, 0, 0, List.of()))
          .invoke();
      httpClient
          .PUT("/monitors/" + idB)
          .withRequestBody(new MonitorConfig(b.url(), 1, 1, 0, 0, List.of()))
          .invoke();

      try (var session = new SseSession(testKit.getPort(), "/monitors/stream")) {
        httpClient.POST("/monitors/" + idA + "/start").invoke();
        httpClient.POST("/monitors/" + idB + "/start").invoke();

        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(
                () -> {
                  var frames = session.awaitFrames(1, Duration.ofMillis(200));
                  var monitorIds = frames.stream().map(SseSession.Frame::data).toList();
                  assertThat(monitorIds.stream().anyMatch(d -> d.contains(idA))).isTrue();
                  assertThat(monitorIds.stream().anyMatch(d -> d.contains(idB))).isTrue();
                });
      }

      httpClient.POST("/monitors/" + idA + "/stop").invoke();
      httpClient.POST("/monitors/" + idB + "/stop").invoke();
    }
  }
}
