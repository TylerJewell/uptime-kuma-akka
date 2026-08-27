package io.akka.uptimekuma.domain;

import java.util.List;

/**
 * Whether a response code is one the monitor was told to accept.
 *
 * <p>An entry is either a single number or a hyphenated inclusive range. Anything else — an entry
 * that is not a string, or one that splits into more than two parts — is skipped rather than
 * refused, so a list holding one unusable entry still matches on the others. An empty or absent
 * list matches nothing at all, which is the source's answer and not an oversight: a monitor with no
 * accepted codes rejects every response.
 */
public final class AcceptedStatusCodes {

  private AcceptedStatusCodes() {}

  public static boolean matches(int status, List<String> accepted) {
    if (accepted == null || accepted.isEmpty()) {
      return false;
    }
    for (String range : accepted) {
      if (range == null) {
        continue;
      }
      String[] parts = range.split("-");
      if (parts.length == 1) {
        Integer only = parseLeadingInt(parts[0]);
        if (only != null && status == only) {
          return true;
        }
      } else if (parts.length == 2) {
        Integer lo = parseLeadingInt(parts[0]);
        Integer hi = parseLeadingInt(parts[1]);
        if (lo != null && hi != null && status >= lo && status <= hi) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The source parses each half with {@code parseInt}, which reads the leading digits and ignores
   * whatever follows, so {@code "200x"} is 200 rather than a refusal. Matched here so a list
   * carried over from the source behaves the same way.
   */
  private static Integer parseLeadingInt(String text) {
    String trimmed = text.trim();
    int end = 0;
    if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
      end++;
    }
    while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
      end++;
    }
    if (end == 0 || (end == 1 && !Character.isDigit(trimmed.charAt(0)))) {
      return null;
    }
    try {
      return Integer.valueOf(trimmed.substring(0, end));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
