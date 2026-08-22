package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.uptimekuma.domain.BeatDecision;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One beat of one monitor: check, record, tell whoever needs telling, and arm the next beat.
 *
 * <p>The delay the next beat is armed at is computed from the status this beat just produced, so
 * the loop is not a fixed cadence — a monitor inside its retry window beats faster than one that is
 * up, and one that has gone down slows back to the ordinary interval. That dependency is the reason
 * the loop re-arms itself here rather than being driven by a schedule outside it.
 */
@Component(id = "monitor-beat")
public class MonitorBeat extends TimedAction {

  private static final Logger log = LoggerFactory.getLogger(MonitorBeat.class);

  /** The timer name a monitor's next beat is always armed under, so that arming replaces. */
  public static String timerName(String monitorId) {
    return "beat-" + monitorId;
  }

  public record Beat(String monitorId) {}

  private final TimerScheduler timers;
  private final ComponentClient componentClient;
  private final NotificationFanOut fanOut;

  public MonitorBeat(TimerScheduler timers, ComponentClient componentClient) {
    this.timers = timers;
    this.componentClient = componentClient;
    this.fanOut = new NotificationFanOut(componentClient);
  }

  public Effect beat(Beat command) {
    long startedAt = System.currentTimeMillis();
    var state =
        componentClient
            .forEventSourcedEntity(command.monitorId())
            .method(MonitorEntity::beatContext)
            .invoke();

    if (!state.created() || !state.active()) {
      log.info("[{}] not running, no next check", command.monitorId());
      return effects().done();
    }

    CheckOutcome check =
        state.underMaintenance() ? CheckOutcome.maintenance() : HttpProbe.check(state.config());

    var result =
        componentClient
            .forEventSourcedEntity(command.monitorId())
            .method(MonitorEntity::recordBeat)
            .invoke(
                new MonitorEntity.RecordBeat(check, state.nextSequence(), System.currentTimeMillis()));

    if (result.duplicate()) {
      // A timer is delivered at least once. A beat that arrives twice must not advance the
      // re-send counter twice, so the second one records nothing and arms nothing: the first
      // one already armed the next beat. SPEC-001 §4 OD2.
      log.info("[{}] beat {} already recorded", command.monitorId(), state.nextSequence());
      return effects().done();
    }

    if (result.send()) {
      var delivered = fanOut.send(command.monitorId(), state.config(), result.heartbeat());
      log.info(
          "[{}] {} notified {} of {}",
          command.monitorId(),
          result.heartbeat().status(),
          delivered.size(),
          state.config().notificationIds().size());
    }

    if (result.active()) {
      long delay =
          BeatDecision.nextDelayMillis(
              state.config(), result.heartbeat().status(), System.currentTimeMillis() - startedAt);
      arm(command.monitorId(), Duration.ofMillis(delay));
    }
    return effects().done();
  }

  /**
   * Arm the next beat. Always under the same name, so this replaces whatever was pending rather
   * than adding to it — which is what makes stopping a monitor, or changing its interval, take
   * effect instead of leaving an older beat alive alongside the new one.
   */
  private void arm(String monitorId, Duration delay) {
    timers.createSingleTimer(
        timerName(monitorId),
        delay,
        componentClient.forTimedAction().method(MonitorBeat::beat).deferred(new Beat(monitorId)));
  }
}
