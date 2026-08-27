package io.akka.uptimekuma.api;

import akka.javasdk.client.ComponentClient;
import io.akka.uptimekuma.application.RecordFields;
import io.akka.uptimekuma.application.Ids;
import io.akka.uptimekuma.application.RecordRow;
import io.akka.uptimekuma.application.StatusPageEntity;
import io.akka.uptimekuma.application.StatusPageListView;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.notifications.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A public page, its groups and the incidents posted to it.
 *
 * <p>The groups and the incidents live on the page rather than in tables of their own, because
 * neither is ever read except through the page it belongs to and the interface saves the whole
 * arrangement in one call.
 */
public final class StatusPages {

  private StatusPages() {}

  /** How many incidents one page of history holds. */
  public static final int INCIDENT_PAGE_SIZE = 10;

  private static final List<String> STYLES =
      List.of("info", "warning", "danger", "primary", "light", "dark");

  /**
   * The page with this slug.
   *
   * <p>A page is stored under its own slug, so this is one read rather than a scan. It also removes
   * a race: the list is built from the write and lags it, so a page read immediately after it was
   * made would not be found.
   */
  public static StoredRecord bySlug(ComponentClient componentClient, String slug) {
    if (slug == null || slug.isBlank()) {
      return null;
    }
    StoredRecord record =
        componentClient.forKeyValueEntity(slug).method(StatusPageEntity::get).invoke();
    return record.exists() ? record : null;
  }

