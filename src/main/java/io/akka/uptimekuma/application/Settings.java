package io.akka.uptimekuma.application;

import akka.javasdk.client.ComponentClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's own settings, and their defaults.
 *
 * <p>Held as one row rather than one row per key, because they are read as a set — the interface's
 * settings screen asks for all of them at once and writes all of them back — and a single row makes
 * that one read and one write instead of twenty of each.
 */
public final class Settings {

  private Settings() {}

  /** The one identifier the settings row is stored under. */
  public static final String ID = "server";

  /** How long a heartbeat is kept before the cleaner removes it. */
  public static final int DEFAULT_KEEP_PERIOD = 365;

  /** How many days before a certificate expires a warning is sent. */
  public static final List<Integer> DEFAULT_TLS_EXPIRY_NOTIFY_DAYS = List.of(7, 14, 21);

  /** The same, for a domain registration. */
  public static final List<Integer> DEFAULT_DOMAIN_EXPIRY_NOTIFY_DAYS = List.of(7, 14, 21);

  /** Every key the settings screen offers, in the order it shows them. */
  public static final List<String> GENERAL_KEYS =
      List.of(
          "serverTimezone",
          "searchEngineIndex",
          "entryPage",
          "primaryBaseURL",
          "steamAPIKey",
          "globalpingApiToken",
          "nscd",
          "chromeExecutable",
          "keepDataPeriodDays",
          "tlsExpiryNotifyDays",
          "domainExpiryNotifyDays",
          "trustProxy",
          "disableAuth",
          "checkUpdate",
          "checkBeta");

  /** Only what was written — no fallbacks. What the interface's own form is drawn from. */
  public static Map<String, Object> stored(ComponentClient componentClient) {
    return new LinkedHashMap<>(
        componentClient.forKeyValueEntity(ID).method(SettingsEntity::get).invoke().fields());
  }

  public static Map<String, Object> read(ComponentClient componentClient) {
    StoredRecord record =
        componentClient.forKeyValueEntity(ID).method(SettingsEntity::get).invoke();
    Map<String, Object> values = new LinkedHashMap<>(record.fields());
    values.putIfAbsent("serverTimezone", java.time.ZoneId.systemDefault().getId());
    values.putIfAbsent("entryPage", "dashboard");
    values.putIfAbsent("keepDataPeriodDays", DEFAULT_KEEP_PERIOD);
    values.putIfAbsent("tlsExpiryNotifyDays", DEFAULT_TLS_EXPIRY_NOTIFY_DAYS);
    values.putIfAbsent("domainExpiryNotifyDays", DEFAULT_DOMAIN_EXPIRY_NOTIFY_DAYS);
    return values;
  }

  /** A setting as it was written, with no fallback — null where nobody has ever set it. */
  public static String storedString(ComponentClient componentClient, String key) {
    Object value = stored(componentClient).get(key);
    return value == null ? null : String.valueOf(value);
  }

  public static String string(ComponentClient componentClient, String key) {
    Object value = read(componentClient).get(key);
    return value == null ? null : String.valueOf(value);
  }

  public static boolean flag(ComponentClient componentClient, String key) {
    Object value = read(componentClient).get(key);
    if (value instanceof Boolean b) {
      return b;
    }
    return "true".equals(String.valueOf(value));
  }

  /** A setting read as a whole number, falling back where it was never set or cannot be read. */
  public static int number(ComponentClient componentClient, String key, int fallback) {
    Object value = read(componentClient).get(key);
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  public static String timezone(ComponentClient componentClient) {
    String zone = string(componentClient, "serverTimezone");
    return zone == null || zone.isBlank() ? java.time.ZoneId.systemDefault().getId() : zone;
  }

  /** The thresholds for a warning, read back as numbers whatever shape they were stored in. */
  public static List<Integer> days(ComponentClient componentClient, String key, List<Integer> fallback) {
    Object value = read(componentClient).get(key);
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      return fallback;
    }
    List<Integer> out = new ArrayList<>();
    for (Object entry : list) {
      try {
        out.add((int) Double.parseDouble(String.valueOf(entry)));
      } catch (NumberFormatException ignored) {
        // A threshold that is not a number is dropped rather than failing the whole list.
      }
    }
    return out.isEmpty() ? fallback : out;
  }

  public static void write(ComponentClient componentClient, Map<String, Object> values) {
    componentClient.forKeyValueEntity(ID).method(SettingsEntity::patch).invoke(RecordFields.of(values));
  }

  /** The settings a client that has not signed in is told about. */
  public static Map<String, Object> publicInfo(
      ComponentClient componentClient, boolean hideVersion, String version) {
    Map<String, Object> all = read(componentClient);
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("primaryBaseURL", all.get("primaryBaseURL"));
    String zone = timezone(componentClient);
    info.put("serverTimezone", zone);
    info.put(
        "serverTimezoneOffset",
        java.time.ZonedDateTime.now(java.time.ZoneId.of(zone))
            .getOffset()
            .getId()
            .replace("Z", "+00:00"));
    if (!hideVersion) {
      info.put("version", version);
      info.put("latestVersion", version);
      info.put("isContainer", System.getenv("UPTIME_KUMA_IS_CONTAINER") != null);
      info.put("dbType", "akka");
      Map<String, Object> runtime = new LinkedHashMap<>();
      runtime.put("platform", System.getProperty("os.name"));
      runtime.put("arch", System.getProperty("os.arch"));
      info.put("runtime", runtime);
    }
    return info;
  }
}
