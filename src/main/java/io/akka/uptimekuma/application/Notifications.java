package io.akka.uptimekuma.application;

import akka.javasdk.client.ComponentClient;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.notifications.Config;
import io.akka.uptimekuma.notifications.Context;
import io.akka.uptimekuma.notifications.Providers;
import io.akka.uptimekuma.notifications.Sender;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Telling the notification targets attached to a monitor that something happened.
 *
 * <p>One target failing does not stop the others: the loop catches, logs and moves on, which is
 * what the source does — a mis-configured target should not silence the rest.
 */
public final class Notifications {

  private static final Logger log = LoggerFactory.getLogger(Notifications.class);

  private static final DateTimeFormatter LOCAL_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private Notifications() {}

  public static void send(
      ComponentClient componentClient,
      Sender sender,
      String version,
      MonitorConfig config,
      Heartbeat beat,
      List<Heartbeat> history) {
    String text = io.akka.uptimekuma.domain.BeatDecision.notificationText(config.name(), beat);
    Map<String, Object> monitorJson = monitorJson(config);
    Map<String, Object> heartbeatJson =
        heartbeatJson(componentClient, beat, history);
    deliver(componentClient, sender, version, config, text, monitorJson, heartbeatJson);
  }

  /** A certificate warning, which names the monitor and the certificate rather than a beat. */
  public static void sendCertWarning(
      ComponentClient componentClient,
      Sender sender,
      String version,
      MonitorConfig config,
      String certType,
      String commonName,
      long daysRemaining) {
    String text =
        "["
            + config.name()
            + "]["
            + config.url()
            + "] "
            + certType
            + " certificate "
            + commonName
            + " will expire in "
            + daysRemaining
            + " days";
    deliver(componentClient, sender, version, config, text, monitorJson(config), null);
  }

  public static void sendDomainWarning(
      ComponentClient componentClient,
      Sender sender,
      String version,
      MonitorConfig config,
      String domain,
      long daysRemaining) {
    String text = "Domain name " + domain + " will expire in " + daysRemaining + " days";
    deliver(componentClient, sender, version, config, text, monitorJson(config), null);
  }

  private static void deliver(
      ComponentClient componentClient,
      Sender sender,
      String version,
      MonitorConfig config,
      String text,
      Map<String, Object> monitorJson,
      Map<String, Object> heartbeatJson) {
    Context context =
        new Context(sender, Settings.string(componentClient, "primaryBaseURL"), version);
    for (String notificationId : attachedTo(config)) {
      StoredRecord record =
          componentClient
              .forKeyValueEntity(notificationId)
              .method(NotificationEntity::get)
              .invoke();
      if (!record.exists()) {
        continue;
      }
      try {
        Providers.send(new Config(record.fields()), text, monitorJson, heartbeatJson, context);
      } catch (Exception e) {
        log.error("Cannot send notification to {}", record.str("name"), e);
      }
    }
  }

  /** The identifiers of the targets a monitor has switched on. */
  public static List<String> attachedTo(MonitorConfig config) {
    List<String> out = new ArrayList<>();
    if (config.notificationIDList() == null) {
      return out;
    }
    config
        .notificationIDList()
        .forEach(
            (id, on) -> {
              if (Boolean.TRUE.equals(on)) {
                out.add(id);
              }
            });
    return out;
  }

  public static Map<String, Object> monitorJson(MonitorConfig config) {
    return io.akka.uptimekuma.notifications.Json.MAPPER.convertValue(
        config.withoutSensitiveData(), LinkedHashMap.class);
  }

  /**
   * The beat as a target reads it, with the four fields the source adds on the way out.
   *
   * <p>{@code lastDownTime} is the instant of the beat that marked the outage starting, not the
   * last failed check before recovery — which is why it is looked for among the important beats
   * rather than among all of them.
   */
  public static Map<String, Object> heartbeatJson(
      ComponentClient componentClient, Heartbeat beat, List<Heartbeat> history) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("monitorID", beat.monitorId());
    json.put("status", beat.status());
    json.put("time", format(beat.timeEpochMillis(), ZoneId.of("UTC")));
    json.put("msg", beat.msg() == null || beat.msg().isEmpty() ? "N/A" : beat.msg());
    json.put("ping", beat.ping());
    json.put("important", beat.important());
    json.put("duration", beat.duration());
    json.put("retries", beat.retries());
    json.put("response", beat.response());

    String zone = Settings.timezone(componentClient);
    json.put("timezone", zone);
    json.put(
        "timezoneOffset",
        java.time.ZonedDateTime.now(ZoneId.of(zone)).getOffset().getId().replace("Z", "+00:00"));
    json.put("localDateTime", format(beat.timeEpochMillis(), ZoneId.of(zone)));

    if (beat.statusEnum() == io.akka.uptimekuma.domain.Status.UP && history != null) {
      for (int i = history.size() - 1; i >= 0; i--) {
        Heartbeat candidate = history.get(i);
        if (candidate.important()
            && candidate.statusEnum() == io.akka.uptimekuma.domain.Status.DOWN) {
          json.put("lastDownTime", format(candidate.timeEpochMillis(), ZoneId.of("UTC")));
          break;
        }
      }
    }
    return json;
  }

  public static String format(long epochMillis, ZoneId zone) {
    return LOCAL_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
  }
}
