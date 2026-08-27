package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.Optional;

/** Every container daemon. */
@Component(id = "docker-host-list")
public class DockerHostListView extends View {

  @Consume.FromKeyValueEntity(DockerHostEntity.class)
  public static class dockerHosts extends TableUpdater<RecordRow> {
    public Effect<RecordRow> onUpdate(StoredRecord record) {
      if (!record.exists()) {
        return effects().deleteRow();
      }
      return effects().updateRow(RecordRows.from(record));
    }
  }

  @Query("SELECT * AS entries FROM dockerHosts ORDER BY sortKey")
  public QueryEffect<RecordRow.Rows> all() {
    return queryResult();
  }

  @Query("SELECT * FROM dockerHosts WHERE id = :id")
  public QueryEffect<Optional<RecordRow>> byId(String id) {
    return queryResult();
  }
}
