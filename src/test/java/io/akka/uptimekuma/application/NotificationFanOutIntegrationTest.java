package io.akka.uptimekuma.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.NotificationTarget;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 R17–R18.
 *
 * <p>Three real notification targets, one of which refuses. What is being asserted is not that the
 * notification arrived — one delivery would show that — but that the two after the refusing one
 * still arrived, and that the beat carried on. A monitoring system whose alerting stops because one
 * webhook is down has failed at the thing it exists for, and nothing but a sequence of targets with
 * a failure in the middle of it distinguishes the two implementations.
 */
public class NotificationFanOutIntegrationTest extends TestKitSupport {

  private List<Heartbeat> history(String id) {
    return componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke().history();
  }

  private void putNotification(String id, String name, String url) {
    componentClient
        .forKeyValueEntity(id)
        .method(NotificationEntity::put)
        .invoke(new NotificationTarget(name, url));
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
  void everyNotificationGetsEveryNotifyingBeatAndARefusalCostsOnlyItsOwnDelivery()
      throws IOException {
    try (var target = new FakeEndpointServer();
        var n1 = new FakeEndpointServer();
        var n2 = new FakeEndpointServer();
        var n3 = new FakeEndpointServer()) {

      var run = UUID.randomUUID().toString();
      putNotification("n1-" + run, "n1", n1.url());
      putNotification("n2-" + run, "n2", n2.url());
      putNotification("n3-" + run, "n3", n3.url());
      n2.answerWith(503);

      target.answerWith(500);
      var id = "fanout-" + run;
      createAndStart(
          id,
          new MonitorConfig(
              target.url(), 30, 30, 0, 0, List.of("n1-" + run, "n2-" + run, "n3-" + run)));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id)).hasSize(1));

      var beat = history(id).get(0);
      assertThat(beat.status().name()).isEqualTo("DOWN");
      assertThat(beat.notified()).isTrue();

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                assertThat(n1.received()).hasSize(1);
                assertThat(n2.received()).hasSize(1);
                assertThat(n3.received()).hasSize(1);
              });

      // The one after the refusal received it, which is the whole point.
      assertThat(n3.bodies().get(0)).contains("\"status\":\"DOWN\"").contains(id);
      assertThat(n1.bodies().get(0)).contains("🔴 Down");

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  @Test
  void anOutageAndItsRecoveryAreTwoDeliveriesAndTheQuietBeatsBetweenThemAreNone()
      throws IOException {
    try (var target = new FakeEndpointServer();
        var n1 = new FakeEndpointServer()) {

      var run = UUID.randomUUID().toString();
      putNotification("only-" + run, "only", n1.url());

      var id = "recovery-" + run;
      // Two-second cadence, no retries, no re-sending: the sequence is UP, DOWN..., UP and
      // exactly two of those beats are deliveries.
      createAndStart(id, new MonitorConfig(target.url(), 2, 2, 0, 0, List.of("only-" + run)));

      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(history(id)).hasSizeGreaterThanOrEqualTo(1));
      target.answerWith(500);
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(n1.received()).hasSize(1));
      // Stay down for a few more beats; none of them may deliver.
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(history(id).stream().filter(h -> h.status().name().equals("DOWN")))
                      .hasSizeGreaterThanOrEqualTo(3));
      assertThat(n1.received()).hasSize(1);

      target.answerWith(200);
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(n1.received()).hasSize(2));
      assertThat(n1.bodies().get(1)).contains("✅ Up");

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));
    }
  }

  @Test
  void aMonitorThatStaysDownReNotifiesEveryNthBeatWhenReSendingIsOn() throws IOException {
    try (var target = new FakeEndpointServer();
        var n1 = new FakeEndpointServer()) {

      var run = UUID.randomUUID().toString();
      putNotification("resend-" + run, "resend", n1.url());
      target.answerWith(500);

      var id = "resend-monitor-" + run;
      // One-second cadence, re-send every second unimportant DOWN beat: deliveries land on
      // beats 1, 3, 5 rather than on every beat or only the first.
      createAndStart(id, new MonitorConfig(target.url(), 1, 1, 0, 2, List.of("resend-" + run)));

      Awaitility.await()
          .atMost(Duration.ofSeconds(25))
          .untilAsserted(() -> assertThat(history(id)).hasSizeGreaterThanOrEqualTo(5));

      componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
      testKit.getTimerScheduler().delete(MonitorBeat.timerName(id));

      var notified = history(id).stream().limit(5).map(Heartbeat::notified).toList();
      assertThat(notified).containsExactly(true, false, true, false, true);
      assertThat(history(id).stream().limit(5).map(Heartbeat::downCount).toList())
          .containsExactly(0, 1, 0, 1, 0);
    }
  }
}
