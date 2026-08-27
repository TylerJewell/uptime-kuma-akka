package io.akka.uptimekuma.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * How wide a string is, in the fonts a badge is drawn with.
 *
 * <p>A badge's width is not a rendering detail — it is an attribute in the SVG, and it decides
 * where the two coloured rectangles meet. So the same character-width tables the source's badge
 * library measures against are shipped here, unmodified, from {@code anafanafo}: four sorted lists
 * of {@code [lowCodePoint, highCodePoint, width]} ranges.
 *
 * <p>A code point no range covers is guessed as the width of {@code m}, which is what the table
 * consumer does; control characters are zero-width.
 */
public final class CharWidths {

  /** The four fonts the badge styles measure in. */
  public static final String VERDANA_11 = "verdana-11px-normal";

  public static final String VERDANA_10 = "verdana-10px-normal";
  public static final String VERDANA_10_BOLD = "verdana-10px-bold";
  public static final String HELVETICA_11_BOLD = "helvetica-11px-bold";

  private static final Map<String, CharWidths> TABLES = new HashMap<>();

  private final double[][] ranges;
  private final double emWidth;

  private CharWidths(double[][] ranges) {
    this.ranges = ranges;
    this.emWidth = widthOfCodePoint('m');
  }

  public static synchronized CharWidths of(String font) {
    return TABLES.computeIfAbsent(font, CharWidths::load);
  }

  private static CharWidths load(String font) {
    try (InputStream in =
        CharWidths.class.getResourceAsStream("/charwidths/" + font + ".json")) {
      if (in == null) {
        throw new IllegalStateException("no width table for " + font);
      }
      double[][] data = new ObjectMapper().readValue(in, double[][].class);
      return new CharWidths(data);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read width table for " + font, e);
    }
  }

  public double widthOf(String text) {
    double total = 0;
    int i = 0;
    while (i < text.length()) {
      int codePoint = text.codePointAt(i);
      i += Character.charCount(codePoint);
      Double width = lookup(codePoint);
      total += width == null ? emWidth : width;
    }
    return total;
  }

  private double widthOfCodePoint(int codePoint) {
    Double width = lookup(codePoint);
    return width == null ? 0 : width;
  }

  private Double lookup(int codePoint) {
    if (codePoint <= 31 || codePoint == 127) {
      return 0d;
    }
    int lo = 0;
    int hi = ranges.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (ranges[mid][0] > codePoint) {
        hi = mid - 1;
      } else if (ranges[mid][1] < codePoint) {
        lo = mid + 1;
      } else {
        return ranges[mid][2];
      }
    }
    return null;
  }

  /** Present so a table can be checked for being sorted, which the binary search assumes. */
  boolean isSorted() {
    return Arrays.equals(
        ranges,
        Arrays.stream(ranges)
            .sorted((a, b) -> Double.compare(a[0], b[0]))
            .toArray(double[][]::new));
  }
}
