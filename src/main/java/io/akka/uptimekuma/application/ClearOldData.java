package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forget the beats that are older than the retention setting.
 *
 * <p>Without this a monitor beating every twenty seconds leaves a million and a half rows a year
 * behind it, none of which anything reads: the interface draws the last hundred, the statistics
 * are held on the monitor itself, and the stream only ever resumes from the recent past. The
 * source deletes them on a daily job and this is that job.
 *
 * <p>Runs at 03:14 in the server's timezone, which is when the source runs it, and re-arms itself
 * for the next one. A retention of less than a day switches deletion off — again the source's
 * rule, and the one an operator uses to keep everything.
 */
@Component(id = "clear-old-data")
public class ClearOldData extends TimedAction {

  private static final Logger log = LoggerFactory.getLogger(ClearOldData.class);

  /** The timer this job is always armed under, so arming replaces rather than accumulates. */
  public static final String TIMER = "clear-old-data";

  private static final int HOUR = 3;
  private static final int MINUTE = 14;

  /** How many beats one pass will forget before leaving the rest for the next. */
  private static final int BATCH = 5000;

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public ClearOldData(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  public record Run() {}

  public Effect run(Run command) {
    int days = Settings.number(componentClient, "keepDataPeriodDays", Settings.DEFAULT_KEEP_PERIOD);
    if (days < 1) {
      log.info("data deletion is off: the retention period is {} day(s)", days);
    } else {
      forget(System.currentTimeMillis() - days * 24L * 3600L * 1000L);
    }
    arm(componentClient, timers, System.currentTimeMillis(), Settings.timezone(componentClient));
    return effects().done();
  }

  private void forget(long before) {
    var expired =
        componentClient.forView().method(HeartbeatFeedView::expired).invoke(before).items();
    int forgotten = 0;
    for (HeartbeatFeedView.HeartbeatRow row : expired) {
      componentClient
          .forKeyValueEntity(
              HeartbeatAnnouncementEntity.key(row.monitorId(), row.sequence()))
          .method(HeartbeatAnnouncementEntity::forget)
          .invoke();
      forgotten++;
    }
    log.info(
        "forgot {} beat(s) older than {}{}",
        forgotten,
        java.time.Instant.ofEpochMilli(before),
        forgotten == BATCH ? ", more remain for the next pass" : "");
  }

  /** Arm the next pass for the coming 03:14, in whichever timezone the server is set to. */
  public static void arm(
      ComponentClient componentClient, TimerScheduler timers, long now, String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone);
    } catch (Exception e) {
      zone = ZoneId.systemDefault();
    }
    ZonedDateTime here = java.time.Instant.ofEpochMilli(now).atZone(zone);
    ZonedDateTime next = here.withHour(HOUR).withMinute(MINUTE).withSecond(0).withNano(0);
    if (!next.isAfter(here)) {
      next = next.plusDays(1);
    }
    timers.createSingleTimer(
        TIMER,
        Duration.ofMillis(next.toInstant().toEpochMilli() - now),
        componentClient.forTimedAction().method(ClearOldData::run).deferred(new Run()));
  }
}
