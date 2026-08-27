package io.akka.uptimekuma.application;

import java.util.List;

/**
 * One row of one of the smaller kinds, as a list query returns it.
 *
 * @param json the whole row, serialised, so a view does not have to know the shape of a kind whose
 *     shape is the interface's business
 * @param sortKey what the interface orders that kind by. Kept as its own column because a query
 *     cannot order by something inside a serialised blob.
 */
public record RecordRow(String id, String sortKey, String json, boolean exists) {

  /**
   * A list of rows.
   *
   * <p>The field is not called {@code rows}: that word is reserved in the query language a view is
   * defined in, and a query that used it is refused when the service starts rather than when it is
   * called.
   */
  public record Rows(List<RecordRow> entries) {}
}
