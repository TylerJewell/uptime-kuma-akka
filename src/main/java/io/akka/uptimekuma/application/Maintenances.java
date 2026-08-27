package io.akka.uptimekuma.application;

import akka.javasdk.client.ComponentClient;
import io.akka.uptimekuma.domain.MaintenanceWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whether a monitor is inside a planned outage.
 *
 * <p>Asked before every beat, and the answer short-circuits the check entirely: a monitor under
 * maintenance records a maintenance beat and no probe runs. The question recurses up the parent
 * chain, so a group put under maintenance takes its children with it.
 */
public final class Maintenances {

  private Maintenances() {}

  /** How far up the parent chain the question is followed before giving up on a cycle. */
  private static final int MAX_DEPTH = 32;

  public static boolean covers(
      ComponentClient componentClient, String monitorId, String serverTimezone, long now) {
    String current = monitorId;
    for (int depth = 0; depth < MAX_DEPTH && current != null && !current.isEmpty(); depth++) {
      if (directlyCovers(componentClient, current, serverTimezone, now)) {
        return true;
      }
      var row =
          componentClient.forView().method(MonitorListView::byId).invoke(current);
      if (row.isEmpty()) {
        return false;
      }
      String parent = row.get().parent();
      current = parent == null || parent.isEmpty() ? null : parent;
    }
    return false;
  }

  private static boolean directlyCovers(
      ComponentClient componentClient, String monitorId, String serverTimezone, long now) {
    for (MaintenanceWindow window : active(componentClient, serverTimezone, now)) {
      List<String> monitors = monitorsOf(componentClient, window.id());
      if (monitors.contains(monitorId)) {
        return true;
      }
    }
    return false;
  }

  /** Every window that is happening right now. */
  public static List<MaintenanceWindow> active(
      ComponentClient componentClient, String serverTimezone, long now) {
    List<MaintenanceWindow> out = new ArrayList<>();
    for (MaintenanceWindow window : all(componentClient)) {
      if (window.isUnderMaintenance(serverTimezone, now)) {
        out.add(window);
      }
    }
    return out;
  }

  public static List<MaintenanceWindow> all(ComponentClient componentClient) {
    List<MaintenanceWindow> out = new ArrayList<>();
    var rows = componentClient.forView().method(MaintenanceListView::all).invoke();
    for (RecordRow row : rows.entries()) {
      MaintenanceWindow window = parse(row.json());
      if (window != null) {
        out.add(window);
      }
    }
    return out;
  }

  public static MaintenanceWindow parse(String json) {
    try {
      Map<String, Object> fields =
          io.akka.uptimekuma.notifications.Json.MAPPER.readValue(json, Map.class);
      return io.akka.uptimekuma.notifications.Json.MAPPER.convertValue(
          fields, MaintenanceWindow.class);
    } catch (Exception e) {
      return null;
    }
  }

  /** The monitors a window names, which the interface stores on the window itself. */
  @SuppressWarnings("unchecked")
  public static List<String> monitorsOf(ComponentClient componentClient, String maintenanceId) {
    var record =
        componentClient.forKeyValueEntity(maintenanceId).method(MaintenanceEntity::get).invoke();
    Object monitors = record.get("monitors");
    List<String> out = new ArrayList<>();
    if (monitors instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> map) {
          out.add(String.valueOf(map.get("id")));
        } else if (entry != null) {
          out.add(String.valueOf(entry));
        }
      }
    }
    return out;
  }

  /** The windows attached to one status page, for the banner it shows. */
  @SuppressWarnings("unchecked")
  public static List<String> statusPagesOf(ComponentClient componentClient, String maintenanceId) {
    var record =
        componentClient.forKeyValueEntity(maintenanceId).method(MaintenanceEntity::get).invoke();
    Object pages = record.get("statusPages");
    List<String> out = new ArrayList<>();
    if (pages instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> map) {
          out.add(String.valueOf(map.get("id")));
        } else if (entry != null) {
          out.add(String.valueOf(entry));
        }
      }
    }
    return out;
  }
}
