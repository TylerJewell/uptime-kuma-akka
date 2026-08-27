package io.akka.uptimekuma.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.uptimekuma.application.HeartbeatFeedView;
import io.akka.uptimekuma.application.Maintenances;
import io.akka.uptimekuma.application.MonitorEntity;
import io.akka.uptimekuma.application.MonitorListView;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.domain.BadgeMaker;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MaintenanceWindow;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a public page serves to somebody who is not signed in.
 *
 * <p>Four things: the page and its groups, the beats behind the bars, a feed, and a badge for the
 * page as a whole. The messages on a public page's beats are blanked — a visitor is told a service
 * was down, not what the failure said, and that is the source's rule rather than a choice made
 * here.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/status-page")
public class StatusPageEndpoint extends AbstractHttpEndpoint {

  /** How many beats a public page shows per monitor. */
  private static final int PUBLIC_BEAT_LIMIT = 100;

  private final ComponentClient componentClient;

  public StatusPageEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/{slug}")
  public HttpResponse page(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return notFound("Status Page Not Found");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("config", StatusPages.toJson(page.toJson(), false));
    body.put("incidents", StatusPages.pinnedIncidents(page));
    body.put("publicGroupList", publicGroups(page));
    body.put("maintenanceList", maintenanceFor(page));
    return HttpResponses.ok(body);
  }

  @Get("/heartbeat/{slug}")
  public HttpResponse heartbeats(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return notFound("Status Page Not Found");
    }
    Map<String, Object> heartbeatList = new LinkedHashMap<>();
    Map<String, Object> uptimeList = new LinkedHashMap<>();
    long now = System.currentTimeMillis();
    for (String monitorId : monitorsOf(page)) {
      List<Heartbeat> recent =
          componentClient.forView().method(HeartbeatFeedView::recent).invoke(monitorId).heartbeats();
      List<Object> rows = new ArrayList<>();
      for (int i = Math.min(recent.size(), PUBLIC_BEAT_LIMIT) - 1; i >= 0; i--) {
        Heartbeat beat = recent.get(i);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("status", beat.status());
        json.put("time", Instant.ofEpochMilli(beat.timeEpochMillis()).toString());
        // A visitor is told a service was down, not what the failure said.
        json.put("msg", "");
        json.put("ping", beat.ping());
        rows.add(json);
      }
      heartbeatList.put(monitorId, rows);
      MonitorEntity.State state =
          componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
      uptimeList.put(monitorId + "_24", state.stats().get24Hour(now).uptime());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("heartbeatList", heartbeatList);
    body.put("uptimeList", uptimeList);
    return HttpResponses.ok(body);
  }

