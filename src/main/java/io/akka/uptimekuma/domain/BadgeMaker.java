package io.akka.uptimekuma.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The badge SVG.
 *
 * <p>Five styles, drawn the way the source's {@code badge-maker} draws them, down to the attribute
 * order and the whitespace stripping — a badge is embedded in somebody else's page and its width,
 * its colours and the position of the divide are the whole of what it communicates.
 *
 * <p>No logo. The badge routes take a label, a message, colours and a style and never a logo, so
 * the branch that would draw one has nothing to reach it.
 */
public final class BadgeMaker {

  private BadgeMaker() {}

  private static final String FONT_FAMILY = "Verdana,Geneva,DejaVu Sans,sans-serif";
  private static final String SOCIAL_FONT_FAMILY = "Helvetica Neue,Helvetica,Arial,sans-serif";
  private static final double BRIGHTNESS_THRESHOLD = 0.69;
  private static final int HORIZ_PADDING = 5;

  private static final Map<String, String> NAMED_COLORS =
      Map.of(
          "brightgreen", "#4c1",
          "green", "#97ca00",
          "yellow", "#dfb317",
          "yellowgreen", "#a4a61d",
          "orange", "#fe7d37",
          "red", "#e05d44",
          "blue", "#007ec6",
          "grey", "#555",
          "lightgrey", "#9f9f9f");

  private static final Map<String, String> ALIASES =
      Map.of(
          "gray", "grey",
          "lightgray", "lightgrey",
          "critical", "red",
          "important", "orange",
          "success", "brightgreen",
          "informational", "blue",
          "inactive", "lightgrey");

  /**
   * @param label the left half; an empty label collapses the left rectangle entirely
   * @param message the right half
   * @param color the right half's background
   * @param labelColor the left half's background, or null for the default grey
   * @param style one of plastic, flat, flat-square, for-the-badge, social
   */
  public static String make(
      String label, String message, String color, String labelColor, String style) {
    String trimmedLabel = label == null ? "" : label.trim();
    String trimmedMessage = message == null ? "" : message.trim();
    String svgColor = toSvgColor(color == null ? "#4c1" : color);
    String svgLabelColor = toSvgColor(labelColor);
    String chosen = style == null || style.isBlank() ? "flat" : style;
    String rendered =
        switch (chosen) {
          case "plastic" -> classic(trimmedLabel, trimmedMessage, svgColor, svgLabelColor, 18, -10, true, "plastic");
          case "flat" -> classic(trimmedLabel, trimmedMessage, svgColor, svgLabelColor, 20, 0, true, "flat");
          case "flat-square" ->
              classic(trimmedLabel, trimmedMessage, svgColor, svgLabelColor, 20, 0, false, "flat-square");
          case "social" -> social(trimmedLabel, trimmedMessage, svgColor, svgLabelColor);
          case "for-the-badge" -> forTheBadge(trimmedLabel, trimmedMessage, svgColor, svgLabelColor);
          default -> throw new IllegalArgumentException("Unknown badge style: '" + chosen + "'");
        };
    return stripXmlWhitespace(rendered);
  }

  // ---- the three styles that share a layout -------------------------------------------------

