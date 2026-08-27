package io.akka.uptimekuma.application;

/** Turning a stored row into the shape a list query returns. */
final class RecordRows {

  private RecordRows() {}

  /**
   * The key each kind is ordered by.
   *
   * <p>Every one of them is ordered by a name or a title in the interface, so whichever of those
   * the row carries is used and the identifier is the fallback — a row with neither still has a
   * stable position rather than an arbitrary one.
   */
  static RecordRow from(StoredRecord record) {
    String sortKey = record.str("name");
    if (sortKey == null) {
      sortKey = record.str("title");
    }
    if (sortKey == null) {
      sortKey = record.id();
    }
    String json;
    try {
      json = io.akka.uptimekuma.notifications.Json.MAPPER.writeValueAsString(record.toJson());
    } catch (Exception e) {
      json = "{}";
    }
    return new RecordRow(record.id(), sortKey, json, true);
  }
}
