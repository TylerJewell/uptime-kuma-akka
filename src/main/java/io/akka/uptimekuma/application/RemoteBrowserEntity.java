package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/** One browser a real-browser monitor is driven through. */
@Component(id = "remote-browser")
public class RemoteBrowserEntity extends KeyValueEntity<StoredRecord> {

  @Override
  public StoredRecord emptyState() {
    return StoredRecord.empty();
  }

  public Effect<String> put(RecordFields fields) {
    return effects()
        .updateState(RecordStates.replaced(commandContext().entityId(), fields.values()))
        .thenReply("saved");
  }

  public Effect<String> patch(RecordFields fields) {
    return effects()
        .updateState(RecordStates.merged(currentState(), commandContext().entityId(), fields.values()))
        .thenReply("saved");
  }

  public ReadOnlyEffect<StoredRecord> get() {
    return effects().reply(currentState());
  }

  public Effect<String> delete() {
    return effects()
        .updateState(RecordStates.removed(commandContext().entityId()))
        .thenReply("deleted");
  }
}
