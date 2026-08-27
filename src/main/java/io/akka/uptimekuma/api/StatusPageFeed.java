package io.akka.uptimekuma.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.client.ComponentClient;
import io.akka.uptimekuma.application.MonitorEntity;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.domain.Heartbeat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A status page as a feed, so a reader can follow it without opening it.
 *
 * <p>Its own class because two routes serve it. The source publishes the feed at
 * {@code /status/<slug>/rss}, beside the page itself, and this port answers on its own
 * {@code /api/status-page} prefix as well — and a reader subscribed to the first must not be handed
 * the single-page application because that path fell through to the interface's catch-all.
 */
final class StatusPageFeed {

  private StatusPageFeed() {}

  static HttpResponse respond(ComponentClient componentClient, String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return notFound("Status Page Not Found");
    }
    String base = Settings.string(componentClient, "primaryBaseURL");
    String link = (base == null ? "" : base) + "/status/" + slug;
    List<Integer> statuses = new ArrayList<>();
    List<String> items = new ArrayList<>();
    for (Map<String, Object> incident : StatusPages.incidentsOf(page)) {
      Object updated = incident.get("lastUpdatedDate");
      String stamp = String.valueOf(updated == null ? incident.get("createdDate") : updated);
      items.add(
          item(
              String.valueOf(incident.get("title")),
              String.valueOf(incident.get("content")),
              "i" + incident.get("id") + "-" + stamp,
              stamp,
              link));
    }
    for (String monitorId : monitorsOf(page)) {
      MonitorEntity.State state =
          componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
      if (state.last() != null) {
        statuses.add(state.last().status());
      }
      for (Heartbeat beat : state.importantHistory()) {
        if (beat.status() == 0) {
          items.add(
              item(
                  state.config().name() + " is down",
                  beat.msg() == null ? "" : beat.msg(),
                  monitorId + "-" + beat.timeEpochMillis(),
                  Instant.ofEpochMilli(beat.timeEpochMillis()).toString(),
                  link));
        }
      }
    }
    String title =
        page.str("rssTitle") != null && !page.str("rssTitle").isBlank()
            ? page.str("rssTitle")
            : page.str("title") != null
                ? page.str("title") + " RSS Feed"
                : "Uptime Kuma RSS Feed";
    String description =
        "Current status: " + StatusPages.statusDescription(StatusPages.overallStatus(statuses));
    String feed =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<rss version=\"2.0\"><channel>"
            + "<title>"
            + escape(title)
            + "</title>"
            + "<link>"
            + escape(link)
            + "</link>"
            + "<description>"
            + escape(description)
            + "</description>"
            // Three fields the source's feed library writes into every channel: when the feed was
            // built, where the format is documented, and what wrote it. A reader that shows a
            // feed's age reads the first of them.
            + "<lastBuildDate>"
            + rfc1123(Instant.now().toString())
            + "</lastBuildDate>"
            + "<docs>https://validator.w3.org/feed/docs/rss2.html</docs>"
            + "<generator>https://github.com/jpmonette/feed</generator>"
            + "<language>en</language>"
            + String.join("", items)
            + "</channel></rss>";
    return HttpResponse.create()
        .withEntity(
            HttpEntities.create(
                ContentTypes.parse("application/rss+xml; charset=utf-8"),
                feed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /**
   * One entry.
   *
   * <p>The title is wrapped in a character-data block rather than escaped, which is what the
   * source's feed library does — a monitor whose name contains markup then reaches a reader as
   * text rather than as markup.
   */
  private static String item(String title, String description, String id, String stamp, String link) {
    return "<item>"
        + "<title><![CDATA["
        + title.replace("]]>", "]]]]><![CDATA[>")
        + "]]></title>"
        + "<link>"
        + escape(link)
        + "</link>"
        + "<guid>"
        + escape(id)
        + "</guid>"
        + "<pubDate>"
        + rfc1123(stamp)
        + "</pubDate>"
        + "<description><![CDATA["
        + description.replace("]]>", "]]]]><![CDATA[>")
        + "]]></description>"
        + "</item>";
  }

  /** A feed's dates are in the format readers parse, in the zone the protocol requires. */
  private static String rfc1123(String stamp) {
    try {
      Instant instant;
      if (stamp.contains("T")) {
        instant = Instant.parse(stamp.endsWith("Z") ? stamp : stamp + "Z");
      } else {
        instant =
            java.time.LocalDateTime.parse(
                    stamp.substring(0, Math.min(19, stamp.length())),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toInstant(java.time.ZoneOffset.UTC);
      }
      return DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
          .withZone(ZoneId.of("GMT"))
          .format(instant);
    } catch (Exception e) {
      return stamp;
    }
  }

  private static String escape(String text) {
    return text == null
        ? ""
        : text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
  }

  private static HttpResponse notFound(String message) {
    return HttpResponse.create()
        .withStatus(StatusCodes.NOT_FOUND)
        .withEntity(
            HttpEntities.create(
                ContentTypes.APPLICATION_JSON,
                io.akka.uptimekuma.notifications.Json.write(
                    Map.of("ok", false, "msg", message))));
  }

  /** Every monitor a page publishes, in the order its groups list them. */
  private static List<String> monitorsOf(StoredRecord page) {
    List<String> ids = new ArrayList<>();
    Object groups = page.get("publicGroupList");
    if (groups instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> group && group.get("monitorList") instanceof List<?> inside) {
          for (Object monitor : inside) {
            if (monitor instanceof Map<?, ?> one) {
              ids.add(String.valueOf(one.get("id")));
            }
          }
        }
      }
    }
    return ids;
  }
}
