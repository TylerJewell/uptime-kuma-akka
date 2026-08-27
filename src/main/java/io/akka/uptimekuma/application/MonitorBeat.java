package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.uptimekuma.checks.CheckContext;
import io.akka.uptimekuma.checks.Checks;
import io.akka.uptimekuma.domain.BeatDecision;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import io.akka.uptimekuma.notifications.HttpSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One beat of one monitor: check, record, publish, tell whoever needs telling, arm the next.
 *
 * <p>The delay the next beat is armed at depends on the status this beat produced, so the loop is
 * not a fixed cadence — a monitor inside its retry window beats faster than one that is up, and one
 * that has gone down slows back to its ordinary interval. That dependency is why the loop re-arms
 * itself here rather than being driven by a schedule outside it.
 *
 * <p>The wait is measured from the instant the beat started rather than from when it finished, so a
 * check that took four seconds on a twenty-second monitor waits sixteen and the cadence does not
 * drift.
 */
@Component(id = "monitor-beat")
public class MonitorBeat extends TimedAction {

  private static final Logger log = LoggerFactory.getLogger(MonitorBeat.class);

  /** The timer name a monitor's next beat is always armed under, so arming replaces. */
  public static String timerName(String monitorId) {
    return "beat-" + monitorId;
  }

  public record Beat(String monitorId) {}

  private final TimerScheduler timers;
  private final ComponentClient componentClient;

  public MonitorBeat(TimerScheduler timers, ComponentClient componentClient) {
    this.timers = timers;
    this.componentClient = componentClient;
  }

  public Effect beat(Beat command) {
    long startedAt = System.currentTimeMillis();
    MonitorEntity.BeatContext state =
        componentClient
            .forEventSourcedEntity(command.monitorId())
            .method(MonitorEntity::beatContext)
            .invoke();

    if (!state.created() || !state.active()) {
      log.info("[{}] not running, no next check", command.monitorId());
      return effects().done();
    }

    String timezone = Settings.timezone(componentClient);
    boolean underMaintenance =
        Maintenances.covers(componentClient, command.monitorId(), timezone, startedAt);
    if (underMaintenance != state.underMaintenance()) {
      componentClient
          .forEventSourcedEntity(command.monitorId())
          .method(MonitorEntity::setMaintenance)
          .invoke(underMaintenance);
    }

    CheckOutcome check;
    if (underMaintenance) {
      // The maintenance branch runs before any probe, so nothing is checked at all.
      check = CheckOutcome.maintenance();
    } else {
      CheckContext context = contextFor(state.config(), state, startedAt);
      check = Checks.run(state.config(), context);
      if (check == null) {
        // A push monitor still inside its window: nothing to record, and the next look is armed
        // for the remainder of the window rather than for a whole interval.
        long remaining = Checks.pushWindowRemainingMillis(state.config(), context);
        arm(command.monitorId(), Duration.ofMillis(Math.max(1, remaining)));
        return effects().done();
      }
    }

    MonitorEntity.BeatResult result =
        componentClient
            .forEventSourcedEntity(command.monitorId())
            .method(MonitorEntity::recordBeat)
            .invoke(
                new MonitorEntity.RecordBeat(
                    check, state.nextSequence(), System.currentTimeMillis(), false));

    if (result.duplicate()) {
      // A timer is delivered at least once. A beat that arrives twice must not advance the
      // re-send counter twice, so the second one records nothing and arms nothing: the first
      // already armed the next beat.
      log.info("[{}] beat {} already recorded", command.monitorId(), state.nextSequence());
      return effects().done();
    }

    long feedSequence = Ids.nextNumber(componentClient, "feed");
    componentClient
        .forKeyValueEntity(
            HeartbeatAnnouncementEntity.key(command.monitorId(), result.heartbeat().sequence()))
        .method(HeartbeatAnnouncementEntity::publish)
        .invoke(new HeartbeatAnnouncementEntity.Announcement(feedSequence, result.heartbeat()));

    if (result.sendNotification()) {
      MonitorEntity.State full =
          componentClient
              .forEventSourcedEntity(command.monitorId())
              .method(MonitorEntity::get)
              .invoke();
      Notifications.send(
          componentClient,
          new HttpSender(),
          Versions.APP_VERSION,
          state.config(),
          result.heartbeat(),
          full.importantHistory());
    }

    if (result.heartbeat().statusEnum() != Status.MAINTENANCE
        && state.config().domainExpiryNotification()) {
      DomainExpiry.checkAndNotify(componentClient, state.config());
    }

    if (result.active()) {
      long delay =
          BeatDecision.nextDelayMillis(
              result.nextIntervalSeconds(), System.currentTimeMillis() - startedAt);
      arm(command.monitorId(), Duration.ofMillis(delay));
    }
    return effects().done();
  }

