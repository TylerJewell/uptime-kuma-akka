package io.akka.uptimekuma.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields of one row, as a command carries them.
 *
 * <p>A command handler may not take a generic type — the runtime cannot tell what to deserialise a
 * message into when the parameter is a map of something to something — so the map travels inside a
 * record instead. The refusal is at runtime rather than at compile time, which is why this exists
 * as a named thing rather than as a plain parameter.
 */
public record RecordFields(Map<String, Object> values) {

  public static RecordFields of(Map<String, Object> values) {
    return new RecordFields(new LinkedHashMap<>(values));
  }
}
