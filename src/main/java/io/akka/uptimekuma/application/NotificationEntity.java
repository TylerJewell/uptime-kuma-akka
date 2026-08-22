package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.uptimekuma.domain.NotificationTarget;

/** Where one notification goes. */
@Component(id = "notification")
public class NotificationEntity extends KeyValueEntity<NotificationTarget> {

  /**
   * A lookup's answer. Wrapped because a key-value entity that has never been written has null
   * state, and replying with that is a runtime failure rather than an absence the caller can read —
   * which matters here, since the fan-out has to distinguish "this target refused" from "there is
   * no such target".
   */
  public record Lookup(NotificationTarget target) {}

  public Effect<String> put(NotificationTarget target) {
    return effects().updateState(target).thenReply("ok");
  }

  public ReadOnlyEffect<Lookup> get() {
    return effects().reply(new Lookup(currentState()));
  }
}
