package io.akka.uptimekuma.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The state transitions the ten smaller kinds share.
 *
 * <p>They are here rather than on a common entity class because the runtime counts an inherited
 * command handler and its override as two handlers of the same name and refuses the pair — so the
 * methods are declared per kind and only the state arithmetic is shared.
 */
final class RecordStates {

  private RecordStates() {}

  static StoredRecord replaced(String id, Map<String, Object> fields) {
    return new StoredRecord(id, new LinkedHashMap<>(fields), true);
  }

  /** Replace only the keys supplied, leaving the rest of the row as it was. */
  static StoredRecord merged(StoredRecord current, String id, Map<String, Object> fields) {
    Map<String, Object> merged = new LinkedHashMap<>(current.fields());
    merged.putAll(fields);
    return new StoredRecord(id, merged, true);
  }

  /**
   * Mark the row gone.
   *
   * <p>The state is replaced rather than the entity deleted, so a view built from it sees the
   * change and drops its own row — a deleted entity stops emitting and would leave a list showing
   * something that is no longer there.
   */
  static StoredRecord removed(String id) {
    return new StoredRecord(id, new LinkedHashMap<>(), false);
  }
}
