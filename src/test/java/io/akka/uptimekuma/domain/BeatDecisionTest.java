package io.akka.uptimekuma.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import org.junit.jupiter.api.Test;

/**
 * The beat loop's state machine.
 *
 * <p>Every case here is a sequence rather than a single beat, because the whole of what this class
 * decides depends on what the monitor already saw: the retry counter, whether the beat is
 * important, and whether anybody is told are all functions of the previous status.
 */
class BeatDecisionTest {

  private static final long T0 = 1_700_000_000_000L;

  private static MonitorConfig monitor(int maxRetries, int retryInterval, int resendInterval) {
    return MonitorConfig.blank("m1")
        .toBuilder()
        .name("web")
        .type("http")
        .url("https://example.com")
        .interval(60)
        .retryInterval(retryInterval)
        .maxretries(maxRetries)
        .resendInterval(resendInterval)
        .build();
  }

  private record Step(Heartbeat beat, boolean notified, int nextInterval) {}

  /** Replay a run of outcomes through the machine, carrying each beat into the next decision. */
  private static java.util.List<Step> replay(
      MonitorConfig config, java.util.List<CheckOutcome> outcomes) {
    java.util.List<Step> steps = new java.util.ArrayList<>();
    Heartbeat previous = null;
    long now = T0;
    for (CheckOutcome outcome : outcomes) {
      BeatDecision.Outcome decision = BeatDecision.decide(config, previous, outcome, now);
      steps.add(
          new Step(
              decision.heartbeat(), decision.sendNotification(), decision.nextIntervalSeconds()));
      previous = decision.heartbeat();
      now += 60_000;
    }
    return steps;
  }

  private static CheckOutcome up() {
    return CheckOutcome.up("200 - OK", 12d);
  }

  private static CheckOutcome down() {
    return CheckOutcome.failed("connect ECONNREFUSED");
  }

  @Test
  void aFirstUpBeatIsImportantButRaisesNoAlert() {
    var steps = replay(monitor(0, 60, 0), java.util.List.of(up()));
    assertEquals(Status.UP, steps.get(0).beat().statusEnum());
    assertTrue(steps.get(0).beat().important());
    // Coming up for the first time is not an outage anybody needs telling about.
    assertFalse(steps.get(0).notified());
  }

  @Test
  void aFirstDownBeatIsImportantAndRaisesAnAlert() {
    var steps = replay(monitor(0, 60, 0), java.util.List.of(down()));
    assertEquals(Status.DOWN, steps.get(0).beat().statusEnum());
    assertTrue(steps.get(0).notified());
  }

  @Test
  void withNoRetriesAFailureGoesStraightDown() {
    var steps = replay(monitor(0, 60, 0), java.util.List.of(up(), down()));
    assertEquals(Status.DOWN, steps.get(1).beat().statusEnum());
    assertTrue(steps.get(1).beat().important());
    assertTrue(steps.get(1).notified());
  }

  @Test
  void withRetriesAFailureIsPendingUntilTheyRunOut() {
    var steps = replay(monitor(2, 20, 0), java.util.List.of(up(), down(), down(), down()));
    assertEquals(Status.PENDING, steps.get(1).beat().statusEnum());
    assertEquals(1, steps.get(1).beat().retries());
    assertEquals(Status.PENDING, steps.get(2).beat().statusEnum());
    assertEquals(2, steps.get(2).beat().retries());
    assertEquals(Status.DOWN, steps.get(3).beat().statusEnum());
    assertEquals(3, steps.get(3).beat().retries());
  }

  @Test
  void aPendingBeatBeatsAgainOnTheRetryInterval() {
    var steps = replay(monitor(2, 20, 0), java.util.List.of(up(), down()));
    assertEquals(60, steps.get(0).nextInterval());
    assertEquals(20, steps.get(1).nextInterval());
  }

  @Test
  void onlyTheTransitionOutOfPendingIsImportant() {
    var steps = replay(monitor(2, 20, 0), java.util.List.of(up(), down(), down(), down()));
    assertFalse(steps.get(1).beat().important());
    assertFalse(steps.get(2).beat().important());
    assertTrue(steps.get(3).beat().important());
    assertTrue(steps.get(3).notified());
  }