  /**
   * The page as JSON.
   *
   * @param includePrivate the identifier and the list of domain names are for the administrator's
   *     screen only; a viewer is given everything else
   */
  public static Map<String, Object> toJson(Map<String, Object> page, boolean includePrivate) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("slug", page.get("slug"));
    json.put("title", page.get("title"));
    json.put("description", page.get("description"));
    Object icon = page.get("icon");
    json.put("icon", icon == null || String.valueOf(icon).isEmpty() ? "/icon.svg" : icon);
    json.put("theme", page.get("theme"));
    json.put("autoRefreshInterval", page.get("autoRefreshInterval"));
    json.put("published", !Boolean.FALSE.equals(page.get("published")));
    json.put("showTags", Boolean.TRUE.equals(page.get("showTags")));
    json.put("customCSS", page.get("customCSS"));
    json.put("footerText", page.get("footerText"));
    json.put("showPoweredBy", !Boolean.FALSE.equals(page.get("showPoweredBy")));
    json.put("analyticsId", page.get("analyticsId"));
    json.put("analyticsScriptUrl", page.get("analyticsScriptUrl"));
    json.put("analyticsType", page.get("analyticsType"));
    json.put("showCertificateExpiry", Boolean.TRUE.equals(page.get("showCertificateExpiry")));
    json.put("showOnlyLastHeartbeat", Boolean.TRUE.equals(page.get("showOnlyLastHeartbeat")));
    json.put("rssTitle", page.get("rssTitle"));
    if (includePrivate) {
      json.put("id", page.get("id"));
      Object domains = page.get("domainNameList");
      json.put("domainNameList", domains == null ? List.of() : domains);
    }
    return json;
  }

  /** Give each group and each monitor inside it the position it arrived in, numbered from one. */
  @SuppressWarnings("unchecked")
  public static List<Object> numberGroups(Object publicGroupList) {
    List<Object> out = new ArrayList<>();
    if (!(publicGroupList instanceof List<?> groups)) {
      return out;
    }
    int groupOrder = 1;
    for (Object entry : groups) {
      if (!(entry instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> group = new LinkedHashMap<>((Map<String, Object>) raw);
      group.put("weight", groupOrder++);
      Object monitors = group.get("monitorList");
      if (monitors instanceof List<?> list) {
        List<Object> numbered = new ArrayList<>();
        int monitorOrder = 1;
        for (Object monitorEntry : list) {
          if (monitorEntry instanceof Map<?, ?> rawMonitor) {
            Map<String, Object> monitor = new LinkedHashMap<>((Map<String, Object>) rawMonitor);
            monitor.put("weight", monitorOrder++);
            // Stored as an integer column, and handed over as one.
            Object sendUrl = monitor.get("sendUrl");
            if (sendUrl instanceof Boolean flag) {
              monitor.put("sendUrl", flag ? 1 : 0);
            }
            numbered.add(monitor);
          }
        }
        group.put("monitorList", numbered);
      }
      out.add(group);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> incidentsOf(StoredRecord page) {
    Object incidents = page.get("incidents");
    List<Map<String, Object>> out = new ArrayList<>();
    if (incidents instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> incident) {
          Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) incident);
          // A column nobody has written is a key holding null, not a key that is missing: the
          // interface tests whether an incident has ever been edited by reading this field.
          copy.putIfAbsent("lastUpdatedDate", null);
          out.add(copy);
        }
      }
    }
    return out;
  }

  /** The incidents a viewer sees: pinned and still open, newest first. */
  public static List<Map<String, Object>> pinnedIncidents(StoredRecord page) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> incident : incidentsOf(page)) {
      if (Boolean.TRUE.equals(incident.get("pin")) && Boolean.TRUE.equals(incident.get("active"))) {
        out.add(incident);
      }
    }
    out.sort(Comparator.comparing((Map<String, Object> i) -> String.valueOf(i.get("createdDate"))).reversed());
    return out;
  }

  /** The instant, written the way the source's own tables write one. */
  private static String sqlNow() {
    return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  /**
   * Add an incident, or replace one.
   *
   * @param forcePinned the posting call always pins and re-opens; the editing call does not
   * @return the stored incident, or null when an edit named one that is not there
   */
  public static Map<String, Object> upsertIncident(
      ComponentClient componentClient,
      StoredRecord page,
      Map<String, Object> incident,
      boolean forcePinned) {
    List<Map<String, Object>> incidents = incidentsOf(page);
    String id = incident.get("id") == null ? null : String.valueOf(incident.get("id"));
    Map<String, Object> stored = null;
    if (id != null && !"null".equals(id)) {
      for (Map<String, Object> candidate : incidents) {
        if (id.equals(String.valueOf(candidate.get("id")))) {
          stored = candidate;
          break;
        }
      }
      if (stored == null && !forcePinned) {
        return null;
      }
    }
    if (stored == null) {
      stored = new LinkedHashMap<>();
      stored.put("id", Ids.next(componentClient, "incident"));
      stored.put("createdDate", sqlNow());
      incidents.add(stored);
    } else {
      stored.put("lastUpdatedDate", sqlNow());
    }
    stored.put("title", incident.get("title"));
    stored.put("content", incident.get("content"));
    Object style = incident.get("style");
    // A style outside the palette is quietly replaced rather than refused.
    stored.put(
        "style", STYLES.contains(String.valueOf(style)) ? style : "warning");
    if (forcePinned) {
      stored.put("pin", true);
      stored.put("active", true);
    } else {
      stored.put("pin", !Boolean.FALSE.equals(incident.get("pin")));
    }
    stored.put("status_page_id", page.id());
    save(componentClient, page, incidents);
    return stored;
  }

  public static boolean deleteIncident(
      ComponentClient componentClient, StoredRecord page, String incidentId) {
    List<Map<String, Object>> incidents = incidentsOf(page);
    boolean removed =
        incidents.removeIf(incident -> incidentId.equals(String.valueOf(incident.get("id"))));
    if (removed) {
      save(componentClient, page, incidents);
    }
    return removed;
  }

  public static Map<String, Object> resolveIncident(
      ComponentClient componentClient, StoredRecord page, String incidentId) {
    List<Map<String, Object>> incidents = incidentsOf(page);
    for (Map<String, Object> incident : incidents) {
      if (incidentId.equals(String.valueOf(incident.get("id")))) {
        incident.put("active", false);
        incident.put("pin", false);
        incident.put("lastUpdatedDate", sqlNow());
        save(componentClient, page, incidents);
        return incident;
      }
    }
    return null;
  }

  public static void unpinAll(ComponentClient componentClient, StoredRecord page) {
    List<Map<String, Object>> incidents = incidentsOf(page);
    for (Map<String, Object> incident : incidents) {
      incident.put("pin", false);
    }
    save(componentClient, page, incidents);
  }

  /** One page of the history, newest first, cursored by the instant an incident was posted. */
  public static Map<String, Object> incidentHistory(StoredRecord page, String cursor) {
    List<Map<String, Object>> all = incidentsOf(page);
    all.sort(
        Comparator.comparing((Map<String, Object> i) -> String.valueOf(i.get("createdDate")))
            .reversed());
    List<Map<String, Object>> filtered = new ArrayList<>();
    for (Map<String, Object> incident : all) {
      if (cursor == null
          || "null".equals(cursor)
          || String.valueOf(incident.get("createdDate")).compareTo(cursor) < 0) {
        filtered.add(incident);
      }
    }
    List<Map<String, Object>> pageOf =
        filtered.subList(0, Math.min(INCIDENT_PAGE_SIZE, filtered.size()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("incidents", new ArrayList<>(pageOf));
    result.put("total", all.size());
    boolean hasMore = filtered.size() > pageOf.size();
    result.put("hasMore", hasMore);
    result.put(
        "nextCursor",
        hasMore && !pageOf.isEmpty()
            ? pageOf.get(pageOf.size() - 1).get("createdDate")
            : null);
    return result;
  }

  private static void save(
      ComponentClient componentClient, StoredRecord page, List<Map<String, Object>> incidents) {
    Map<String, Object> fields = new LinkedHashMap<>(page.fields());
    fields.put("incidents", incidents);
    componentClient.forKeyValueEntity(page.id()).method(StatusPageEntity::put).invoke(RecordFields.of(fields));
  }

  /** The overall state a page reports, from the last beat of every monitor it shows. */
  public static int overallStatus(List<Integer> statuses) {
    if (statuses.isEmpty()) {
      return -1;
    }
    boolean anyUp = false;
    boolean anyNotUp = false;
    for (Integer status : statuses) {
      if (status != null && status == 3) {
        return 3;
      }
      if (status != null && status == 1) {
        anyUp = true;
      } else {
        anyNotUp = true;
      }
    }
    if (!anyUp) {
      return 0;
    }
    return anyNotUp ? 2 : 1;
  }

  public static String statusDescription(int status) {
    return switch (status) {
      case -1 -> "No Services";
      case 1 -> "All Systems Operational";
      case 2 -> "Partially Degraded Service";
      case 0 -> "Degraded Service";
      case 3 -> "Under maintenance";
      default -> "?";
    };
  }

  static String write(Object value) {
    return Json.write(value);
  }
}
