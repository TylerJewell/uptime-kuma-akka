package io.akka.uptimekuma.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 R2, R3, R7–R9, R14–R16.
 *
 * <p>Every rule here is about what the loop does *next* given what it has already seen, so every
 * test drives a whole sequence and asserts on the sequence. A table of single beats agrees with an
 * implementation whose retry counter resets, whose re-send period is off by one, or whose DOWN
 * beats keep using the retry interval — none of which is visible in one row.
 */
class BeatDecisionTest {

  private static MonitorConfig config(
      int intervalSeconds, int retryIntervalSeconds, int maxRetries, int resendIntervalSeconds) {
    return new MonitorConfig(
        "http://target.invalid/",
        intervalSeconds,
        retryIntervalSeconds,
        maxRetries,
        resendIntervalSeconds,
        List.of("n1"));
  }

  /** Drive `outcomes` through the decision function, threading each beat into the next. */
  private static List<Heartbeat> run(MonitorConfig config, List<CheckOutcome> outcomes) {
    var beats = new ArrayList<Heartbeat>();
    Heartbeat previous = null;
    long clock = 1_000_000L;
    for (CheckOutcome outcome : outcomes) {
      previous = BeatDecision.decide(config, previous, outcome, clock).heartbeat();
      beats.add(previous);
      clock += 1000;
    }
    return beats;
  }

  private static List<CheckOutcome> repeated(CheckOutcome outcome, int times) {
    return java.util.stream.IntStream.range(0, times).mapToObj(i -> outcome).toList();
  }

  private static final CheckOutcome FAILED = CheckOutcome.failed("connect refused");
  private static final CheckOutcome OK = CheckOutcome.up("200 - OK", 12);

  private static List<String> statuses(List<Heartbeat> beats) {
    return beats.stream().map(b -> b.status().name()).toList();
  }

  @Test
  void ladderClimbsThroughPendingIntoDownAndTheCounterKeepsGoing() {
    var beats = run(config(60, 20, 2, 0), repeated(FAILED, 6));

    assertThat(statuses(beats))
        .containsExactly("PENDING", "PENDING", "DOWN", "DOWN", "DOWN", "DOWN");
    assertThat(beats.stream().map(Heartbeat::retries)).containsExactly(1, 2, 3, 4, 5, 6);
    assertThat(beats.stream().map(Heartbeat::important))
        .containsExactly(true, false, true, false, false, false);
  }

  @Test
  void ladderWithNoRetriesNeverProducesAPendingBeat() {
    var beats = run(config(60, 20, 0, 0), repeated(FAILED, 3));

    assertThat(statuses(beats)).containsExactly("DOWN", "DOWN", "DOWN");
    assertThat(beats.stream().map(Heartbeat::retries)).containsExactly(1, 2, 3);
  }

  @Test
  void aSuccessfulCheckClearsTheRetryCounter() {
    var beats = run(config(60, 20, 3, 0), List.of(FAILED, FAILED, OK, FAILED));

    assertThat(statuses(beats)).containsExactly("PENDING", "PENDING", "UP", "PENDING");
    assertThat(beats.stream().map(Heartbeat::retries)).containsExactly(1, 2, 0, 1);
  }

  @Test
  void aBlipInsideTheRetryWindowTellsNobody() {
    var beats = run(config(60, 20, 3, 0), List.of(OK, FAILED, OK, OK));

    assertThat(statuses(beats)).containsExactly("UP", "PENDING", "UP", "UP");
    assertThat(beats.stream().map(Heartbeat::notified)).containsOnly(false);
  }

  @Test
  void anOutageAndItsRecoveryEachSendOnce() {
    var beats = run(config(60, 20, 1, 0), List.of(OK, FAILED, FAILED, OK));

    assertThat(statuses(beats)).containsExactly("UP", "PENDING", "DOWN", "UP");
    assertThat(beats.stream().map(Heartbeat::notified)).containsExactly(false, false, true, true);
  }

  @Test
  void resendCounterFiresEveryNthUnimportantDownBeatAndResets() {
    var beats = run(config(60, 60, 0, 3), repeated(FAILED, 9));

    assertThat(beats.stream().map(Heartbeat::downCount)).containsExactly(0, 1, 2, 0, 1, 2, 0, 1, 2);
    assertThat(beats.stream().map(Heartbeat::notified))
        .containsExactly(true, false, false, true, false, false, true, false, false);
  }

  @Test
  void resendDisabledSendsOnlyTheTransition() {
    var beats = run(config(60, 60, 0, 0), repeated(FAILED, 9));

    assertThat(beats.stream().map(Heartbeat::downCount)).containsOnly(0);
    assertThat(beats.stream().filter(Heartbeat::notified)).hasSize(1);
  }