  /** Everything the check needs that is not on the monitor: hosts, proxies, children, settings. */
  private CheckContext contextFor(
      MonitorConfig config, MonitorEntity.BeatContext state, long now) {
    Map<String, String> settings = new LinkedHashMap<>();
    Settings.read(componentClient)
        .forEach((key, value) -> settings.put(key, value == null ? null : String.valueOf(value)));
    settings.put("version", Versions.APP_VERSION);

    CheckContext.DockerHostConfig dockerHost = null;
    if (config.docker_host() != null && !config.docker_host().isEmpty()) {
      StoredRecord record =
          componentClient
              .forKeyValueEntity(config.docker_host())
              .method(DockerHostEntity::get)
              .invoke();
      if (record.exists()) {
        dockerHost =
            new CheckContext.DockerHostConfig(
                record.id(),
                record.str("name"),
                record.str("dockerDaemon"),
                record.str("dockerType"));
      }
    }

    CheckContext.ProxyConfig proxy = null;
    if (config.proxyId() != null && !config.proxyId().isEmpty()) {
      StoredRecord record =
          componentClient.forKeyValueEntity(config.proxyId()).method(ProxyEntity::get).invoke();
      if (record.exists() && record.flag("active")) {
        proxy =
            new CheckContext.ProxyConfig(
                record.id(),
                record.str("protocol"),
                record.str("host"),
                record.get("port") == null
                    ? 0
                    : (int) Double.parseDouble(String.valueOf(record.get("port"))),
                record.flag("auth"),
                record.str("username"),
                record.str("password"));
      }
    }

    String remoteBrowserUrl = null;
    if (config.remote_browser() != null && !config.remote_browser().isEmpty()) {
      StoredRecord record =
          componentClient
              .forKeyValueEntity(config.remote_browser())
              .method(RemoteBrowserEntity::get)
              .invoke();
      if (record.exists()) {
        remoteBrowserUrl = record.str("url");
      }
    }

    List<CheckContext.ChildStatus> children = new ArrayList<>();
    if ("group".equals(config.type())) {
      var rows = componentClient.forView().method(MonitorListView::children).invoke(config.id());
      for (MonitorListView.MonitorRow row : rows.monitors()) {
        children.add(
            new CheckContext.ChildStatus(
                row.id(), row.name(), row.active(), Status.of(row.lastStatus())));
      }
    }

    return new CheckContext(
        settings, dockerHost, proxy, remoteBrowserUrl, children, state.previousBeat(), now);
  }

  /**
   * Arm the next beat.
   *
   * <p>Always under the same name, so this replaces whatever was pending rather than adding to it —
   * which is what makes stopping a monitor, or changing its interval, take effect instead of
   * leaving an older beat alive alongside the new one.
   */
  private void arm(String monitorId, Duration delay) {
    timers.createSingleTimer(
        timerName(monitorId),
        delay,
        componentClient.forTimedAction().method(MonitorBeat::beat).deferred(new Beat(monitorId)));
  }
}
