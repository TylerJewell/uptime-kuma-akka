package io.akka.uptimekuma.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 R7–R9, R14–R16 over durable state, and §4 OD2.
 *
 * <p>The unit tests over {@code BeatDecision} thread each beat into the next by hand. These drive
 * the same sequences through the entity, so that the thing being asserted is what a monitor
 * actually resumes from rather than what a local variable held.
 */
class MonitorEntityTest {

  private static final CheckOutcome FAILED = CheckOutcome.failed("connect refused");
  private static final CheckOutcome OK = CheckOutcome.up("200 - OK", 12);

  private static MonitorConfig config(int maxRetries, int resendIntervalSeconds) {
    return new MonitorConfig(
        "http://target.invalid/", 60, 20, maxRetries, resendIntervalSeconds, List.of("n1"));
  }

  private static EventSourcedTestKit<MonitorEntity.State, MonitorEntity.Event, MonitorEntity>
      started(MonitorConfig config) {
    var testKit = EventSourcedTestKit.of("monitor-under-test", MonitorEntity::new);
    testKit.method(MonitorEntity::create).invoke(config);
    testKit.method(MonitorEntity::start).invoke();
    return testKit;
  }

  private static MonitorEntity.BeatResult beat(
      EventSourcedTestKit<MonitorEntity.State, MonitorEntity.Event, MonitorEntity> testKit,
      CheckOutcome outcome) {
    long sequence = testKit.getState().nextSequence();
    return testKit
        .method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(outcome, sequence, sequence * 1000))
        .getReply();
  }

  @Test
  void anOutageAndItsRecoveryAreRecordedInOrder() {
    var testKit = started(config(2, 0));
    for (CheckOutcome outcome : List.of(OK, FAILED, FAILED, FAILED, OK)) {
      beat(testKit, outcome);
    }

    assertThat(testKit.getState().history().stream().map(h -> h.status().name()))
        .containsExactly("UP", "PENDING", "PENDING", "DOWN", "UP");
    assertThat(testKit.getState().history().stream().map(h -> h.notified()))
        .containsExactly(false, false, false, true, true);
  }

  @Test
  void resendCounterSurvivesInTheEntityAcrossAWholeOutage() {
    var testKit = started(config(0, 3));
    for (int i = 0; i < 9; i++) {
      beat(testKit, FAILED);
    }

    assertThat(testKit.getState().history().stream().map(h -> h.downCount()))
        .containsExactly(0, 1, 2, 0, 1, 2, 0, 1, 2);
  }

  @Test
  void theRetryCounterIsWhatARestartMidOutageResumesFrom() {
    var testKit = started(config(3, 0));
    beat(testKit, FAILED);
    beat(testKit, FAILED);
    beat(testKit, FAILED);

    // What a restart sees: the state, rebuilt from the journal, still carrying retries = 3.
    var recovered = testKit.getState();
    assertThat(recovered.last().retries()).isEqualTo(3);
    assertThat(recovered.last().status().name()).isEqualTo("PENDING");

    var next = beat(testKit, FAILED);
    assertThat(next.heartbeat().status().name()).isEqualTo("DOWN");
    assertThat(next.heartbeat().retries()).isEqualTo(4);
  }

  @Test
  void aBeatDeliveredTwiceIsRecordedOnce() {
    var testKit = started(config(0, 3));
    beat(testKit, FAILED);
    long alreadyWritten = testKit.getState().last().sequence();

    var again =
        testKit
            .method(MonitorEntity::recordBeat)
            .invoke(new MonitorEntity.RecordBeat(FAILED, alreadyWritten, 9999))
            .getReply();

    assertThat(again.duplicate()).isTrue();
    assertThat(again.send()).isFalse();
    assertThat(again.heartbeat().sequence()).isEqualTo(alreadyWritten);
    assertThat(testKit.getState().history()).hasSize(1);
    assertThat(testKit.getState().last().downCount()).isEqualTo(0);
  }

  @Test
  void maintenanceIsAnInputAndIsCarriedInState() {
    var testKit = started(config(0, 0));
    beat(testKit, OK);
    testKit.method(MonitorEntity::setMaintenance).invoke(true);
    assertThat(testKit.getState().underMaintenance()).isTrue();

    beat(testKit, CheckOutcome.maintenance());
    testKit.method(MonitorEntity::setMaintenance).invoke(false);
    beat(testKit, OK);

    assertThat(testKit.getState().history().stream().map(h -> h.status().name()))
        .containsExactly("UP", "MAINTENANCE", "UP");
    assertThat(testKit.getState().history().stream().map(h -> h.important()))
        .containsExactly(true, true, true);
    assertThat(testKit.getState().history().stream().map(h -> h.notified())).containsOnly(false);
  }

  @Test
  void stoppingIsIdempotentAndVisibleToTheBeatThatFollows() {
    var testKit = started(config(0, 0));
    assertThat(testKit.method(MonitorEntity::stop).invoke().getReply()).isEqualTo("stopped");
    assertThat(testKit.method(MonitorEntity::stop).invoke().getReply()).isEqualTo("already stopped");

    var result = beat(testKit, FAILED);
    assertThat(result.active()).isFalse();
  }

  @Test
  void historyIsBoundedAndKeepsTheNewest() {
    var testKit = started(config(0, 0));
    for (int i = 0; i < MonitorEntity.HISTORY_LIMIT + 25; i++) {
      beat(testKit, FAILED);
    }

    var history = testKit.getState().history();
    assertThat(history).hasSize(MonitorEntity.HISTORY_LIMIT);
    assertThat(history.get(history.size() - 1).sequence())
        .isEqualTo(MonitorEntity.HISTORY_LIMIT + 25L);
    assertThat(history.get(0).sequence()).isEqualTo(26L);
  }
}