  @Test
  void theRetryCounterKeepsClimbingOnceTheMonitorIsDown() {
    var steps = replay(monitor(1, 20, 0), java.util.List.of(down(), down(), down(), down()));
    assertEquals(1, steps.get(0).beat().retries());
    assertEquals(2, steps.get(1).beat().retries());
    assertEquals(3, steps.get(2).beat().retries());
    assertEquals(4, steps.get(3).beat().retries());
  }

  @Test
  void comingBackUpIsImportantAndRaisesAnAlert() {
    var steps = replay(monitor(0, 60, 0), java.util.List.of(down(), up()));
    assertTrue(steps.get(1).beat().important());
    assertTrue(steps.get(1).notified());
    assertEquals(0, steps.get(1).beat().retries());
  }

  @Test
  void aResendIntervalRaisesTheAlarmAgainAfterThatManyQuietBeats() {
    var steps = replay(monitor(0, 60, 3), java.util.List.of(down(), down(), down(), down(), down()));
    // The first is the outage; the next two are counted and silent; the third reaches the count.
    assertTrue(steps.get(0).notified());
    assertFalse(steps.get(1).notified());
    assertEquals(1, steps.get(1).beat().downCount());
    assertFalse(steps.get(2).notified());
    assertEquals(2, steps.get(2).beat().downCount());
    assertTrue(steps.get(3).notified());
    assertEquals(0, steps.get(3).beat().downCount());
    assertFalse(steps.get(4).notified());
  }

  @Test
  void withNoResendIntervalTheAlarmIsRaisedOnce() {
    var steps = replay(monitor(0, 60, 0), java.util.List.of(down(), down(), down(), down()));
    assertTrue(steps.get(0).notified());
    assertFalse(steps.get(1).notified());
    assertFalse(steps.get(2).notified());
    assertFalse(steps.get(3).notified());
  }

  @Test
  void anUpsideDownMonitorTreatsAFailedCheckAsHealthy() {
    MonitorConfig config = monitor(2, 20, 0).toBuilder().upsideDown(true).build();
    var steps = replay(config, java.util.List.of(down(), down()));
    assertEquals(Status.UP, steps.get(0).beat().statusEnum());
    assertEquals(0, steps.get(0).beat().retries());
    assertEquals(Status.UP, steps.get(1).beat().statusEnum());
  }

  @Test
  void anUpsideDownMonitorTreatsASuccessfulCheckAsAnOutageAndRetriesIt() {
    MonitorConfig config = monitor(1, 20, 0).toBuilder().upsideDown(true).build();
    var steps = replay(config, java.util.List.of(down(), up(), up()));
    assertEquals(Status.UP, steps.get(0).beat().statusEnum());
    // The success is flipped down, and a monitor with a retry left goes pending first.
    assertEquals(Status.PENDING, steps.get(1).beat().statusEnum());
    assertEquals("Flip UP to DOWN", steps.get(1).beat().msg());
    assertEquals(Status.DOWN, steps.get(2).beat().statusEnum());
  }

  @Test
  void aJsonQueryMonitorSetToSkipRetriesGoesStraightDownOnAQueryFailure() {
    MonitorConfig config =
        monitor(3, 20, 0).toBuilder().type("json-query").retryOnlyOnStatusCodeFailure(true).build();
    var steps =
        replay(
            config,
            java.util.List.of(
                up(), CheckOutcome.failed("JSON query does not pass (comparing 1 == 2)")));
    assertEquals(Status.DOWN, steps.get(1).beat().statusEnum());
    assertEquals(0, steps.get(1).beat().retries());
  }

  @Test
  void aJsonQueryMonitorSetToSkipRetriesStillRetriesATransportFailure() {
    MonitorConfig config =
        monitor(3, 20, 0).toBuilder().type("json-query").retryOnlyOnStatusCodeFailure(true).build();
    var steps = replay(config, java.util.List.of(up(), down()));
    assertEquals(Status.PENDING, steps.get(1).beat().statusEnum());
  }

  @Test
  void maintenanceOverridesTheCheckAndClearsTheRetryCounter() {
    var steps =
        replay(monitor(3, 20, 0), java.util.List.of(down(), down(), CheckOutcome.maintenance()));
    assertEquals(Status.MAINTENANCE, steps.get(2).beat().statusEnum());
    assertEquals("Monitor under maintenance", steps.get(2).beat().msg());
    assertEquals(0, steps.get(2).beat().retries());
  }

