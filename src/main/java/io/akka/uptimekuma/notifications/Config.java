package io.akka.uptimekuma.notifications;

import java.util.List;
import java.util.Map;

/**
 * A notification's stored configuration.
 *
 * <p>The source stores the whole settings object as one JSON string on the notification row, and
 * every provider reads whichever keys it wants out of it — there is no schema. That shape is kept,
 * because a notification carried over from the source has to be readable here.
 */
public record Config(Map<String, Object> values) {

  public String str(String key) {
    Object value = values.get(key);
    return value == null ? null : String.valueOf(value);
  }

  public String str(String key, String fallback) {
    String value = str(key);
    return value == null || value.isEmpty() ? fallback : value;
  }

  /**
   * JavaScript truthiness, because that is what every {@code if (notification.x)} in the source
   * means: absent, null, false, zero and the empty string are all false.
   */
  public boolean truthy(String key) {
    Object value = values.get(key);
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    return !String.valueOf(value).isEmpty() && !"false".equals(String.valueOf(value));
  }

  public Integer intOf(String key) {
    Object value = values.get(key);
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public int intOf(String key, int fallback) {
    Integer value = intOf(key);
    return value == null ? fallback : value;
  }

  public Object raw(String key) {
    return values.get(key);
  }

  @SuppressWarnings("unchecked")
  public List<Object> list(String key) {
    Object value = values.get(key);
    return value instanceof List<?> l ? (List<Object>) l : null;
  }

  public String name() {
    return str("name");
  }

  public String type() {
    return str("type");
  }
}
