package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.Optional;

/** Every API key. */
@Component(id = "api-key-list")
public class ApiKeyListView extends View {

  @Consume.FromKeyValueEntity(ApiKeyEntity.class)
  public static class apiKeys extends TableUpdater<RecordRow> {
    public Effect<RecordRow> onUpdate(StoredRecord record) {
      if (!record.exists()) {
        return effects().deleteRow();
      }
      return effects().updateRow(RecordRows.from(record));
    }
  }

  @Query("SELECT * AS entries FROM apiKeys ORDER BY sortKey")
  public QueryEffect<RecordRow.Rows> all() {
    return queryResult();
  }

  @Query("SELECT * FROM apiKeys WHERE id = :id")
  public QueryEffect<Optional<RecordRow>> byId(String id) {
    return queryResult();
  }
}
