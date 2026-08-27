package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/** One container daemon a docker monitor is asked of. */
@Component(id = "docker-host")
public class DockerHostEntity extends KeyValueEntity<StoredRecord> {

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