  private static String classic(
      String label,
      String message,
      String color,
      String labelColor,
      int height,
      int verticalMargin,
      boolean shadow,
      String style) {

    boolean hasLabel = !label.isEmpty() || labelColor != null;
    String outLabelColor = labelColor == null ? "#555" : labelColor;
    // With no label and no label colour, the left rectangle takes the message's colour — which,
    // because its width is then zero, is only visible in the gradient overlay.
    outLabelColor = hasLabel ? outLabelColor : color;

    int labelMargin = 1;
    Rendered renderedLabel =
        renderText(labelMargin, label, height, verticalMargin, shadow, outLabelColor);
    int leftWidth = hasLabel ? renderedLabel.width() + 2 * HORIZ_PADDING : 0;

    int messageMargin = leftWidth - (message.isEmpty() ? 0 : 1);
    if (!hasLabel) {
      messageMargin = messageMargin + 1;
    }
    Rendered renderedMessage =
        renderText(messageMargin, message, height, verticalMargin, shadow, color);
    int rightWidth = renderedMessage.width() + 2 * HORIZ_PADDING;
    int width = leftWidth + rightWidth;

    String body;
    if ("flat-square".equals(style)) {
      body =
          """
          <g shape-rendering="crispEdges">
            <rect width="%d" height="%d" fill="%s"/>
            <rect x="%d" width="%d" height="%d" fill="%s"/>
          </g>
          <g fill="#fff" text-anchor="middle" font-family="%s" text-rendering="geometricPrecision" font-size="110">
            %s
            %s
          </g>"""
              .formatted(
                  leftWidth, height, outLabelColor, leftWidth, rightWidth, height, color,
                  FONT_FAMILY, renderedLabel.svg(), renderedMessage.svg());
    } else {
      String gradient =
          "plastic".equals(style)
              ? """
                <linearGradient id="s" x2="0" y2="100%">
                  <stop offset="0"  stop-color="#fff" stop-opacity=".7"/>
                  <stop offset=".1" stop-color="#aaa" stop-opacity=".1"/>
                  <stop offset=".9" stop-color="#000" stop-opacity=".3"/>
                  <stop offset="1"  stop-color="#000" stop-opacity=".5"/>
                </linearGradient>"""
              : """
                <linearGradient id="s" x2="0" y2="100%">
                  <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
                  <stop offset="1" stop-opacity=".1"/>
                </linearGradient>""";
      int radius = "plastic".equals(style) ? 4 : 3;
      body =
          gradient
              + """

              <clipPath id="r">
                <rect width="%d" height="%d" rx="%d" fill="#fff"/>
              </clipPath>
              <g clip-path="url(#r)">
                <rect width="%d" height="%d" fill="%s"/>
                <rect x="%d" width="%d" height="%d" fill="%s"/>
                <rect width="%d" height="%d" fill="url(#s)"/>
              </g>
              <g fill="#fff" text-anchor="middle" font-family="%s" text-rendering="geometricPrecision" font-size="110">
                %s
                %s
              </g>"""
                  .formatted(
                      width, height, radius,
                      leftWidth, height, outLabelColor,
                      leftWidth, rightWidth, height, color,
                      width, height,
                      FONT_FAMILY, renderedLabel.svg(), renderedMessage.svg());
    }

    return renderBadge(width, height, accessibleText(label, message), body);
  }

  private record Rendered(String svg, int width) {}

  private static Rendered renderText(
      int leftMargin,
      String content,
      int height,
      int verticalMargin,
      boolean shadow,
      String background) {
    if (content.isEmpty()) {
      return new Rendered("", 0);
    }
    int textLength = preferredWidthOf(content, CharWidths.VERDANA_11);
    String escaped = escapeXml(content);

    int shadowMargin = 150 + verticalMargin;
    int textMargin = 140 + verticalMargin;
    int outTextLength = 10 * textLength;
    String x = number(10 * (leftMargin + 0.5 * textLength + HORIZ_PADDING));

    String textColor = brightness(background) <= BRIGHTNESS_THRESHOLD ? "#fff" : "#333";
    String shadowColor = brightness(background) <= BRIGHTNESS_THRESHOLD ? "#010101" : "#ccc";

    StringBuilder svg = new StringBuilder();
    if (shadow) {
      svg.append(
          "<text aria-hidden=\"true\" x=\"%s\" y=\"%d\" fill=\"%s\" fill-opacity=\".3\" transform=\"scale(.1)\" textLength=\"%d\">%s</text>"
              .formatted(x, shadowMargin, shadowColor, outTextLength, escaped));
    }
    svg.append(
        "<text x=\"%s\" y=\"%d\" transform=\"scale(.1)\" fill=\"%s\" textLength=\"%d\">%s</text>"
            .formatted(x, textMargin, textColor, outTextLength, escaped));
    return new Rendered(svg.toString(), textLength);
  }

