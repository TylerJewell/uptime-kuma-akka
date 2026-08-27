package io.akka.uptimekuma.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The condition tree, against the same cases the source's own suite covers.
 *
 * <p>Each operator is put to a value that satisfies it and one that does not, because an operator
 * that always answers true passes a test that only checks the true case.
 */
class ConditionsTest {

  private static boolean apply(String operator, Object variable, String value) {
    return Conditions.apply(operator, variable, value);
  }

  @Test
  void equalsIsStrictAboutType() {
    assertTrue(apply("equals", "mx1.example.com", "mx1.example.com"));
    assertFalse(apply("equals", "mx1.example.com", "mx1.example.org"));
    assertFalse(apply("equals", 1, "1x"));
  }

  @Test
  void notEqualsIsTheNegation() {
    assertTrue(apply("not_equals", ".com", ".org"));
    assertFalse(apply("not_equals", ".com", ".com"));
  }

  @Test
  void containsReadsAScalarAsASubstring() {
    assertTrue(apply("contains", "mx1.example.org", "example.org"));
    assertFalse(apply("contains", "mx1.example.org", "example.com"));
  }

  @Test
  void containsReadsAListAsMembership() {
    assertTrue(apply("contains", List.of("example.org"), "example.org"));
    assertFalse(apply("contains", List.of("example.org"), "example.com"));
  }

  @Test
  void notContainsIsTheNegationForBothShapes() {
    assertTrue(apply("not_contains", "example.org", ".com"));
    assertFalse(apply("not_contains", "example.org", ".org"));
    assertTrue(apply("not_contains", List.of("example.org"), "example.com"));
    assertFalse(apply("not_contains", List.of("example.org"), "example.org"));
  }

  @Test
  void startsAndEndsWith() {
    assertTrue(apply("starts_with", "mx1.example.com", "mx1"));
    assertFalse(apply("starts_with", "mx1.example.com", "mx2"));
    assertTrue(apply("not_starts_with", "mx1.example.com", "mx2"));
    assertFalse(apply("not_starts_with", "mx1.example.com", "mx1"));
    assertTrue(apply("ends_with", "mx1.example.com", "example.com"));
    assertFalse(apply("ends_with", "mx1.example.com", "example.net"));
    assertTrue(apply("not_ends_with", "mx1.example.com", "example.net"));
    assertFalse(apply("not_ends_with", "mx1.example.com", "example.com"));
  }

  @Test
  void numericEqualityCoercesTheStoredValue() {
    assertTrue(apply("num_equals", 1, "1"));
    assertFalse(apply("num_equals", 1, "2"));
    assertTrue(apply("num_not_equals", 1, "2"));
    assertFalse(apply("num_not_equals", 1, "1"));
  }

  @Test
  void orderingOperators() {
    assertTrue(apply("lt", 1, "2"));
    assertFalse(apply("lt", 1, "1"));
    assertTrue(apply("gt", 2, "1"));
    assertFalse(apply("gt", 1, "1"));
    assertTrue(apply("lte", 1, "1"));
    assertTrue(apply("lte", 1, "2"));
    assertFalse(apply("lte", 1, "0"));
    assertTrue(apply("gte", 1, "1"));
    assertTrue(apply("gte", 2, "1"));
    assertFalse(apply("gte", 2, "3"));
  }

  @Test
  void anUnorderedComparisonAnswersFalseBothWays() {
    // A value that is not a number is unordered against one, so neither the operator nor its
    // apparent negation holds. A signed comparison would make one of them true.
    assertFalse(apply("lt", "abc", "5"));
    assertFalse(apply("gte", "abc", "5"));
    assertFalse(apply("gt", "abc", "5"));
    assertFalse(apply("lte", "abc", "5"));
  }

