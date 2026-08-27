package io.akka.uptimekuma.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The condition tree a monitor can be given, and how it is read.
 *
 * <p>A tree is a list of nodes, each either an {@code expression} — a variable, an operator and a
 * value — or a {@code group} holding more nodes. Every node also carries its own {@code andOr},
 * which is how it joins the accumulated result of everything before it.
 *
 * <p>There is no operator precedence. The first child seeds the result and each later child folds
 * itself in with its own connective, left to right, so {@code a OR b AND c} reads as {@code (a OR
 * b) AND c} rather than the other way round. That is the source's evaluator and it is what a stored
 * tree was authored against.
 */
public final class Conditions {

  private Conditions() {}

  /** The operators, by the id stored in a condition. */
  public static final List<String> STRING_OPERATORS =
      List.of(
          "equals",
          "not_equals",
          "contains",
          "not_contains",
          "starts_with",
          "not_starts_with",
          "ends_with",
          "not_ends_with");

  public static final List<String> NUMBER_OPERATORS =
      List.of("num_equals", "num_not_equals", "lt", "gt", "lte", "gte");

  /** The caption the interface shows beside each operator id. */
  public static String caption(String operatorId) {
    return switch (operatorId) {
      case "equals", "num_equals" -> "equals";
      case "not_equals", "num_not_equals" -> "not equals";
      case "contains" -> "contains";
      case "not_contains" -> "not contains";
      case "starts_with" -> "starts with";
      case "not_starts_with" -> "not starts with";
      case "ends_with" -> "ends with";
      case "not_ends_with" -> "not ends with";
      case "lt" -> "less than";
      case "gt" -> "greater than";
      case "lte" -> "less than or equal to";
      case "gte" -> "greater than or equal to";
      default -> operatorId;
    };
  }

  /** The variables each type offers, in the order the interface lists them. */
  public static List<String> variablesFor(String monitorType) {
    return switch (monitorType) {
      case "dns" -> List.of("record");
      case "mqtt" -> List.of("topic", "message", "json_value");
      case "sqlserver", "mysql", "oracledb" -> List.of("result");
      default -> List.of();
    };
  }

  /** The operators offered for one variable of one type. */
  public static List<String> operatorsFor(String monitorType, String variable) {
    if ("mqtt".equals(monitorType) && "json_value".equals(variable)) {
      List<String> both = new ArrayList<>(STRING_OPERATORS);
      both.addAll(NUMBER_OPERATORS);
      return List.copyOf(both);
    }
    return STRING_OPERATORS;
  }

  /**
   * Read one operator.
   *
   * @param variable the value in the context, as a string for the string operators and as read for
   *     the numeric ones
   * @throws IllegalArgumentException if the operator id is not one of the fourteen
   */
  public static boolean apply(String operatorId, Object variable, String value) {
    return switch (operatorId) {
      case "equals" -> asString(variable).equals(value);
      case "not_equals" -> !asString(variable).equals(value);
      case "contains" -> containsValue(variable, value);
      case "not_contains" -> !containsValue(variable, value);
      case "starts_with" -> asString(variable).startsWith(value);
      case "not_starts_with" -> !asString(variable).startsWith(value);
      case "ends_with" -> asString(variable).endsWith(value);
      case "not_ends_with" -> !asString(variable).endsWith(value);
      case "num_equals" -> numeric(variable) != null && numeric(variable).equals(toNumber(value));
      case "num_not_equals" ->
          !(numeric(variable) != null && numeric(variable).equals(toNumber(value)));
      case "lt" -> ordered(variable, value, c -> c < 0);
      case "gt" -> ordered(variable, value, c -> c > 0);
      case "lte" -> ordered(variable, value, c -> c <= 0);
      case "gte" -> ordered(variable, value, c -> c >= 0);
      default ->
          throw new IllegalArgumentException(
              "Unexpected expression operator ID '"
                  + operatorId
                  + "'. Expected one of "
                  + allOperators());
    };
  }

  private static List<String> allOperators() {
    List<String> all = new ArrayList<>(STRING_OPERATORS);
    all.addAll(NUMBER_OPERATORS);
    return all;
  }

  /**
   * Read a whole tree against a context of variable values.
   *
   * @param nodes the stored condition list
   * @param context variable id to value
   * @throws IllegalArgumentException if the list is empty, or names a variable the context does not
   *     carry
   */
  public static boolean evaluate(List<Map<String, Object>> nodes, Map<String, Object> context) {
    if (nodes == null || nodes.isEmpty()) {
      throw new IllegalArgumentException("ConditionExpressionGroup must contain at least one child.");
    }
    Boolean result = null;
    for (Map<String, Object> node : nodes) {
      boolean value = evaluateNode(node, context);
      if (result == null) {
        result = value;
      } else if ("or".equals(node.get("andOr"))) {
        result = result || value;
      } else {
        result = result && value;
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static boolean evaluateNode(Map<String, Object> node, Map<String, Object> context) {
    if ("group".equals(node.get("type"))) {
      Object children = node.get("children");
      return evaluate((List<Map<String, Object>>) children, context);
    }
    String variable = (String) node.get("variable");
    if (!context.containsKey(variable)) {
      throw new IllegalArgumentException("Variable missing in context: " + variable);
    }
    String value = node.get("value") == null ? "" : String.valueOf(node.get("value"));
    return apply((String) node.get("operator"), context.get(variable), value);
  }

  private static String asString(Object variable) {
    return variable == null ? "" : String.valueOf(variable);
  }

  @SuppressWarnings("unchecked")
  private static boolean containsValue(Object variable, String value) {
    if (variable instanceof List<?> list) {
      return ((List<Object>) list).stream().anyMatch(item -> String.valueOf(item).equals(value));
    }
    return asString(variable).contains(value);
  }

  private static Double numeric(Object variable) {
    if (variable instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return Double.valueOf(String.valueOf(variable));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Double toNumber(String value) {
    try {
      return Double.valueOf(value);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /**
   * The four ordering operators.
   *
   * <p>A variable that is not a number is unordered against one, and all four answer false — which
   * is what the source's JavaScript produces, because every comparison against NaN is false. Both
   * the operator and its apparent negation being false is the point, so this cannot be written as
   * a signed comparison with a sentinel.
   */
  private static boolean ordered(
      Object variable, String value, java.util.function.IntPredicate test) {
    Double left = numeric(variable);
    Double right = toNumber(value);
    if (left == null || left.isNaN() || right.isNaN()) {
      return false;
    }
    return test.test(Double.compare(left, right));
  }
}
