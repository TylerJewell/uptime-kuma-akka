package io.akka.uptimekuma.checks;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a schema definition supplied as text, and encoding against it.
 *
 * <p>A grpc monitor carries the schema in a field rather than referring to a compiled one, so the
 * schema is not known until the beat runs. What is needed of it is narrow: the field names, numbers
 * and types of the request and reply messages, which is enough to turn the caller's JSON into the
 * bytes on the wire and the bytes back into JSON.
 *
 * <p>Scalars, strings, bytes, enumerations and nested messages, repeated or not. A schema using a
 * map field, a group, or one of the wrapper types fails by name rather than encoding something
 * wrong.
 */
final class Proto {

  private final Map<String, Message> messages = new LinkedHashMap<>();
  private final Map<String, Map<String, Integer>> enums = new LinkedHashMap<>();

  record Field(String name, int number, String type, boolean repeated) {}

  record Message(String name, List<Field> fields) {}

  private static final Pattern MESSAGE =
      Pattern.compile("message\\s+(\\w+)\\s*\\{(.*?)\\n\\s*\\}", Pattern.DOTALL);
  private static final Pattern ENUM =
      Pattern.compile("enum\\s+(\\w+)\\s*\\{(.*?)\\n\\s*\\}", Pattern.DOTALL);
  private static final Pattern FIELD =
      Pattern.compile("(repeated\\s+|optional\\s+|required\\s+)?([\\w.]+)\\s+(\\w+)\\s*=\\s*(\\d+)");
  private static final Pattern ENUM_VALUE = Pattern.compile("(\\w+)\\s*=\\s*(\\d+)");

  static Proto parse(String text) {
    Proto proto = new Proto();
    // Comments are stripped first so a commented-out field is not read as a real one.
    String cleaned = text.replaceAll("//[^\n]*", "").replaceAll("(?s)/\\*.*?\\*/", "");

    Matcher enumMatcher = ENUM.matcher(cleaned);
    while (enumMatcher.find()) {
      Map<String, Integer> values = new LinkedHashMap<>();
      Matcher valueMatcher = ENUM_VALUE.matcher(enumMatcher.group(2));
      while (valueMatcher.find()) {
        values.put(valueMatcher.group(1), Integer.valueOf(valueMatcher.group(2)));
      }
      proto.enums.put(enumMatcher.group(1), values);
    }

    Matcher messageMatcher = MESSAGE.matcher(cleaned);
    while (messageMatcher.find()) {
      String name = messageMatcher.group(1);
      List<Field> fields = new ArrayList<>();
      Matcher fieldMatcher = FIELD.matcher(messageMatcher.group(2));
      while (fieldMatcher.find()) {
        String modifier = fieldMatcher.group(1);
        fields.add(
            new Field(
                fieldMatcher.group(3),
                Integer.parseInt(fieldMatcher.group(4)),
                fieldMatcher.group(2),
                modifier != null && modifier.trim().equals("repeated")));
      }
      proto.messages.put(name, new Message(name, fields));
    }
    return proto;
  }

  Message message(String name) {
    String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    return messages.get(simple);
  }

  /** The reply type a service method declares, so a caller need not name it separately. */
  static String replyType(String text, String service, String method) {
    Matcher matcher =
        Pattern.compile(
                "rpc\\s+"
                    + Pattern.quote(method)
                    + "\\s*\\(\\s*(?:stream\\s+)?([\\w.]+)\\s*\\)\\s*returns\\s*\\(\\s*(?:stream\\s+)?([\\w.]+)\\s*\\)")
            .matcher(text);
    return matcher.find() ? matcher.group(2) : null;
  }

