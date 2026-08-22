package io.akka.uptimekuma.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.NotificationTarget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The port's capability driven the way something outside a test would drive it — over HTTP, through
 * the endpoint, with nothing reaching in past it.
 *
 * <p>Its sibling tests use the component client, which is a shorter path and says nothing about
 * whether the surface exists or works. A port whose logic is only ever reached through the
 * component client has no reachable capability at all, which is a gap of the same kind as an
 * unanswered rendering manifest.
 */
public class MonitorEndpointIntegrationTest extends TestKitSupport {

  /** A target the monitor can be pointed at, answering whatever it is last told to. */
  private static final class Target implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger();

    Target() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(status.get(), -1);
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

  private MonitorEndpoint.MonitorView monitor(String id) {
    return httpClient
        .GET("/monitors/" + id)
        .responseBodyAs(MonitorEndpoint.MonitorView.class)
        .invoke()
        .body();
  }

  @Test
  void aMonitorIsCreatedStartedAndReadBackOverHttp() throws IOException {
    try (var target = new Target()) {
      var id = "http-" + UUID.randomUUID();
      var created =
          httpClient
              .PUT("/monitors/" + id)
              .withRequestBody(new MonitorConfig(target.url(), 1, 1, 0, 0, List.of()))
              .invoke();
      assertThat(created.status().intValue()).isEqualTo(201);

      var started = httpClient.POST("/monitors/" + id + "/start").invoke();
      assertThat(started.status().intValue()).isEqualTo(200);

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(monitor(id).heartbeats().size()).isGreaterThanOrEqualTo(2));

      var view = monitor(id);
      assertThat(view.active()).isTrue();
      assertThat(view.config().url()).isEqualTo(target.url());
      assertThat(view.heartbeats()).allMatch(h -> h.status().name().equals("UP"));

      assertThat(httpClient.POST("/monitors/" + id + "/stop").invoke().status().intValue())
          .isEqualTo(200);
      assertThat(monitor(id).active()).isFalse();
    }
  }

  @Test
  void maintenanceIsTurnedOnAndOffOverHttpAndSuppressesTheCheck() throws IOException {
    try (var target = new Target()) {
      var id = "maint-http-" + UUID.randomUUID();
      httpClient
          .PUT("/monitors/" + id)
          .withRequestBody(new MonitorConfig(target.url(), 30, 30, 0, 0, List.of()))
          .invoke();
      httpClient.POST("/monitors/" + id + "/maintenance/true").invoke();
      assertThat(monitor(id).underMaintenance()).isTrue();

      httpClient.POST("/monitors/" + id + "/start").invoke();
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(monitor(id).heartbeats()).hasSize(1));
      assertThat(monitor(id).heartbeats().get(0).status().name()).isEqualTo("MAINTENANCE");
      assertThat(target.hits.get()).isZero();

      httpClient.POST("/monitors/" + id + "/stop").invoke();
      httpClient.POST("/monitors/" + id + "/maintenance/false").invoke();
      assertThat(monitor(id).underMaintenance()).isFalse();
    }
  }

  @Test
  void startingAMonitorTwiceDoesNotPushItsNextBeatOut() throws IOException {
    try (var target = new Target()) {
      var id = "twice-" + UUID.randomUUID();
      httpClient
          .PUT("/monitors/" + id)
          .withRequestBody(new MonitorConfig(target.url(), 2, 2, 0, 0, List.of()))
          .invoke();
      httpClient.POST("/monitors/" + id + "/start").invoke();

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(monitor(id).heartbeats()).hasSizeGreaterThanOrEqualTo(1));

      // A second start must not re-arm: arming replaces, so an endpoint that armed
      // unconditionally would restart the two-second wait on every call.
      for (int i = 0; i < 5; i++) {
        httpClient.POST("/monitors/" + id + "/start").invoke();
      }
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(monitor(id).heartbeats()).hasSizeGreaterThanOrEqualTo(3));

      httpClient.POST("/monitors/" + id + "/stop").invoke();
    }
  }

  @Test
  void aMonitorWithANonPositiveIntervalIsRefusedRatherThanGivenOne() {
    var id = "bad-" + UUID.randomUUID();
    var response =
        httpClient
            .PUT("/monitors/" + id)
            .withRequestBody(new BadConfig("http://example.invalid/", 0, 30, 0, 0, List.of()))
            .invoke();
    assertThat(response.status().intValue()).isGreaterThanOrEqualTo(400);
    // Refused rather than half-made: the monitor does not exist afterwards, and asking for
    // one that does not exist is a 404 rather than a monitor with every field empty.
    assertThat(
            httpClient.GET("/monitors/" + id).invoke().httpResponse().status().intValue())
        .isEqualTo(404);
  }

  /** The same shape as {@link MonitorConfig} without its invariant, to get past the client. */
  public record BadConfig(
      String url,
      int intervalSeconds,
      int retryIntervalSeconds,
      int maxRetries,
      int resendIntervalSeconds,
      List<String> notificationIds) {}

  @Test
  void aNotificationTargetIsRegisteredOverHttpAndReceivesTheOutage() throws IOException {
    try (var target = new Target();
        var webhook = new Target()) {
      var run = UUID.randomUUID().toString();
      var created =
          httpClient
              .PUT("/monitors/notifications/hook-" + run)
              .withRequestBody(new NotificationTarget("hook", webhook.url()))
              .invoke();
      assertThat(created.status().intValue()).isEqualTo(201);

      target.status.set(500);
      var id = "notified-" + run;
      httpClient
          .PUT("/monitors/" + id)
          .withRequestBody(new MonitorConfig(target.url(), 30, 30, 0, 0, List.of("hook-" + run)))
          .invoke();
      httpClient.POST("/monitors/" + id + "/start").invoke();

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(webhook.hits.get()).isEqualTo(1));
      assertThat(monitor(id).heartbeats().get(0).status().name()).isEqualTo("DOWN");

      httpClient.POST("/monitors/" + id + "/stop").invoke();
    }
  }
}
