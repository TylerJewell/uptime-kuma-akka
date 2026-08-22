package io.akka.uptimekuma.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 R1–R5 and R19, over a running runtime.
 *
 * <p>These start real timers against a real HTTP target, so the intervals are seconds rather than
 * minutes. The rules being checked are about which delay is chosen and whether arming replaces —
 * neither of which depends on the size of the number.
 */
public class MonitorSchedulingIntegrationTest extends TestKitSupport {

  private List<Heartbeat> history(String id) {
    return componentClient
        .forEventSourcedEntity(id)
        .method(MonitorEntity::get)
        .invoke()
        .history();
  }

  private void createAndStart(String id, MonitorConfig config) {
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::create).invoke(config);
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::start).invoke();
    testKit
        .getTimerScheduler()
        .createSingleTimer(
            MonitorBeat.timerName(id),
            Duration.ofMillis(1),
            componentClient
                .forTimedAction()
                .method(MonitorBeat::beat)
                .deferred(new MonitorBeat.Beat(id)));
  }

  @Test
  void aRunningMonitorKeepsBeatingAndAStoppedOneDoesNot() throws IOException {
    try (var target = new FakeEndpointServer()) {
      var id = "sched-" + UUID.randomUUID();
      createAndStart(id, new MonitorConfig(target.url(), 1, 1, 0, 0, List.of()));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id).size()).isGreaterThanOrEqualTo(3));
      assertThat(history(id)).allMatch(h -> h.status().name().equals("UP"));

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));

      int afterStop = history(id).size();
      sleep(3000);
      assertThat(history(id)).hasSizeLessThanOrEqualTo(afterStop + 1);
    }
  }

  @Test
  void aMonitorInsideItsRetryWindowBeatsOnTheRetryInterval() throws IOException {
    try (var target = new FakeEndpointServer()) {
      target.answerWith(500);
      var id = "retry-" + UUID.randomUUID();
      // Ordinary cadence 30s, retry cadence 1s, three retries allowed: if the retry
      // interval were not being used, only one beat would land inside the window below.
      createAndStart(id, new MonitorConfig(target.url(), 30, 1, 3, 0, List.of()));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id).size()).isGreaterThanOrEqualTo(3));

      var statuses = history(id).stream().map(h -> h.status().name()).toList();
      assertThat(statuses.subList(0, 3)).containsExactly("PENDING", "PENDING", "PENDING");

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  @Test
  void aMonitorThatHasGoneDownReturnsToTheOrdinaryInterval() throws IOException {
    try (var target = new FakeEndpointServer()) {
      target.answerWith(500);
      var id = "down-" + UUID.randomUUID();
      // One retry at 1s, then DOWN — and a 30s ordinary cadence the DOWN beat must pick up.
      createAndStart(id, new MonitorConfig(target.url(), 30, 1, 1, 0, List.of()));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id).size()).isGreaterThanOrEqualTo(2));
      assertThat(history(id).stream().map(h -> h.status().name()).toList())
          .containsExactly("PENDING", "DOWN");

      // Nothing further inside the retry cadence, because DOWN is not PENDING.
      sleep(5000);
      assertThat(history(id)).hasSize(2);

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  @Test
  void armingTheSameMonitorTwiceLeavesOneBeatPending() throws IOException {
    try (var target = new FakeEndpointServer()) {
      var id = "arm-" + UUID.randomUUID();
      var config = new MonitorConfig(target.url(), 30, 30, 0, 0, List.of());
      componentClient.forEventSourcedEntity(id).method(MonitorEntity::create).invoke(config);
      componentClient.forEventSourcedEntity(id).method(MonitorEntity::start).invoke();

      var call =
          componentClient
              .forTimedAction()
              .method(MonitorBeat::beat)
              .deferred(new MonitorBeat.Beat(id));
      for (int i = 0; i < 3; i++) {
        testKit
            .getTimerScheduler()
            .createSingleTimer(MonitorBeat.timerName(id), Duration.ofSeconds(2), call);
      }

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id)).hasSize(1));
      sleep(4000);
      assertThat(history(id)).hasSize(1);

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  @Test
  void stoppingAMonitorThatWasNeverArmedIsSilent() {
    var id = "never-" + UUID.randomUUID();
    componentClient
        .forEventSourcedEntity(id)
        .method(MonitorEntity::create)
        .invoke(new MonitorConfig("http://unused.invalid/", 30, 30, 0, 0, List.of()));

    assertThat(componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke())
        .isEqualTo("already stopped");
    testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
  }

  @Test
  void aBeatWhoseTargetRefusesConnectionsIsAnAnswerRatherThanAFault() throws IOException {
    // A check that cannot connect must produce a DOWN beat, not an exception out of the
    // beat — the runtime would then retry the beat on a backoff of its own and the
    // monitor's cadence would stop being the monitor's. SPEC-001 §4 OD1.
    var id = "refused-" + UUID.randomUUID();
    int closedPort;
    try (var briefly = new FakeEndpointServer()) {
      closedPort = Integer.parseInt(briefly.url().split(":")[2].replace("/", ""));
    }
    createAndStart(
        id, new MonitorConfig("http://127.0.0.1:" + closedPort + "/", 30, 30, 0, 0, List.of()));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(history(id)).hasSize(1));
    assertThat(history(id).get(0).status().name()).isEqualTo("DOWN");

    componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
    testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
  }

  @Test
  void aNotificationTargetThatDoesNotExistDoesNotStopTheBeat() throws IOException {
    try (var target = new FakeEndpointServer()) {
      target.answerWith(500);
      var id = "missing-" + UUID.randomUUID();
      createAndStart(
          id, new MonitorConfig(target.url(), 2, 2, 0, 0, List.of("no-such-notification")));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id).size()).isGreaterThanOrEqualTo(2));
      assertThat(history(id).get(0).notified()).isTrue();

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  @Test
  void aMonitorUnderMaintenanceIsNotChecked() throws IOException {
    try (var target = new FakeEndpointServer()) {
      target.answerWith(500);
      var id = "maint-" + UUID.randomUUID();
      componentClient
          .forEventSourcedEntity(id)
          .method(MonitorEntity::create)
          .invoke(new MonitorConfig(target.url(), 30, 30, 0, 0, List.of()));
      componentClient.forEventSourcedEntity(id).method(MonitorEntity::setMaintenance).invoke(true);
      componentClient.forEventSourcedEntity(id).method(MonitorEntity::start).invoke();
      testKit
          .getTimerScheduler()
          .createSingleTimer(
              MonitorBeat.timerName(id),
              Duration.ofMillis(1),
              componentClient
                  .forTimedAction()
                  .method(MonitorBeat::beat)
                  .deferred(new MonitorBeat.Beat(id)));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id)).hasSize(1));
      assertThat(history(id).get(0).status().name()).isEqualTo("MAINTENANCE");
      // The target answers 500; a monitor that was checked anyway would read DOWN.
      assertThat(target.received()).isEmpty();

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
