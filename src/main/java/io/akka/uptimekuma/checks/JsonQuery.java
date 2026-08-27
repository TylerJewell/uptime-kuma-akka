package io.akka.uptimekuma.checks;

import com.dashjoin.jsonata.Jsonata;
import java.util.List;
import java.util.Map;

/**
 * The json-query monitor type's evaluation.
 *
 * <p>Two expressions, not one: the stored path is a JSONata expression that reduces the response to
 * a single value, and the comparison against the expected value is a second JSONata expression
 * built from the chosen operator. That second step matters — the comparison is done on the string
 * forms of both sides, so {@code 5} and {@code "5"} are equal under {@code ==} and the ordering
 * operators coerce back to numbers.
 */
final class JsonQuery {

  private JsonQuery() {}

  /**
   * @param status whether the comparison held
   * @param response the value the path reduced the body to, which both messages quote
   */
  record Result(boolean status, Object response) {}

  static Result evaluate(String data, String jsonPath, String operator, String expectedValue)
      throws CheckFailed {
    Object response;
    try {
      response = Jsonata.jsonata("$").evaluate(parse(data));
    } catch (Exception e) {
      response = data;
    }

    try {
      if (jsonPath != null && !jsonPath.isEmpty()) {
        response = Jsonata.jsonata(jsonPath).evaluate(parse(data));
      }
      if (response == null) {
        throw new IllegalStateException(
            "Empty or undefined response. Check query syntax and response structure");
      }
      if (response instanceof List<?> list) {
        String rendered = Json.write(list);
        String truncated =
            rendered.length() > 25 ? rendered.substring(0, 25) + "...]" : rendered;
        throw new IllegalStateException(
            "JSON query returned the array "
                + truncated
                + ", but a primitive value is required. "
                + "Modify your query to return a single value via [0] to get the first element or "
                + "use an aggregation like $count(), $sum() or $boolean().");
      }
      if (response instanceof Map<?, ?>) {
        throw new IllegalStateException(
            "The post-JSON query evaluated response from the server is of type object, which "
                + "cannot be directly compared to the expected value");
      }

      String comparison =
          switch (operator == null ? "" : operator) {
            case ">", ">=", "<", "<=" ->
                "$number($.value) " + operator + " $number($.expected)";
            case "!=" -> "$.value != $.expected";
            case "==" -> "$.value = $.expected";
            case "contains" -> "$contains($.value, $.expected)";
            default -> throw new IllegalStateException("Invalid condition " + operator);
          };

      Map<String, Object> operands =
          Map.of(
              "value",
              String.valueOf(response),
              "expected",
              expectedValue == null ? "null" : expectedValue);
      Object status = Jsonata.jsonata(comparison).evaluate(operands);
      if (status == null) {
        throw new IllegalStateException(
            "Query evaluation returned undefined. Check query syntax and the structure of the "
                + "response data");
      }
      return new Result(Boolean.TRUE.equals(status), response);
    } catch (Exception e) {
      String rendered = Json.write(response);
      // The body is quoted back so a failing expression can be read against what it was given,
      // truncated because a response can be a megabyte.
      if (rendered != null && rendered.length() > 50) {
        rendered = rendered.substring(0, Math.min(100, rendered.length())) + "… (truncated)";
      }
      throw new CheckFailed(
          "Error evaluating JSON query: "
              + e.getMessage()
              + ". Response from server was: "
              + rendered);
    }
  }

  private static Object parse(String data) {
    try {
      return Json.MAPPER.readValue(data, Object.class);
    } catch (Exception e) {
      return data;
    }
  }

  /** A small holder so this file does not reach into the notification package for a mapper. */
  static final class Json {
    private Json() {}

    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    static String write(Object value) {
      try {
        return MAPPER.writeValueAsString(value);
      } catch (Exception e) {
        return String.valueOf(value);
      }
    }
  }
}
