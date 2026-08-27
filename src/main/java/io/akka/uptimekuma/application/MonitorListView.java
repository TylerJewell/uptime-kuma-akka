package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.util.List;
import java.util.Optional;

/**
 * Every monitor, for the list the interface renders before any beat arrives.
 *
 * <p>The row carries the whole configuration as JSON rather than a chosen subset, because the
 * interface reads the monitor object field by field on four different screens and a field left out
 * here is a screen that breaks on an absent property rather than one that renders without it.
 *
 * <p>Two fields are their own columns as well: the parent, so a group's children can be found
 * without reading every row, and the push token, so an incoming push can be routed without a scan.
 */
@Component(id = "monitor-list")
public class MonitorListView extends View {

  /**
   * @param json the whole monitor, serialised
   * @param pushToken carried as its own column and left empty rather than null for a monitor of
   *     another type: a view row field that is sometimes absent has to be optional or the row
   *     cannot be written at all
   */
  public record MonitorRow(
      String id,
      String name,
      String type,
      boolean active,
      boolean underMaintenance,
      int weight,
      String parent,
      String pushToken,
      String json,
      int lastStatus,
      long lastBeatEpochMillis,
      Optional<Double> lastPing) {}

  @Consume.FromEventSourcedEntity(MonitorEntity.class)
  public static class Monitors extends TableUpdater<MonitorRow> {

    @Override
    public MonitorRow emptyRow() {
      return new MonitorRow("", "", "", false, false, 0, "", "", "{}", 2, 0, Optional.empty());
    }

    public Effect<MonitorRow> onEvent(MonitorEntity.Event event) {
      String id = updateContext().eventSubject().orElseThrow();
      MonitorRow row = rowState();
      return switch (event) {
        case MonitorEntity.Event.Created e -> effects().updateRow(from(id, e.config(), row));
        case MonitorEntity.Event.Reconfigured e -> effects().updateRow(from(id, e.config(), row));
        case MonitorEntity.Event.Started ignored -> effects().updateRow(withActive(row, true));
        case MonitorEntity.Event.Stopped ignored -> effects().updateRow(withActive(row, false));
        case MonitorEntity.Event.MaintenanceSet e ->
            effects()
                .updateRow(
                    new MonitorRow(
                        row.id(),
                        row.name(),
                        row.type(),
                        row.active(),
                        e.under(),
                        row.weight(),
                        row.parent(),
                        row.pushToken(),
                        row.json(),
                        row.lastStatus(),
                        row.lastBeatEpochMillis(),
                        row.lastPing()));
        case MonitorEntity.Event.BeatRecorded e ->
            effects()
                .updateRow(
                    new MonitorRow(
                        row.id(),
                        row.name(),
                        row.type(),
                        row.active(),
                        row.underMaintenance(),
                        row.weight(),
                        row.parent(),
                        row.pushToken(),
                        row.json(),
                        e.heartbeat().status(),
                        e.heartbeat().timeEpochMillis(),
                        Optional.ofNullable(e.heartbeat().ping())));
        case MonitorEntity.Event.Deleted ignored -> effects().deleteRow();
        default -> effects().ignore();
      };
    }

    private static MonitorRow from(String id, MonitorConfig config, MonitorRow previous) {
      String json;
      try {
        json =
            io.akka.uptimekuma.notifications.Json.MAPPER.writeValueAsString(
                config.toBuilder().id(id).build());
      } catch (Exception e) {
        json = "{}";
      }
      return new MonitorRow(
          id,
          config.name() == null ? "" : config.name(),
          config.type(),
          config.active(),
          config.maintenance(),
          config.weight(),
          config.parent() == null ? "" : config.parent(),
          config.pushToken() == null ? "" : config.pushToken(),
          json,
          previous.lastStatus(),
          previous.lastBeatEpochMillis(),
          previous.lastPing());
    }

    private static MonitorRow withActive(MonitorRow row, boolean active) {
      return new MonitorRow(
          row.id(),
          row.name(),
          row.type(),
          active,
          row.underMaintenance(),
          row.weight(),
          row.parent(),
          row.pushToken(),
          row.json(),
          row.lastStatus(),
          row.lastBeatEpochMillis(),
          row.lastPing());
    }
  }

  public record Monitors_(List<MonitorRow> monitors) {}

  /** Heaviest first, then by name, which is the order the interface's list expects. */
  @Query("SELECT * AS monitors FROM monitors ORDER BY weight DESC, name")
  public QueryEffect<Monitors_> all() {
    return queryResult();
  }

  @Query("SELECT * AS monitors FROM monitors WHERE parent = :parent ORDER BY weight DESC, name")
  public QueryEffect<Monitors_> children(String parent) {
    return queryResult();
  }

  /**
   * Every monitor holding a push token, which is not always one.
   *
   * <p>Nothing stops two monitors being given the same token — the interface generates one but a
   * person can type it, and cloning a monitor copies it. Asked for a single row, the view refuses
   * the whole query when a second one exists, and the push route then answers every caller with a
   * failure instead of recording a beat. The source's own lookup takes the first row it finds.
   */
  @Query("SELECT * AS monitors FROM monitors WHERE pushToken = :pushToken")
  public QueryEffect<Monitors_> byPushToken(String pushToken) {
    return queryResult();
  }

  @Query("SELECT * FROM monitors WHERE id = :id")
  public QueryEffect<Optional<MonitorRow>> byId(String id) {
    return queryResult();
  }
}
