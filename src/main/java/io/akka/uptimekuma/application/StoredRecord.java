package io.akka.uptimekuma.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of one of the smaller kinds: a notification, a proxy, a tag, an API key and the rest.
 *
 * <p>The fields are carried as a map rather than as a typed record on purpose. Every one of these
 * kinds is stored by the source as a loose row whose interesting half is a JSON blob, and the
 * interface posts back the whole object it was given — so a typed shape here would have to be kept
 * in step with a schema that has no server-side meaning, and a field it did not know about would be
 * dropped on the first edit.
 *
 * @param exists false for a row that was never created or has been deleted, so a caller can tell
 *     an empty record from a missing one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredRecord(String id, Map<String, Object> fields, boolean exists) {

  public static StoredRecord empty() {
    return new StoredRecord(null, new LinkedHashMap<>(), false);
  }

  public StoredRecord with(String key, Object value) {
    Map<String, Object> next = new LinkedHashMap<>(fields);
    next.put(key, value);
    return new StoredRecord(id, next, exists);
  }

  public Object get(String key) {
    return fields.get(key);
  }

  public String str(String key) {
    Object value = fields.get(key);
    return value == null ? null : String.valueOf(value);
  }

  public boolean flag(String key) {
    Object value = fields.get(key);
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.intValue() != 0;
    }
    return "true".equals(String.valueOf(value)) || "1".equals(String.valueOf(value));
  }

  /** The row as the interface reads it, with the identifier folded in under its own key. */
  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>(fields);
    json.put("id", id);
    return json;
  }
}