  @Get("/{slug}/manifest.json")
  public HttpResponse manifest(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return notFound("Not Found");
    }
    Map<String, Object> icon = new LinkedHashMap<>();
    icon.put("src", page.str("icon"));
    icon.put("sizes", "128x128");
    icon.put("type", "image/png");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", page.str("title"));
    body.put("start_url", "/status/" + slug);
    body.put("display", "standalone");
    body.put("icons", List.of(icon));
    return HttpResponses.ok(body);
  }

  @Get("/{slug}/incident-history")
  public HttpResponse incidentHistory(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return notFound("Status Page Not Found");
    }
    String cursor = requestContext().queryParams().getString("cursor").orElse(null);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    body.putAll(StatusPages.incidentHistory(page, cursor));
    return HttpResponses.ok(body);
  }

  /** One badge for a whole page: the worst thing happening on it. */
  @Get("/{slug}/badge")
  public HttpResponse badge(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return notFound("Status Page Not Found");
    }
    var params = requestContext().queryParams();
    List<Integer> statuses = new ArrayList<>();
    for (String monitorId : monitorsOf(page)) {
      MonitorEntity.State state =
          componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
      Heartbeat last = state.last();
      if (last != null) {
        statuses.add(last.status());
      }
    }
    String style = params.getString("style").orElse("flat");
    String label = params.getString("label").orElse("");
    if (statuses.isEmpty()) {
      return svg(BadgeMaker.make(label, "N/A", "#999", null, style));
    }
    boolean hasMaintenance = statuses.contains(3);
    boolean hasUp = statuses.contains(1);
    boolean hasDown = statuses.stream().anyMatch(status -> status != 1 && status != 2 && status != 3);
    String message;
    String color;
    if (hasMaintenance) {
      message = "Maintenance";
      // The page badge uses its own grey rather than the blue a monitor's badge uses.
      color = params.getString("maintenanceColor").orElse("#808080");
    } else if (hasUp && !hasDown) {
      message = "Up";
      color = params.getString("upColor").orElse("#66c20a");
    } else if (hasUp) {
      message = "Degraded";
      color = params.getString("partialColor").orElse("#F6BE00");
    } else {
      message = "Down";
      color = params.getString("downColor").orElse("#c2290a");
    }
    return svg(BadgeMaker.make(label, message, color, null, style));
  }

  /** The page as a feed, so a reader can follow it without opening it. */
  @Get("/{slug}/rss")
  public HttpResponse rss(String slug) {
    return StatusPageFeed.respond(componentClient, slug);
  }

  // ---- shared ---------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> publicGroups(StoredRecord page) {
    List<Map<String, Object>> out = new ArrayList<>();
    Object groups = page.get("publicGroupList");
    if (!(groups instanceof List<?> list)) {
      return out;
    }
    boolean showTags = page.flag("showTags");
    boolean showCertExpiry = page.flag("showCertificateExpiry");
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> group = new LinkedHashMap<>();
      group.put("id", raw.get("id"));
      group.put("name", raw.get("name"));
      group.put("weight", raw.get("weight"));
      List<Object> monitors = new ArrayList<>();
      Object monitorList = raw.get("monitorList");
      if (monitorList instanceof List<?> members) {
        for (Object member : members) {
          if (!(member instanceof Map<?, ?> rawMonitor)) {
            continue;
          }
          String monitorId = String.valueOf(rawMonitor.get("id"));
          var row = componentClient.forView().method(MonitorListView::byId).invoke(monitorId);
          if (row.isEmpty()) {
            continue;
          }
          MonitorEntity.State state =
              componentClient
                  .forEventSourcedEntity(monitorId)
                  .method(MonitorEntity::get)
                  .invoke();
          Map<String, Object> monitor = new LinkedHashMap<>();
          monitor.put("id", monitorId);
          monitor.put("name", row.get().name());
          boolean sendUrl = Boolean.TRUE.equals(rawMonitor.get("sendUrl"));
          monitor.put("sendUrl", sendUrl);
          monitor.put("type", row.get().type());
          if (sendUrl) {
            Object custom = rawMonitor.get("customUrl");
            monitor.put(
                "url", custom != null ? custom : state.created() ? state.config().url() : null);
          }
          if (showTags && state.created()) {
            monitor.put("tags", state.config().tags());
          }
          if (showCertExpiry && state.tlsInfo() != null && !state.tlsInfo().isEmpty()) {
            Object certInfo = state.tlsInfo().get("certInfo");
            if (certInfo instanceof Map<?, ?> map) {
              monitor.put("certExpiryDaysRemaining", map.get("daysRemaining"));
              monitor.put("validCert", Boolean.TRUE.equals(state.tlsInfo().get("valid")));
            }
          }
          monitors.add(monitor);
        }
      }
      group.put("monitorList", monitors);
      out.add(group);
    }
    return out;
  }

  private List<String> monitorsOf(StoredRecord page) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> group : publicGroups(page)) {
      Object monitors = group.get("monitorList");
      if (monitors instanceof List<?> list) {
        for (Object monitor : list) {
          if (monitor instanceof Map<?, ?> map) {
            out.add(String.valueOf(map.get("id")));
          }
        }
      }
    }
    return out;
  }

  /** The windows attached to this page that are happening now. */
  private List<Object> maintenanceFor(StoredRecord page) {
    List<Object> out = new ArrayList<>();
    String timezone = Settings.timezone(componentClient);
    long now = System.currentTimeMillis();
    for (MaintenanceWindow window : Maintenances.active(componentClient, timezone, now)) {
      if (Maintenances.statusPagesOf(componentClient, window.id()).contains(page.id())) {
        out.add(window.toJson(timezone, now));
      }
    }
    return out;
  }

  private static HttpResponse svg(String body) {
    return HttpResponse.create()
        .withEntity(
            HttpEntities.create(
                ContentTypes.parse("image/svg+xml; charset=UTF-8"),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static String escape(String value) {
    return value == null
        ? ""
        : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
  }

  private HttpResponse notFound(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "fail");
    body.put("msg", message);
    return HttpResponse.create()
        .withStatus(StatusCodes.NOT_FOUND)
        .withEntity(
            HttpEntities.create(
                ContentTypes.APPLICATION_JSON,
                io.akka.uptimekuma.notifications.Json.write(body)));
  }
}
