package io.akka.uptimekuma.notifications;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subset of Liquid the interface's message templates are written in.
 *
 * <p>Three constructs: {@code {{ expression }}}, {@code {% if expression %}} … {@code {% else %}} …
 * {@code {% endif %}}, and member access through dots or brackets. An expression that names nothing
 * renders as the empty string, which is Liquid's own behaviour and is what makes a template
 * referring to a field a test notification does not carry render blank rather than fail.
 *
 * <p>A tag outside that set is left in the output verbatim. That is deliberate: a template using a
 * filter or a loop then shows its own tag in the delivered message, so the gap is visible where the
 * source would have expanded it, rather than being quietly dropped.
 */
final class Liquid {

  private Liquid() {}

  private static final Pattern IF_BLOCK =
      Pattern.compile(
          "\\{%-?\\s*if\\s+(.+?)\\s*-?%\\}(.*?)(?:\\{%-?\\s*else\\s*-?%\\}(.*?))?\\{%-?\\s*endif\\s*-?%\\}",
          Pattern.DOTALL);

  private static final Pattern OUTPUT = Pattern.compile("\\{\\{-?\\s*(.+?)\\s*-?\\}\\}");

  static String render(String template, Map<String, Object> context) {
    String expanded = expandConditionals(template, context);
    Matcher matcher = OUTPUT.matcher(expanded);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      Object value = resolve(matcher.group(1), context);
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /**
   * Expand the innermost conditional first, repeatedly, so a nested {@code if} is resolved by the
   * pass that follows the one which uncovered it.
   */
  private static String expandConditionals(String template, Map<String, Object> context) {
    String current = template;
    for (int pass = 0; pass < 16; pass++) {
      Matcher matcher = IF_BLOCK.matcher(current);
      if (!matcher.find()) {
        return current;
      }
      StringBuilder out = new StringBuilder();
      matcher.reset();
      while (matcher.find()) {
        boolean condition = truthy(resolve(matcher.group(1), context));
        String chosen = condition ? matcher.group(2) : matcher.group(3);
        matcher.appendReplacement(
            out, Matcher.quoteReplacement(chosen == null ? "" : chosen));
      }
      matcher.appendTail(out);
      String next = out.toString();
      if (next.equals(current)) {
        return next;
      }
      current = next;
    }
    return current;
  }

  /** Liquid's truthiness: everything except null and false is true, including zero and "". */
  private static boolean truthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static Object resolve(String expression, Map<String, Object> context) {
    String trimmed = expression.trim();
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    Object current = context;
    for (String segment : segments(trimmed)) {
      if (current instanceof Map<?, ?> map) {
        current = ((Map<String, Object>) map).get(segment);
      } else if (current instanceof List<?> list) {
        try {
          current = list.get(Integer.parseInt(segment));
        } catch (Exception e) {
          return null;
        }
      } else {
        return null;
      }
      if (current == null) {
        return null;
      }
    }
    return current == context ? null : current;
  }

  /** Split {@code a.b["c"][0]} into its four member names. */
  private static List<String> segments(String path) {
    java.util.List<String> out = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inBracket = false;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (!inBracket && c == '.') {
        if (current.length() > 0) {
          out.add(current.toString());
          current.setLength(0);
        }
      } else if (c == '[') {
        if (current.length() > 0) {
          out.add(current.toString());
          current.setLength(0);
        }
        inBracket = true;
      } else if (c == ']') {
        out.add(unquote(current.toString()));
        current.setLength(0);
        inBracket = false;
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0) {
      out.add(current.toString());
    }
    return out;
  }

  private static String unquote(String value) {
    String trimmed = value.trim();
    if (trimmed.length() >= 2
        && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
            || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }
}
