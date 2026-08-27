package io.akka.uptimekuma.notifications;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The pieces more than one provider needs. */
public final class Notify {

  private Notify() {}

  public static final int DOWN = 0;
  public static final int UP = 1;

  /** What all but three providers return when the call succeeded. */
  public static final String OK = "Sent Successfully.";

  public static Map<String, String> headers(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      if (pairs[i + 1] != null) {
        map.put(pairs[i], pairs[i + 1]);
      }
    }
    return map;
  }

  public static String basic(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                ((user == null ? "" : user) + ":" + (password == null ? "" : password))
                    .getBytes(StandardCharsets.UTF_8));
  }

  public static String base64(String value) {
    return Base64.getEncoder()
        .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
  }

  /** Percent-encoding for a path or a query value, with a space as {@code %20}. */
  public static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  /** Form-encoding, which uses {@code +} for a space where a URL path uses {@code %20}. */
  public static String form(Map<String, String> fields) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (out.length() > 0) {
        out.append('&');
      }
      out.append(java.net.URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(
              java.net.URLEncoder.encode(
                  field.getValue() == null ? "" : field.getValue(), StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  public static Map<String, String> fields(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
    }
    return map;
  }

  public static String hmacBase64(String algorithm, byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(key, algorithm));
      return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("cannot sign notification", e);
    }
  }

  public static String hmacHex(String algorithm, byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(key, algorithm));
      return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("cannot sign notification", e);
    }
  }

  /** Strip characters outside the seven-bit range, which nine SMS providers do to the message. */
  public static String asciiOnly(String text) {
    return text == null ? null : text.replaceAll("[^\\x00-\\x7F]", "");
  }

  /** Remove a single trailing slash, which several providers do before appending a path. */
  public static String stripTrailingSlash(String url) {
    if (url == null) {
      return null;
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /** Remove every trailing slash while keeping at least one non-slash character. */
  public static String stripTrailingSlashes(String url) {
    if (url == null) {
      return null;
    }
    return url.replaceAll("([^/])/+$", "$1");
  }

  public static Object get(Map<String, Object> json, String key) {
    return json == null ? null : json.get(key);
  }

  public static String string(Map<String, Object> json, String key) {
    Object value = get(json, key);
    return value == null ? null : String.valueOf(value);
  }

  public static Integer status(Map<String, Object> heartbeat) {
    Object value = get(heartbeat, "status");
    return value instanceof Number n ? n.intValue() : null;
  }

  public static boolean isUp(Map<String, Object> heartbeat) {
    Integer status = status(heartbeat);
    return status != null && status == UP;
  }

  public static boolean isDown(Map<String, Object> heartbeat) {
    Integer status = status(heartbeat);
    return status != null && status == DOWN;
  }

  /**
   * The address a provider shows for a monitor.
   *
   * <p>Type by type, because what identifies a monitor differs: a push monitor has no address at
   * all, a port monitor is a host and a port, and an http monitor's URL is left out when it is the
   * placeholder the interface starts a new monitor with.
   */
  public static String extractAddress(Map<String, Object> monitor) {
    if (monitor == null) {
      return "";
    }
    String type = string(monitor, "type");
    if (type == null) {
      type = "";
    }
    String hostname = string(monitor, "hostname");
    String url = string(monitor, "url");
    Object port = get(monitor, "port");
    switch (type) {
      case "push":
        return "Heartbeat";
      case "ping":
      case "dns":
        return hostname == null ? "" : hostname;
      case "port":
      case "gamedig":
      case "steam":
        if (port != null) {
          return hostname + ":" + port;
        }
        return hostname == null ? "" : hostname;
      case "globalping":
        String subtype = string(monitor, "subtype");
        if ("ping".equals(subtype) || "dns".equals(subtype)) {
          return hostname == null ? "" : hostname;
        }
        if ("http".equals(subtype)) {
          return url == null ? "" : url;
        }
        return "";
      default:
        if (url == null || "https://".equals(url) || "http://".equals(url) || url.isEmpty()) {
          return "";
        }
        return url;
    }
  }

  /** The status word a template's {@code status} variable holds. */
  public static String templateStatus(Map<String, Object> heartbeat) {
    if (heartbeat == null) {
      return "⚠️ Test";
    }
    return isDown(heartbeat) ? "🔴 Down" : "✅ Up";
  }

  /**
   * Render one of the interface's message templates.
   *
   * <p>The source runs these through Liquid. What the interface's template editors offer is
   * variable interpolation, dotted and bracketed member access, and a conditional, and that is what
   * {@link Liquid} implements — a template using a filter or a loop leaves its tag in the output
   * where the source would have expanded it, so the gap is visible in the message rather than
   * swallowed.
   */
  public static String renderTemplate(
      String template,
      String msg,
      Map<String, Object> monitorJson,
      Map<String, Object> heartbeatJson) {
    if (template == null) {
      return null;
    }
    Map<String, Object> context = new LinkedHashMap<>();
    String name =
        monitorJson == null
            ? "Monitor Name not available"
            : String.valueOf(monitorJson.getOrDefault("name", "Monitor Name not available"));
    String hostnameOrUrl = monitorJson == null ? "testing.hostname" : extractAddress(monitorJson);
    if (hostnameOrUrl == null || hostnameOrUrl.isEmpty()) {
      hostnameOrUrl = "testing.hostname";
    }
    String status = templateStatus(heartbeatJson);
    context.put("STATUS", status);
    context.put("NAME", name);
    context.put("HOSTNAME_OR_URL", hostnameOrUrl);
    context.put("status", status);
    context.put("name", name);
    context.put("hostnameOrURL", hostnameOrUrl);
    context.put("monitorJSON", monitorJson);
    context.put("heartbeatJSON", heartbeatJson);
    context.put("msg", msg);
    return Liquid.render(template, context);
  }

  /**
   * The message shape the source's axios error handler produces.
   *
   * <p>Reproduced because it is what a person sees when a test notification fails, and a shorter
   * message would lose the response body that says why.
   */
  public static String axiosError(String message, Sender.Response response) {
    StringBuilder out = new StringBuilder(message == null ? "" : message);
    if (response != null) {
      out.append(" (HTTP ").append(response.status());
      if (response.statusText() != null && !response.statusText().isEmpty()) {
        out.append(" ").append(response.statusText());
      }
      out.append(")");
      if (response.body() != null && !response.body().isEmpty()) {
        out.append(" ").append(response.body());
      }
    }
    return out.toString();
  }

  /** Split a field into trimmed, non-empty entries. */
  public static List<String> splitList(String value, String separatorRegex) {
    List<String> out = new ArrayList<>();
    if (value == null) {
      return out;
    }
    for (String part : value.split(separatorRegex)) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  public static boolean hasNonAscii(String text) {
    return text != null && text.chars().anyMatch(c -> c > 0x7F);
  }

  /** The response body, checked and turned into the exception a provider is expected to raise. */
  public static void requireOk(Sender.Response response, String what) throws Exception {
    if (response.status() == 0) {
      throw new Exception(what + " notification failed with invalid response!");
    }
    if (!response.ok()) {
      throw new Exception(what + " notification failed with status code " + response.status());
    }
  }
}