  // ---- social --------------------------------------------------------------------------------

  private static String social(String label, String message, String color, String labelColor) {
    String capitalised =
        label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    int externalHeight = 20;
    int internalHeight = 19;
    int labelHorizPadding = 5;
    int messageHorizPadding = 4;
    int horizGutter = 6;
    boolean hasMessage = !message.isEmpty();

    int labelTextWidth = preferredWidthOf(capitalised, CharWidths.HELVETICA_11_BOLD);
    int messageTextWidth = preferredWidthOf(message, CharWidths.HELVETICA_11_BOLD);
    int labelRectWidth = labelTextWidth + 2 * labelHorizPadding;
    int messageRectWidth = messageTextWidth + 2 * messageHorizPadding;

    String labelTextX = number(10 * (labelTextWidth / 2.0 + labelHorizPadding));
    String messageTextX = number(10 * (labelRectWidth + horizGutter + messageRectWidth / 2.0));

    String messageBubble =
        hasMessage
            ? """
              <rect x="%s" y="0.5" width="%d" height="%d" rx="2" fill="#fafafa"/>
              <rect x="%d" y="7.5" width="0.5" height="5" stroke="#fafafa"/>
              <path d="M%s 6.5 l-3 3v1 l3 3" stroke="d5d5d5" fill="#fafafa"/>"""
                .formatted(
                    number(labelRectWidth + horizGutter + 0.5),
                    messageRectWidth,
                    internalHeight,
                    labelRectWidth + horizGutter,
                    number(labelRectWidth + horizGutter + 0.5))
            : "";

    String labelText =
        """
        <rect id="llink" stroke="#d5d5d5" fill="url(#a)" x=".5" y=".5" width="%d" height="%d" rx="2" />
        <text aria-hidden="true" x="%s" y="150" fill="#fff" transform="scale(.1)" textLength="%d">%s</text>
        <text x="%s" y="140" transform="scale(.1)" textLength="%d">%s</text>"""
            .formatted(
                labelRectWidth, internalHeight,
                labelTextX, 10 * labelTextWidth, escapeXml(capitalised),
                labelTextX, 10 * labelTextWidth, escapeXml(capitalised));

    String messageText =
        hasMessage
            ? """
              <text aria-hidden="true" x="%s" y="150" fill="#fff" transform="scale(.1)" textLength="%d">%s</text>
              <text id="rlink" x="%s" y="140" transform="scale(.1)" textLength="%d">%s</text>"""
                .formatted(
                    messageTextX, 10 * messageTextWidth, escapeXml(message),
                    messageTextX, 10 * messageTextWidth, escapeXml(message))
            : "";

    String body =
        """
        <style>a:hover #llink{fill:url(#b);stroke:#ccc}a:hover #rlink{fill:#4183c4}</style>
        <linearGradient id="a" x2="0" y2="100%%">
          <stop offset="0" stop-color="#fcfcfc" stop-opacity="0"/>
          <stop offset="1" stop-opacity=".1"/>
        </linearGradient>
        <linearGradient id="b" x2="0" y2="100%%">
          <stop offset="0" stop-color="#ccc" stop-opacity=".1"/>
          <stop offset="1" stop-opacity=".1"/>
        </linearGradient>
        <g stroke="#d5d5d5">
          <rect stroke="none" fill="#fcfcfc" x="0.5" y="0.5" width="%d" height="%d" rx="2"/>
          %s
        </g>
        <g aria-hidden="true" fill="#333" text-anchor="middle" font-family="%s" text-rendering="geometricPrecision" font-weight="700" font-size="110px" line-height="14px">
          %s
          %s
        </g>"""
            .formatted(
                labelRectWidth, internalHeight, messageBubble, SOCIAL_FONT_FAMILY, labelText,
                messageText);

    int width = labelRectWidth + 1 + (hasMessage ? horizGutter + messageRectWidth : 0);
    return renderBadge(width, externalHeight, accessibleText(capitalised, message), body);
  }

