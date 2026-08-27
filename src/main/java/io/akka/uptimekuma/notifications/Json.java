package io.akka.uptimekuma.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Building the JSON body a provider sends.
 *
 * <p>Insertion order is preserved, because a body is compared against the source's byte for byte
 * and JavaScript object literals serialise in the order they are written.
 */
public final class Json {

  private Json() {}

  public static final ObjectMapper MAPPER = new ObjectMapper();

  /** An ordered map builder, so a body reads in the same order the source writes it. */
  public static final class Obj {
    private final Map<String, Object> fields = new LinkedHashMap<>();

    public Obj put(String key, Object value) {
      fields.put(key, value);
      return this;
    }

    /** Add the key only when the value is neither null nor an empty string. */
    public Obj putIfPresent(String key, Object value) {
      if (value != null && !"".equals(value)) {
        fields.put(key, value);
      }
      return this;
    }

    public Obj putIf(boolean condition, String key, Object value) {
      if (condition) {
        fields.put(key, value);
      }
      return this;
    }

    public Map<String, Object> map() {
      return fields;
    }

    @Override
    public String toString() {
      return write(fields);
    }
  }

  public static Obj obj() {
    return new Obj();
  }

  public static List<Object> array(Object... items) {
    return new ArrayList<>(List.of(items));
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialise notification body", e);
    }
  }
}
