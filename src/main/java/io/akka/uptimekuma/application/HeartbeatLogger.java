package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.uptimekuma.domain.HeartbeatAnnouncement;

/**
 * Turns every beat a monitor records into one durable, addressable announcement, so a GUI
 * client can subscribe to the state this port's slice owns — RENDERING.md R7.
 */
@Component(id = "heartbeat-logger")
@Consume.FromEventSourcedEntity(MonitorEntity.class)
public class HeartbeatLogger extends Consumer {

  private final ComponentClient componentClient;

  public HeartbeatLogger(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(MonitorEntity.Event event) {
    if (!(event instanceof MonitorEntity.Event.BeatRecorded recorded)) {
      return effects().ignore();
    }
    String monitorId = messageContext().eventSubject().orElseThrow();
    var heartbeat = recorded.heartbeat();
    var announcement =
        new HeartbeatAnnouncement(
            monitorId,
            heartbeat.sequence(),
            heartbeat.status(),
            heartbeat.important(),
            heartbeat.notified(),
            heartbeat.retries(),
            heartbeat.downCount(),
            heartbeat.message(),
            java.util.Optional.ofNullable(heartbeat.pingMillis()),
            heartbeat.atEpochMillis());
    componentClient
        .forKeyValueEntity(HeartbeatAnnouncementEntity.idFor(monitorId, heartbeat.sequence()))
        .method(HeartbeatAnnouncementEntity::put)
        .invoke(announcement);
    return effects().done();
  }
}