  static String requestType(String text, String service, String method) {
    Matcher matcher =
        Pattern.compile(
                "rpc\\s+"
                    + Pattern.quote(method)
                    + "\\s*\\(\\s*(?:stream\\s+)?([\\w.]+)\\s*\\)\\s*returns")
            .matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  byte[] encode(String messageName, Map<String, Object> value) throws CheckFailed {
    Message definition = message(messageName);
    if (definition == null) {
      throw new CheckFailed("The protobuf definition has no message named " + messageName);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Field field : definition.fields()) {
      Object supplied = value.get(field.name());
      if (supplied == null) {
        continue;
      }
      if (field.repeated() && supplied instanceof List<?> items) {
        for (Object item : items) {
          writeField(out, field, item);
        }
      } else {
        writeField(out, field, supplied);
      }
    }
    return out.toByteArray();
  }

  private void writeField(ByteArrayOutputStream out, Field field, Object value)
      throws CheckFailed {
    switch (field.type()) {
      case "string" -> {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        writeTag(out, field.number(), 2);
        writeVarint(out, bytes.length);
        out.writeBytes(bytes);
      }
      case "bytes" -> {
        byte[] bytes = java.util.Base64.getDecoder().decode(String.valueOf(value));
        writeTag(out, field.number(), 2);
        writeVarint(out, bytes.length);
        out.writeBytes(bytes);
      }
      case "bool" -> {
        writeTag(out, field.number(), 0);
        writeVarint(out, Boolean.parseBoolean(String.valueOf(value)) ? 1 : 0);
      }
      case "int32", "int64", "uint32", "uint64" -> {
        writeTag(out, field.number(), 0);
        writeVarint(out, Long.parseLong(String.valueOf(value)));
      }
      case "sint32", "sint64" -> {
        writeTag(out, field.number(), 0);
        long raw = Long.parseLong(String.valueOf(value));
        // Zig-zag, so a small negative number stays small on the wire.
        writeVarint(out, (raw << 1) ^ (raw >> 63));
      }
      case "double" -> {
        writeTag(out, field.number(), 1);
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putDouble(Double.parseDouble(String.valueOf(value)));
        out.writeBytes(buffer.array());
      }
      case "float" -> {
        writeTag(out, field.number(), 5);
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(Float.parseFloat(String.valueOf(value)));
        out.writeBytes(buffer.array());
      }
      default -> {
        Map<String, Integer> enumeration = enums.get(field.type());
        if (enumeration != null) {
          Integer ordinal =
              value instanceof Number n ? n.intValue() : enumeration.get(String.valueOf(value));
          if (ordinal == null) {
            throw new CheckFailed(
                "The value " + value + " is not one of the " + field.type() + " enumeration");
          }
          writeTag(out, field.number(), 0);
          writeVarint(out, ordinal);
          return;
        }
        Message nested = message(field.type());
        if (nested == null) {
          throw new CheckFailed(
              "The protobuf definition uses the type " + field.type() + ", which this port does "
                  + "not encode");
        }
        @SuppressWarnings("unchecked")
        byte[] encoded = encode(field.type(), (Map<String, Object>) value);
        writeTag(out, field.number(), 2);
        writeVarint(out, encoded.length);
        out.writeBytes(encoded);
      }
    }
  }

  /**
   * Turn wire bytes back into a readable object.
   *
   * <p>The schema is used for the field names; a field the schema does not describe is kept under
   * its number, so a reply from a newer server still shows everything it carried.
   */
  Map<String, Object> decode(String messageName, byte[] data) {
    Message definition = message(messageName);
    Map<String, Object> out = new LinkedHashMap<>();
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    while (buffer.hasRemaining()) {
      long tag = readVarint(buffer);
      int number = (int) (tag >>> 3);
      int wireType = (int) (tag & 0x07);
      Field field =
          definition == null
              ? null
              : definition.fields().stream()
                  .filter(candidate -> candidate.number() == number)
                  .findFirst()
                  .orElse(null);
      String key = field == null ? String.valueOf(number) : field.name();
      Object value =
          switch (wireType) {
            case 0 -> readVarint(buffer);
            case 1 -> buffer.getDouble();
            case 5 -> buffer.getFloat();
            case 2 -> {
              int length = (int) readVarint(buffer);
              byte[] bytes = new byte[length];
              buffer.get(bytes);
              if (field != null && message(field.type()) != null) {
                yield decode(field.type(), bytes);
              }
              yield new String(bytes, StandardCharsets.UTF_8);
            }
            default -> null;
          };
      if (field != null && field.repeated()) {
        @SuppressWarnings("unchecked")
        List<Object> existing = (List<Object>) out.computeIfAbsent(key, k -> new ArrayList<>());
        existing.add(value);
      } else {
        out.put(key, value);
      }
    }
    return out;
  }

  private static void writeTag(ByteArrayOutputStream out, int number, int wireType) {
    writeVarint(out, ((long) number << 3) | wireType);
  }

  private static void writeVarint(ByteArrayOutputStream out, long value) {
    long remaining = value;
    while (true) {
      if ((remaining & ~0x7FL) == 0) {
        out.write((int) remaining);
        return;
      }
      out.write((int) ((remaining & 0x7F) | 0x80));
      remaining >>>= 7;
    }
  }

  private static long readVarint(ByteBuffer buffer) {
    long value = 0;
    int shift = 0;
    while (buffer.hasRemaining()) {
      byte current = buffer.get();
      value |= (long) (current & 0x7F) << shift;
      if ((current & 0x80) == 0) {
        return value;
      }
      shift += 7;
    }
    return value;
  }
}
