package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.util.List;

/**
 * Every monitor's configuration, for the GUI's initial render — RENDERING.md R1.4, R7. Status is
 * deliberately not carried here: the interface derives it from the heartbeat feed, the same way
 * the source derives it from the heartbeat list rather than from the monitor row.
 */
@Component(id = "monitor-summary")
public class MonitorSummaryView extends View {

  /**
   * The cadence fields are carried because the interface shows them — "check every N seconds"
   * on a monitor's own screen is this slice's state, not configuration the screen happens to
   * have lying around.
   */
  public record MonitorEntry(
      String id,
      String url,
      boolean active,
      boolean underMaintenance,
      int intervalSeconds,
      int retryIntervalSeconds,
      int maxRetries,
      int resendIntervalSeconds) {}

  @Consume.FromEventSourcedEntity(MonitorEntity.class)
  public static class Summaries extends TableUpdater<MonitorEntry> {

    @Override
    public MonitorEntry emptyRow() {
      return new MonitorEntry("", "", false, false, 0, 0, 0, 0);
    }

    public Effect<MonitorEntry> onEvent(MonitorEntity.Event event) {
      String id = updateContext().eventSubject().orElseThrow();
      MonitorEntry row = rowState();
      return switch (event) {
        case MonitorEntity.Event.Created e -> effects().updateRow(from(id, e.config(), false, false));
        case MonitorEntity.Event.Reconfigured e ->
            effects().updateRow(from(id, e.config(), row.active(), row.underMaintenance()));
        case MonitorEntity.Event.Started ignored ->
            effects().updateRow(withFlags(row, true, row.underMaintenance()));
        case MonitorEntity.Event.Stopped ignored ->
            effects().updateRow(withFlags(row, false, row.underMaintenance()));
        case MonitorEntity.Event.MaintenanceSet e ->
            effects().updateRow(withFlags(row, row.active(), e.under()));
        case MonitorEntity.Event.BeatRecorded ignored -> effects().ignore();
      };
    }
  }

  private static MonitorEntry from(
      String id, MonitorConfig config, boolean active, boolean underMaintenance) {
    return new MonitorEntry(
        id,
        config.url(),
        active,
        underMaintenance,
        config.intervalSeconds(),
        config.retryIntervalSeconds(),
        config.maxRetries(),
        config.resendIntervalSeconds());
  }

  private static MonitorEntry withFlags(MonitorEntry row, boolean active, boolean underMaintenance) {
    return new MonitorEntry(
        row.id(),
        row.url(),
        active,
        underMaintenance,
        row.intervalSeconds(),
        row.retryIntervalSeconds(),
        row.maxRetries(),
        row.resendIntervalSeconds());
  }

  public record SummaryList(List<MonitorEntry> monitors) {}

  @Query("SELECT * AS monitors FROM summaries ORDER BY id")
  public QueryEffect<SummaryList> list() {
    return queryResult();
  }
}
