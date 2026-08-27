package io.akka.uptimekuma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A monitor's durable state, and what a beat does to it. */
class MonitorEntityTest {

  private static MonitorConfig config() {
    return MonitorConfig.blank("m1")
        .toBuilder()
        .name("web")
        .type("http")
        .url("https://example.com")
        .interval(60)
        .retryInterval(20)
        .maxretries(1)
        .build();
  }

  private static EventSourcedTestKit<MonitorEntity.State, MonitorEntity.Event, MonitorEntity>
      testKit() {
    return EventSourcedTestKit.of("m1", MonitorEntity::new);
  }

  @Test
  void creatingAMonitorRecordsItsConfiguration() {
    var kit = testKit();
    var result = kit.method(MonitorEntity::create).invoke(config());
    assertEquals("created", result.getReply());
    assertEquals("web", kit.getState().config().name());
    assertTrue(kit.getState().created());
  }

  @Test
  void aMonitorThatWillNotValidateIsRefused() {
    var kit = testKit();
    var result = kit.method(MonitorEntity::create).invoke(config().toBuilder().interval(0).build());
    assertTrue(result.isError());
    assertFalse(kit.getState().created());
  }

  @Test
  void creatingAMonitorThatExistsReconfiguresIt() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    var result =
        kit.method(MonitorEntity::create).invoke(config().toBuilder().name("api").build());
    assertEquals("reconfigured", result.getReply());
    assertEquals("api", kit.getState().config().name());
  }

  @Test
  void startingAndStoppingAreIdempotent() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config().toBuilder().active(false).build());
    assertEquals("started", kit.method(MonitorEntity::start).invoke().getReply());
    assertEquals("already running", kit.method(MonitorEntity::start).invoke().getReply());
    assertEquals("stopped", kit.method(MonitorEntity::stop).invoke().getReply());
    assertEquals("already stopped", kit.method(MonitorEntity::stop).invoke().getReply());
  }

  @Test
  void aBeatIsRecordedAndFoldedIntoTheStatistics() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    var result =
        kit.method(MonitorEntity::recordBeat)
            .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    MonitorEntity.BeatResult beat = result.getReply();
    assertEquals(Status.UP, beat.heartbeat().statusEnum());
    assertFalse(beat.duplicate());
    assertEquals(1, kit.getState().history().size());
    assertEquals(1, kit.getState().stats().get24Hour(now).up());
  }

  @Test
  void aBeatThatArrivesTwiceIsRecordedOnce() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    // A timer is delivered at least once, so the same beat can arrive again. Recording it twice
    // would advance the re-send counter twice for one outage.
    var again =
        kit.method(MonitorEntity::recordBeat)
            .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    assertTrue(again.getReply().duplicate());
    assertEquals(1, kit.getState().history().size());
  }

  @Test
  void theRetryCounterSurvivesAcrossBeats() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    var first =
        kit.method(MonitorEntity::recordBeat)
            .invoke(new MonitorEntity.RecordBeat(CheckOutcome.failed("refused"), 2, now + 1000, false));
    assertEquals(Status.PENDING, first.getReply().heartbeat().statusEnum());
    var second =
        kit.method(MonitorEntity::recordBeat)
            .invoke(new MonitorEntity.RecordBeat(CheckOutcome.failed("refused"), 3, now + 2000, false));
    assertEquals(Status.DOWN, second.getReply().heartbeat().statusEnum());
    assertEquals(2, second.getReply().heartbeat().retries());
  }

  /**
   * A beat's number is never handed out twice, whatever happens to the history it was kept in.
   *
   * <p>Each beat is published under a key made of the monitor and this number, and a key-value
   * entity refuses a write after its own deletion — so a number that restarts after a clear makes
   * the next beat write to a key that the clear has just removed, and every beat from then on
   * fails. The monitor therefore counts every beat it has ever taken rather than reading the
   * number off a window that is bounded and can be emptied.
   */
  @Test
  void aBeatsNumberKeepsClimbingAfterTheHistoryIsCleared() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 2, now + 1000, false));
    assertEquals(3, kit.getState().nextSequence());

    kit.method(MonitorEntity::clearHeartbeats).invoke();
    assertEquals(0, kit.getState().history().size());
    assertEquals(3, kit.getState().nextSequence(), "the numbering restarted after a clear");

    var after =
        kit.method(MonitorEntity::recordBeat)
            .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 9d), 3, now + 2000, false));
    assertEquals(3, after.getReply().heartbeat().sequence());
    assertFalse(after.getReply().duplicate());

    // And clearing the events, which keeps the beats, does not move it either.
    kit.method(MonitorEntity::clearEvents).invoke();
    assertEquals(4, kit.getState().nextSequence());
  }

  @Test
  void importantBeatsAreKeptSeparatelyFromTheRollingWindow() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 2, now + 1000, false));
    assertEquals(2, kit.getState().history().size());
    // Only the first beat marked a change.
    assertEquals(1, kit.getState().importantHistory().size());
  }

  @Test
  void theRollingWindowIsBounded() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    for (int i = 1; i <= MonitorEntity.HISTORY_LIMIT + 25; i++) {
      kit.method(MonitorEntity::recordBeat)
          .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 1d), i, now + i, false));
    }
    assertEquals(MonitorEntity.HISTORY_LIMIT, kit.getState().history().size());
  }

  @Test
  void clearingEventsBlanksTheMessagesAndKeepsTheBeats() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.failed("refused"), 1, now, false));
    kit.method(MonitorEntity::clearEvents).invoke();
    assertEquals(1, kit.getState().history().size());
    assertEquals("", kit.getState().history().get(0).msg());
    assertFalse(kit.getState().history().get(0).important());
    assertTrue(kit.getState().importantHistory().isEmpty());
  }

  @Test
  void clearingHeartbeatsDropsTheHistoryAndTheStatistics() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    kit.method(MonitorEntity::clearHeartbeats).invoke();
    assertTrue(kit.getState().history().isEmpty());
    assertEquals(0, kit.getState().stats().get24Hour(now).up());
    // The configuration survives: clearing history is not deleting the monitor.
    assertTrue(kit.getState().created());
  }

  @Test
  void aRenewedCertificateReArmsEveryWarning() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    kit.method(MonitorEntity::recordTlsInfo).invoke(Map.of("fingerprint256", "aa:bb"));
    kit.method(MonitorEntity::recordCertNotified).invoke(21);
    kit.method(MonitorEntity::recordCertNotified).invoke(14);
    assertEquals(2, kit.getState().certNotifiedDays().size());
    // A different certificate is a different expiry, so the thresholds already passed were about
    // something that has been replaced.
    kit.method(MonitorEntity::recordTlsInfo).invoke(Map.of("fingerprint256", "cc:dd"));
    assertTrue(kit.getState().certNotifiedDays().isEmpty());
  }

  @Test
  void theSameCertificateDoesNotReArmTheWarnings() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    kit.method(MonitorEntity::recordTlsInfo).invoke(Map.of("fingerprint256", "aa:bb"));
    kit.method(MonitorEntity::recordCertNotified).invoke(21);
    kit.method(MonitorEntity::recordTlsInfo).invoke(Map.of("fingerprint256", "aa:bb"));
    assertEquals(1, kit.getState().certNotifiedDays().size());
  }

  @Test
  void aMaintenanceFlagThatDoesNotChangeCostsNoEvent() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    kit.method(MonitorEntity::setMaintenance).invoke(true);
    var again = kit.method(MonitorEntity::setMaintenance).invoke(true);
    assertEquals("unchanged", again.getReply());
    assertTrue(kit.getState().underMaintenance());
  }

  @Test
  void aDeletedMonitorForgetsEverything() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    kit.method(MonitorEntity::recordBeat)
        .invoke(
            new MonitorEntity.RecordBeat(
                CheckOutcome.up("200 - OK", 12d), 1, System.currentTimeMillis(), false));
    kit.method(MonitorEntity::delete).invoke();
    assertFalse(kit.getState().created());
    assertTrue(kit.getState().history().isEmpty());
    assertNull(kit.getState().config());
  }

  @Test
  void aBeatContextCarriesOnlyWhatABeatNeeds() {
    var kit = testKit();
    kit.method(MonitorEntity::create).invoke(config());
    long now = System.currentTimeMillis();
    kit.method(MonitorEntity::recordBeat)
        .invoke(new MonitorEntity.RecordBeat(CheckOutcome.up("200 - OK", 12d), 1, now, false));
    MonitorEntity.BeatContext context = kit.method(MonitorEntity::beatContext).invoke().getReply();
    assertEquals("web", context.config().name());
    assertEquals(2, context.nextSequence());
    assertEquals(Status.UP, context.previousBeat().statusEnum());
  }
}