  // ---- for-the-badge -------------------------------------------------------------------------

  private static String forTheBadge(
      String label, String message, String color, String labelColor) {
    final int fontSize = 10;
    final int badgeHeight = 28;
    final int textMargin = 12;
    final double letterSpacing = 1.25;

    String upperLabel = label.toUpperCase(Locale.ROOT);
    String upperMessage = message.toUpperCase(Locale.ROOT);
    String outLabelColor = labelColor == null ? "#555" : labelColor;

    double labelTextWidth =
        upperLabel.isEmpty()
            ? 0
            : (int) CharWidths.of(CharWidths.VERDANA_10).widthOf(upperLabel)
                + letterSpacing * upperLabel.length();
    double messageTextWidth =
        upperMessage.isEmpty()
            ? 0
            : (int) CharWidths.of(CharWidths.VERDANA_10_BOLD).widthOf(upperMessage)
                + letterSpacing * upperMessage.length();

    boolean hasLabel = !upperLabel.isEmpty();
    double labelTextMinX = textMargin;
    double labelRectWidth = 0;
    double messageTextMinX;
    double messageRectWidth;
    if (hasLabel) {
      labelRectWidth = labelTextMinX + labelTextWidth + textMargin;
      messageTextMinX = labelRectWidth + textMargin;
      messageRectWidth = 2 * textMargin + messageTextWidth;
    } else {
      messageTextMinX = textMargin;
      messageRectWidth = 2 * textMargin + messageTextWidth;
    }

    String background =
        hasLabel
            ? "<g shape-rendering=\"crispEdges\"><rect width=\"%s\" height=\"%d\" fill=\"%s\"/><rect x=\"%s\" width=\"%s\" height=\"%d\" fill=\"%s\"/></g>"
                .formatted(
                    number(labelRectWidth), badgeHeight, outLabelColor,
                    number(labelRectWidth), number(messageRectWidth), badgeHeight, color)
            : "<g shape-rendering=\"crispEdges\"><rect width=\"%s\" height=\"%d\" fill=\"%s\"/></g>"
                .formatted(number(messageRectWidth), badgeHeight, color);

    String labelElement =
        hasLabel
            ? "<text transform=\"scale(.1)\" x=\"%s\" y=\"175\" textLength=\"%s\" fill=\"%s\">%s</text>"
                .formatted(
                    number(10 * (labelTextMinX + 0.5 * labelTextWidth)),
                    number(10 * labelTextWidth),
                    brightness(outLabelColor) <= BRIGHTNESS_THRESHOLD ? "#fff" : "#333",
                    escapeXml(upperLabel))
            : "";
    String messageElement =
        "<text transform=\"scale(.1)\" x=\"%s\" y=\"175\" textLength=\"%s\" fill=\"%s\" font-weight=\"bold\">%s</text>"
            .formatted(
                number(10 * (messageTextMinX + 0.5 * messageTextWidth)),
                number(10 * messageTextWidth),
                brightness(color) <= BRIGHTNESS_THRESHOLD ? "#fff" : "#333",
                escapeXml(upperMessage));

    String body =
        background
            + "<g fill=\"#fff\" text-anchor=\"middle\" font-family=\"%s\" text-rendering=\"geometricPrecision\" font-size=\"%d\">%s%s</g>"
                .formatted(FONT_FAMILY, 10 * fontSize, labelElement, messageElement);

    int width = (int) Math.round(labelRectWidth + messageRectWidth);
    return renderBadge(
        width, badgeHeight, accessibleText(upperLabel, upperMessage), body);
  }

  // ---- shared --------------------------------------------------------------------------------

  private static String renderBadge(int width, int height, String accessibleText, String main) {
    return """
        <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="%d" height="%d" role="img" aria-label="%s">
        <title>%s</title>
        %s
        </svg>"""
        .formatted(width, height, escapeXml(accessibleText), escapeXml(accessibleText), main);
  }