  @Test
  void anImportantBeatZeroesTheResendCounter() {
    var beats = run(config(60, 60, 0, 10), List.of(OK, FAILED, FAILED, FAILED, FAILED, OK));

    assertThat(statuses(beats)).containsExactly("UP", "DOWN", "DOWN", "DOWN", "DOWN", "UP");
    assertThat(beats.stream().map(Heartbeat::downCount)).containsExactly(0, 0, 1, 2, 3, 0);
  }

  @Test
  void aFirstBeatSendsOnlyWhenItIsDown() {
    assertThat(run(config(60, 60, 0, 0), List.of(OK)).get(0).notified()).isFalse();
    assertThat(run(config(60, 60, 2, 0), List.of(FAILED)).get(0).notified()).isFalse();
    assertThat(run(config(60, 60, 0, 0), List.of(FAILED)).get(0).notified()).isTrue();
    assertThat(run(config(60, 60, 0, 0), List.of(CheckOutcome.maintenance())).get(0).notified())
        .isFalse();
  }

  @Test
  void aMonitorUnderMaintenanceIsRecordedAndSilent() {
    var beats =
        run(
            config(60, 60, 0, 0),
            List.of(OK, CheckOutcome.maintenance(), CheckOutcome.maintenance(), OK));

    assertThat(statuses(beats)).containsExactly("UP", "MAINTENANCE", "MAINTENANCE", "UP");
    assertThat(beats.stream().map(Heartbeat::important)).containsExactly(true, true, false, true);
    assertThat(beats.stream().map(Heartbeat::notified)).containsOnly(false);
  }

  @Test
  void aMaintenanceBeatClearsTheRetryCounterTheWayASuccessfulCheckDoes() {
    // A monitor mid-retry whose window opens comes out of it with a fresh allowance rather
    // than where it left off, so the window costs the outage its accumulated retries.
    var beats =
        run(
            config(60, 20, 3, 0),
            List.of(FAILED, CheckOutcome.maintenance(), FAILED, FAILED));

    assertThat(statuses(beats)).containsExactly("PENDING", "MAINTENANCE", "PENDING", "PENDING");
    assertThat(beats.stream().map(Heartbeat::retries)).containsExactly(1, 0, 1, 2);
  }

  @Test
  void aCheckThatFailsAsTheWindowClosesDoesSend() {
    var beats = run(config(60, 60, 0, 0), List.of(CheckOutcome.maintenance(), FAILED));

    assertThat(statuses(beats)).containsExactly("MAINTENANCE", "DOWN");
    assertThat(beats.stream().map(Heartbeat::notified)).containsExactly(false, true);
  }

  @Test
  void sequenceNumbersRunFromOneWithoutGaps() {
    var beats = run(config(60, 60, 0, 0), repeated(FAILED, 5));
    assertThat(beats.stream().map(Heartbeat::sequence)).containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  void nextDelayUsesTheRetryIntervalOnlyForPendingAndOnlyWhenItIsSet() {
    var withRetry = config(60, 20, 2, 0);
    assertThat(BeatDecision.nextDelayMillis(withRetry, Status.PENDING, 0)).isEqualTo(20_000);
    assertThat(BeatDecision.nextDelayMillis(withRetry, Status.DOWN, 0)).isEqualTo(60_000);
    assertThat(BeatDecision.nextDelayMillis(withRetry, Status.UP, 0)).isEqualTo(60_000);
    assertThat(BeatDecision.nextDelayMillis(withRetry, Status.MAINTENANCE, 0)).isEqualTo(60_000);

    var withoutRetry = config(30, 0, 2, 0);
    assertThat(BeatDecision.nextDelayMillis(withoutRetry, Status.PENDING, 0)).isEqualTo(30_000);
  }

  @Test
  void nextDelayHasTheBeatsOwnDurationTakenOffAndNeverGoesBelowOne() {
    var config = config(60, 20, 2, 0);
    assertThat(BeatDecision.nextDelayMillis(config, Status.UP, 250)).isEqualTo(59_750);
    assertThat(BeatDecision.nextDelayMillis(config, Status.PENDING, 19_999)).isEqualTo(1);
    assertThat(BeatDecision.nextDelayMillis(config, Status.UP, 90_000)).isEqualTo(1);
  }

  @Test
  void aNonPositiveIntervalIsRefusedRatherThanSubstituted() {
    // The refusal is a value the boundary reads, not an exception the constructor raises:
    // this record is also rebuilt when a persisted event is replayed, and a rule that
    // throws there makes a monitor that cannot be read back at all.
    assertThat(config(0, 20, 2, 0).validate()).hasValueSatisfying(
        reason -> assertThat(reason).contains("intervalSeconds"));
    assertThat(config(60, 20, 2, 0).validate()).isEmpty();
  }
}
