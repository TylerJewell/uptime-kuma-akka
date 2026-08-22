package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.uptimekuma.domain.HeartbeatAnnouncement;
import java.util.List;

/**
 * The GUI's subscribable feed of heartbeats — RENDERING.md R1, R7. Two ways to read it, because a
 * resumable stream is two different jobs: {@link #replay} hands back, in order, what a client
 * missed while it was away; {@link #stream} and {@link #streamAll} push what happens from there
 * on, so a client is never asking whether anything changed.
 *
 * <p>A streaming query may not carry an ORDER BY, which is why the ordered half is the plain
 * query and not this one.
 */
@Component(id = "heartbeat-feed")
public class HeartbeatFeedView extends View {

  @Consume.FromKeyValueEntity(HeartbeatAnnouncementEntity.class)
  public static class Feed extends TableUpdater<HeartbeatAnnouncement> {
    public Effect<HeartbeatAnnouncement> onUpdate(HeartbeatAnnouncement announcement) {
      return effects().updateRow(announcement);
    }
  }

  public record From(String monitorId, long sinceSequence) {}

  public record Announcements(List<HeartbeatAnnouncement> items) {}

  @Query(
      "SELECT * AS items FROM feed WHERE monitorId = :monitorId AND sequence > :sinceSequence "
          + "ORDER BY sequence")
  public QueryEffect<Announcements> replay(From from) {
    return queryResult();
  }

  @Query(
      value = "SELECT * FROM feed WHERE monitorId = :monitorId AND sequence > :sinceSequence",
      streamUpdates = true)
  public QueryStreamEffect<HeartbeatAnnouncement> stream(From from) {
    return queryStreamResult();
  }

  /** Every monitor at once — what the dashboard's own screen subscribes to. */
  @Query(value = "SELECT * FROM feed", streamUpdates = true)
  public QueryStreamEffect<HeartbeatAnnouncement> streamAll() {
    return queryStreamResult();
  }
}