  @Test
  void goingIntoMaintenanceIsImportantButSilent() {
    var steps = replay(monitor(0, 60, 0), java.util.List.of(up(), CheckOutcome.maintenance()));
    assertTrue(steps.get(1).beat().important());
    assertFalse(steps.get(1).notified());
  }

  @Test
  void comingOutOfMaintenanceIntoAnOutageIsNotSilent() {
    var steps =
        replay(monitor(0, 60, 0), java.util.List.of(up(), CheckOutcome.maintenance(), down()));
    assertTrue(steps.get(2).beat().important());
    assertTrue(steps.get(2).notified());
  }

  @Test
  void comingOutOfMaintenanceIntoHealthIsSilent() {
    var steps =
        replay(monitor(0, 60, 0), java.util.List.of(up(), CheckOutcome.maintenance(), up()));
    assertTrue(steps.get(2).beat().important());
    assertFalse(steps.get(2).notified());
  }

  @Test
  void everyImportantTransitionIsTheOneTheSourceNames() {
    assertTrue(BeatDecision.isImportantBeat(true, null, Status.UP));
    assertTrue(BeatDecision.isImportantBeat(false, Status.UP, Status.DOWN));
    assertTrue(BeatDecision.isImportantBeat(false, Status.DOWN, Status.UP));
    assertTrue(BeatDecision.isImportantBeat(false, Status.PENDING, Status.DOWN));
    assertTrue(BeatDecision.isImportantBeat(false, Status.UP, Status.MAINTENANCE));
    assertTrue(BeatDecision.isImportantBeat(false, Status.DOWN, Status.MAINTENANCE));
    assertTrue(BeatDecision.isImportantBeat(false, Status.MAINTENANCE, Status.UP));
    assertTrue(BeatDecision.isImportantBeat(false, Status.MAINTENANCE, Status.DOWN));
    assertFalse(BeatDecision.isImportantBeat(false, Status.UP, Status.UP));
    assertFalse(BeatDecision.isImportantBeat(false, Status.DOWN, Status.DOWN));
    assertFalse(BeatDecision.isImportantBeat(false, Status.UP, Status.PENDING));
    assertFalse(BeatDecision.isImportantBeat(false, Status.PENDING, Status.PENDING));
  }

  @Test
  void theThreeSilentTransitionsAreTheOnesAboutMaintenance() {
    assertFalse(BeatDecision.isImportantForNotification(false, Status.UP, Status.MAINTENANCE));
    assertFalse(BeatDecision.isImportantForNotification(false, Status.DOWN, Status.MAINTENANCE));
    assertFalse(BeatDecision.isImportantForNotification(false, Status.MAINTENANCE, Status.UP));
    assertTrue(BeatDecision.isImportantForNotification(false, Status.MAINTENANCE, Status.DOWN));
  }

  @Test
  void theNextBeatIsMeasuredFromWhenThisOneStarted() {
    // A check that took four seconds on a sixty-second monitor waits fifty-six, so the cadence
    // does not drift by the length of every check.
    assertEquals(56_000, BeatDecision.nextDelayMillis(60, 4_000));
    // A check that overran its own interval beats again straight away rather than in the past.
    assertEquals(1, BeatDecision.nextDelayMillis(60, 120_000));
  }

  @Test
  void theAlertTextIsTheSourcesOwnWording() {
    Heartbeat upBeat =
        new Heartbeat(1, "m1", true, Status.UP.code(), "200 - OK", T0, 12d, 0, 0, 0, T0, null);
    Heartbeat downBeat =
        new Heartbeat(2, "m1", true, Status.DOWN.code(), "refused", T0, null, 0, 0, 1, T0, null);
    assertEquals("[web] [✅ Up] 200 - OK", BeatDecision.notificationText("web", upBeat));
    assertEquals("[web] [🔴 Down] refused", BeatDecision.notificationText("web", downBeat));
  }

  @Test
  void onlyAPushMonitorRecordsTheGapBetweenBeats() {
    var http = replay(monitor(0, 60, 0), java.util.List.of(up(), up()));
    assertEquals(0, http.get(1).beat().duration());

    MonitorConfig push = monitor(0, 60, 0).toBuilder().type("push").build();
    var pushed = replay(push, java.util.List.of(up(), up()));
    assertEquals(60, pushed.get(1).beat().duration());
  }
}
