package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.Optional;

/** Every label. */
@Component(id = "tag-list")
public class TagListView extends View {

  @Consume.FromKeyValueEntity(TagEntity.class)
  public static class tags extends TableUpdater<RecordRow> {
    public Effect<RecordRow> onUpdate(StoredRecord record) {
      if (!record.exists()) {
        return effects().deleteRow();
      }
      return effects().updateRow(RecordRows.from(record));
    }
  }

  @Query("SELECT * AS entries FROM tags ORDER BY sortKey")
  public QueryEffect<RecordRow.Rows> all() {
    return queryResult();
  }

  @Query("SELECT * FROM tags WHERE id = :id")
  public QueryEffect<Optional<RecordRow>> byId(String id) {
    return queryResult();
  }
}