  private static String accessibleText(String label, String message) {
    return (label == null || label.isEmpty() ? "" : label + ": ") + message;
  }

  /**
   * Round a measured width up to the next odd integer, after truncating the fraction.
   *
   * <p>Odd, so the text's midpoint — which is half the width — lands on a half pixel and the
   * renderer's grid alignment is the same on both sides.
   */
  static int preferredWidthOf(String text, String font) {
    int measured = (int) CharWidths.of(font).widthOf(text);
    return measured % 2 == 0 ? measured + 1 : measured;
  }

  /** JavaScript prints a whole number without a trailing {@code .0}; this matches that. */
  private static String number(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value)) {
      return Long.toString((long) value);
    }
    String text = Double.toString(value);
    return text;
  }

  static String toSvgColor(String color) {
    String normalized = normalizeColor(color);
    if (normalized == null) {
      return null;
    }
    if (NAMED_COLORS.containsKey(normalized)) {
      return NAMED_COLORS.get(normalized);
    }
    if (ALIASES.containsKey(normalized)) {
      return NAMED_COLORS.get(ALIASES.get(normalized));
    }
    return normalized;
  }

  static String normalizeColor(String color) {
    if (color == null) {
      return null;
    }
    if (NAMED_COLORS.containsKey(color) || ALIASES.containsKey(color)) {
      return color;
    }
    if (color.matches("^([\\da-fA-F]{3}){1,2}$")) {
      return "#" + color.toLowerCase(Locale.ROOT);
    }
    if (parseCssColor(color) != null) {
      return color.toLowerCase(Locale.ROOT);
    }
    return null;
  }

  /**
   * How light a colour is, on the standard luminance weighting.
   *
   * <p>Decides whether the text on top is white or dark grey, so it is part of what a badge looks
   * like rather than a helper.
   */
  static double brightness(String color) {
    int[] rgb = parseCssColor(color);
    if (rgb == null) {
      return 0;
    }
    double value = (rgb[0] * 299.0 + rgb[1] * 587.0 + rgb[2] * 114.0) / 255000.0;
    return Math.round(value * 100.0) / 100.0;
  }

  /** Hex in three or six digits, {@code rgb(...)}, or one of the CSS colour keywords. */
  static int[] parseCssColor(String color) {
    if (color == null) {
      return null;
    }
    String value = color.trim().toLowerCase(Locale.ROOT);
    if (NAMED_COLORS.containsKey(value)) {
      value = NAMED_COLORS.get(value);
    } else if (ALIASES.containsKey(value)) {
      value = NAMED_COLORS.get(ALIASES.get(value));
    }
    if (value.startsWith("#")) {
      value = value.substring(1);
    }
    if (value.matches("^[\\da-f]{3}$")) {
      return new int[] {
        Integer.parseInt(value.substring(0, 1).repeat(2), 16),
        Integer.parseInt(value.substring(1, 2).repeat(2), 16),
        Integer.parseInt(value.substring(2, 3).repeat(2), 16)
      };
    }
    if (value.matches("^[\\da-f]{6}$")) {
      return new int[] {
        Integer.parseInt(value.substring(0, 2), 16),
        Integer.parseInt(value.substring(2, 4), 16),
        Integer.parseInt(value.substring(4, 6), 16)
      };
    }
    if (value.startsWith("rgb")) {
      String[] parts = value.replaceAll("^rgba?\\(", "").replace(")", "").split(",");
      if (parts.length >= 3) {
        try {
          return new int[] {
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim())
          };
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return CssKeywords.rgb(value);
  }

  static String escapeXml(String text) {
    if (text == null) {
      return null;
    }
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  static String stripXmlWhitespace(String xml) {
    return xml.replaceAll(">\\s+", ">").replaceAll("<\\s+", "<").trim();
  }
}
