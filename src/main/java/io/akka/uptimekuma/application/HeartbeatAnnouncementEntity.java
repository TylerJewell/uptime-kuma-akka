package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.uptimekuma.domain.Heartbeat;

/**
 * One beat, published so a client can be told about it.
 *
 * <p>It duplicates what the monitor already holds, and that is the point: a stream needs one row
 * per beat with a stable key to resume from, and an entity that holds a rolling window of five
 * hundred beats cannot offer that. The key is the monitor and the sequence together, so a beat is
 * published exactly once however many times its timer fires.
 */
@Component(id = "heartbeat-announcement")
public class HeartbeatAnnouncementEntity extends KeyValueEntity<HeartbeatAnnouncementEntity.Announcement> {

  /**
   * A beat and its place in the feed as a whole.
   *
   * <p>The beat's own {@code sequence} counts that monitor's beats, so two monitors beating at the
   * same moment carry the same number. A stream a client resumes needs a cursor that is unique
   * across every monitor and never goes backwards, which is what {@code feedSequence} is: one
   * counter for the whole feed, taken when the beat is published.
   */
  public record Announcement(long feedSequence, Heartbeat beat) {}

  /**
   * A beat's key: the monitor and the beat's own number.
   *
   * <p>The number has to be one that is never handed out twice, because a key-value entity cannot
   * be written after it has been deleted — and clearing a monitor's beats deletes these. A monitor
   * counts every beat it has ever taken rather than counting what is left in its window, so the
   * beat after a clear gets the next number rather than starting again at one. The counter that
   * makes that true is `MonitorEntity.State.beatsEverTaken`, and it is what this key rests on.
   */
  public static String key(String monitorId, long sequence) {
    return monitorId + ":" + sequence;
  }

  public Effect<String> publish(Announcement announcement) {
    return effects().updateState(announcement).thenReply("published");
  }

  public ReadOnlyEffect<Announcement> get() {
    return effects().reply(currentState());
  }

  /**
   * Change what a published beat says without moving it in the feed.
   *
   * <p>Clearing a monitor's events blanks every message and every importance flag while keeping
   * the beats themselves, so the row has to be rewritten in place: a fresh feed sequence would
   * send every one of them down the stream again as though it had just happened.
   */
  public Effect<String> amend(Heartbeat heartbeat) {
    long feedSequence = currentState() == null ? 0 : currentState().feedSequence();
    return effects().updateState(new Announcement(feedSequence, heartbeat)).thenReply("amended");
  }

  /** Forget a beat old enough that the retention setting says nobody will ask for it. */
  public Effect<String> forget() {
    return effects().deleteEntity().thenReply("forgotten");
  }
}
