package io.akka.uptimekuma.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.uptimekuma.domain.HeartbeatAnnouncement;

/**
 * One durable, addressable heartbeat announcement — the row a GUI client's stream resumes from.
 *
 * <p>The id is {@code <monitorId>@<sequence>}, derived from the event that caused it, so the same
 * beat delivered twice (a timer redelivery — SPEC-001 §4 OD2) rewrites the same row rather than
 * making a second announcement.
 */
@Component(id = "heartbeat-announcement")
public class HeartbeatAnnouncementEntity extends KeyValueEntity<HeartbeatAnnouncement> {

  public static String idFor(String monitorId, long sequence) {
    return monitorId + "@" + sequence;
  }

  public Effect<Done> put(HeartbeatAnnouncement announcement) {
    return effects().updateState(announcement).thenReply(Done.getInstance());
  }
}
