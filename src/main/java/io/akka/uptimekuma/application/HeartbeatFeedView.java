package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.uptimekuma.domain.Heartbeat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The interface's subscribable feed of heartbeats — RENDERING.md R1.
 *
 * <p>Two ways to read it, because a resumable stream is two different jobs: {@link #replay} hands
 * back, in order, what a client missed while it was away, and {@link #stream} pushes what happens
 * from there on, so a client is never asking whether anything changed.
 *
 * <p>A streaming query may not carry an ordering clause, which is why the ordered half is the plain
 * query and not this one.
 */
@Component(id = "heartbeat-feed")
public class HeartbeatFeedView extends View {

  /**
   * A beat as this view stores it.
   *
   * <p>Three of its fields are genuinely absent on some beats — a failed check measures no response
   * time, a beat that was not asked to keep the body has none, and a beat carries no end time until
   * the statistics have folded it in. A row field that is sometimes absent has to say so: the
   * runtime refuses the whole row otherwise, the stream that writes it restarts in a loop, and
   * every query against the view then answers empty rather than answering with an error.
   *
   * <p>So this is its own type rather than the domain record, which keeps the plain nullable fields
   * — an entity's state tolerates a null a view row does not.
   */
  public record HeartbeatRow(
      long feedSequence,
      long sequence,
      String monitorId,
      boolean important,
      int status,
      String msg,
      long timeEpochMillis,
      Optional<Double> ping,
      int duration,
      int downCount,
      int retries,
      Optional<Long> endTimeEpochMillis,
      Optional<String> response) {

    static HeartbeatRow from(HeartbeatAnnouncementEntity.Announcement announcement) {
      Heartbeat beat = announcement.beat();
      return new HeartbeatRow(
          announcement.feedSequence(),
          beat.sequence(),
          beat.monitorId(),
          beat.important(),
          beat.status(),
          beat.msg() == null ? "" : beat.msg(),
          beat.timeEpochMillis(),
          Optional.ofNullable(beat.ping()),
          beat.duration(),
          beat.downCount(),
          beat.retries(),
          Optional.ofNullable(beat.endTimeEpochMillis()),
          Optional.ofNullable(beat.response()));
    }

    public Heartbeat toHeartbeat() {
      return new Heartbeat(
          sequence,
          monitorId,
          important,
          status,
          msg,
          timeEpochMillis,
          ping.orElse(null),
          duration,
          downCount,
          retries,
          endTimeEpochMillis.orElse(null),
          response.orElse(null));
    }
  }

  @Consume.FromKeyValueEntity(HeartbeatAnnouncementEntity.class)
  public static class Feed extends TableUpdater<HeartbeatRow> {
    public Effect<HeartbeatRow> onUpdate(HeartbeatAnnouncementEntity.Announcement announcement) {
      return effects().updateRow(HeartbeatRow.from(announcement));
    }
  }

  public record From(String monitorId, long sinceSequence) {}

  public record Beats(List<HeartbeatRow> items) {

    /** The beats as the rest of the system reads them. */
    public List<Heartbeat> heartbeats() {
      List<Heartbeat> out = new ArrayList<>();
      for (HeartbeatRow row : items) {
        out.add(row.toHeartbeat());
      }
      return out;
    }
  }

  @Query(
      "SELECT * AS items FROM feed WHERE monitorId = :monitorId AND sequence > :sinceSequence "
          + "ORDER BY sequence")
  public QueryEffect<Beats> replay(From from) {
    return queryResult();
  }

  /** The window the interface draws, newest first. */
  @Query(
      "SELECT * AS items FROM feed WHERE monitorId = :monitorId ORDER BY sequence DESC LIMIT 100")
  public QueryEffect<Beats> recent(String monitorId) {
    return queryResult();
  }

  /**
   * The beats old enough to be forgotten, oldest first.
   *
   * <p>Bounded, because a year of a twenty-second monitor is a million and a half rows and the
   * job that reads this deletes them one at a time. Whatever is left waits for the next pass.
   */
  @Query(
      "SELECT * AS items FROM feed WHERE timeEpochMillis < :beforeEpochMillis "
          + "ORDER BY timeEpochMillis LIMIT 5000")
  public QueryEffect<Beats> expired(long beforeEpochMillis) {
    return queryResult();
  }

  /**
   * The events table, newest first.
   *
   * <p>Ordered on the instant of the beat rather than on its sequence, because this list spans
   * every monitor and a sequence counts one monitor's beats: two monitors' first beats are both
   * number one, so a sequence ordering puts them in no particular order and the table reads as
   * though it were sorted by monitor. The feed sequence breaks a tie on the instant, which the
   * ordering the source uses leaves to its database.
   */
  @Query(
      "SELECT * AS items FROM feed WHERE important = true "
          + "ORDER BY timeEpochMillis DESC, feedSequence DESC")
  public QueryEffect<Beats> important() {
    return queryResult();
  }

  @Query(
      "SELECT * AS items FROM feed WHERE monitorId = :monitorId AND important = true "
          + "ORDER BY timeEpochMillis DESC, feedSequence DESC")
  public QueryEffect<Beats> importantFor(String monitorId) {
    return queryResult();
  }

  @Query(
      value = "SELECT * FROM feed WHERE monitorId = :monitorId AND sequence > :sinceSequence",
      streamUpdates = true)
  public QueryStreamEffect<HeartbeatRow> stream(From from) {
    return queryStreamResult();
  }

  /** Every monitor at once, which is what the dashboard subscribes to. */
  /**
   * Every monitor at once, from a point in the feed onwards — what the interface subscribes to.
   *
   * <p>The filter is what makes this a subscription rather than a replay. Unfiltered, a streaming
   * query hands over every row the view already holds before it reaches the live ones, and a client
   * that was given the same beats as a list moments earlier draws each of them twice. A client with
   * no history to resume passes the feed's current end, and so is sent nothing it already has.
   */
  @Query(value = "SELECT * FROM feed WHERE feedSequence > :sinceFeedSequence", streamUpdates = true)
  public QueryStreamEffect<HeartbeatRow> streamAll(Since since) {
    return queryStreamResult();
  }

  public record Since(long sinceFeedSequence) {}

  /** What a client that lost the stream missed, in the order it happened. */
  @Query("SELECT * AS items FROM feed WHERE feedSequence > :sinceFeedSequence ORDER BY feedSequence")
  public QueryEffect<Beats> replayFeed(Since since) {
    return queryResult();
  }
}