  @Test
  void anUnknownOperatorIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> apply("approximately", "a", "a"));
  }

  private static Map<String, Object> expression(String variable, String operator, String value) {
    return expression(variable, operator, value, "and");
  }

  private static Map<String, Object> expression(
      String variable, String operator, String value, String andOr) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "expression");
    node.put("andOr", andOr);
    node.put("variable", variable);
    node.put("operator", operator);
    node.put("value", value);
    return node;
  }

  private static Map<String, Object> group(String andOr, List<Map<String, Object>> children) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "group");
    node.put("andOr", andOr);
    node.put("children", children);
    return node;
  }

  private static Map<String, Object> record(String value) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("record", value);
    return context;
  }

  @Test
  void oneExpressionReadsItsVariable() {
    List<Map<String, Object>> tree =
        List.of(expression("record", "contains", "mx1.example.com"));
    assertTrue(Conditions.evaluate(tree, record("mx1.example.com")));
    assertFalse(Conditions.evaluate(tree, record("mx2.example.com")));
  }

  @Test
  void andRequiresEveryChild() {
    List<Map<String, Object>> tree =
        List.of(
            expression("record", "contains", "mx1."),
            expression("record", "contains", "example.com"));
    assertTrue(Conditions.evaluate(tree, record("mx1.example.com")));
    assertFalse(Conditions.evaluate(tree, record("mx1.")));
    assertFalse(Conditions.evaluate(tree, record("example.com")));
  }

  @Test
  void orRequiresOneChild() {
    List<Map<String, Object>> tree =
        List.of(
            expression("record", "contains", "example.com"),
            expression("record", "contains", "example.org", "or"));
    assertTrue(Conditions.evaluate(tree, record("example.com")));
    assertTrue(Conditions.evaluate(tree, record("example.org")));
    assertFalse(Conditions.evaluate(tree, record("example.net")));
  }

  @Test
  void aNestedGroupIsReadAsAWhole() {
    List<Map<String, Object>> tree =
        List.of(
            expression("record", "contains", "mx1."),
            group(
                "and",
                List.of(
                    expression("record", "contains", "example.com"),
                    expression("record", "contains", "example.org", "or"))));
    assertFalse(Conditions.evaluate(tree, record("mx1.")));
    assertTrue(Conditions.evaluate(tree, record("mx1.example.com")));
    assertTrue(Conditions.evaluate(tree, record("mx1.example.org")));
    assertFalse(Conditions.evaluate(tree, record("example.com")));
    assertFalse(Conditions.evaluate(tree, record("example.org")));
    assertFalse(Conditions.evaluate(tree, record("mx1.example.net")));
  }

  @Test
  void theFoldIsLeftToRightWithNoPrecedence() {
    // a OR b AND c reads as (a OR b) AND c. With precedence it would read the other way and this
    // record would pass.
    List<Map<String, Object>> tree =
        List.of(
            expression("record", "equals", "a"),
            expression("record", "equals", "b", "or"),
            expression("record", "equals", "c"));
    assertFalse(Conditions.evaluate(tree, record("a")));
    assertFalse(Conditions.evaluate(tree, record("c")));
  }

  @Test
  void anEmptyTreeIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> Conditions.evaluate(List.of(), record("a")));
  }

  @Test
  void aVariableTheContextDoesNotCarryIsRefused() {
    List<Map<String, Object>> tree = List.of(expression("topic", "equals", "a"));
    assertThrows(
        IllegalArgumentException.class, () -> Conditions.evaluate(tree, record("a")));
  }

  @Test
  void eachTypeOffersTheVariablesItsChecksFill() {
    assertEquals(List.of("record"), Conditions.variablesFor("dns"));
    assertEquals(List.of("topic", "message", "json_value"), Conditions.variablesFor("mqtt"));
    assertEquals(List.of("result"), Conditions.variablesFor("mysql"));
    assertEquals(List.of(), Conditions.variablesFor("http"));
  }

  @Test
  void onlyOneVariableOffersTheNumericOperators() {
    assertEquals(8, Conditions.operatorsFor("mqtt", "topic").size());
    assertEquals(14, Conditions.operatorsFor("mqtt", "json_value").size());
  }
}
