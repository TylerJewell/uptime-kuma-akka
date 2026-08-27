package io.akka.uptimekuma.notifications;

import static io.akka.uptimekuma.notifications.Notify.OK;
import static io.akka.uptimekuma.notifications.Notify.headers;
import static io.akka.uptimekuma.notifications.Notify.string;
import static io.akka.uptimekuma.notifications.Notify.urlEncode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The targets that open and close an incident rather than post a message.
 *
 * <p>What they have in common is that going down and coming back up are different calls: the first
 * raises something a person is paged for, and the second has to reach the same thing and close it.
 * The key that ties the two together — an alias, a deduplication key, an external identifier — is
 * the interesting part of each one.
 */
final class IncidentProviders {

  private IncidentProviders() {}

  static List<Provider> all() {
    return List.of(
        new PagerDuty(),
        new PagerTree(),
        new Opsgenie(),
        new Splunk(),
        new Squadcast(),
        new Signl4(),
        new GrafanaOncall(),
        new GoAlert(),
        new HeiiOnCall(),
        new FlashDuty(),
        new JiraServiceManagement(),
        new Alerta(),
        new AlertNow(),
        new Keep(),
        new ClickUp(),
        new HaloPsa(),
        new Flowtriq(),
        new Notifery(),
        new Pinglet());
  }

  /** The address an on-call target shows, which prefers a host over a URL. */
  private static String monitorUrl(Map<String, Object> monitor) {
    if (monitor == null) {
      return null;
    }
    String hostname = string(monitor, "hostname");
    Object port = Notify.get(monitor, "port");
    if ("port".equals(string(monitor, "type"))) {
      return port != null ? hostname + ":" + port : hostname;
    }
    return hostname != null ? hostname : string(monitor, "url");
  }

  static final class PagerDuty implements Provider {
    @Override
    public String name() {
      return "PagerDuty";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String title;
      String body;
      Map<String, Object> monitor = m;
      String eventAction = "trigger";
      if (h == null) {
        title = "Uptime Kuma Alert";
        body = msg;
        monitor = new LinkedHashMap<>();
        monitor.put("type", "ping");
        monitor.put("url", "Uptime Kuma Test Button");
      } else if (Notify.isUp(h)) {
        title = "Uptime Kuma Monitor ✅ Up";
        body = string(h, "msg");
        eventAction = "resolve";
      } else if (Notify.isDown(h)) {
        title = "Uptime Kuma Monitor 🔴 Down";
        body = string(h, "msg");
      } else {
        title = "Uptime Kuma Alert";
        body = msg;
      }

      if ("resolve".equals(eventAction)) {
        if ("0".equals(c.str("pagerdutyAutoResolve"))) {
          // Configured not to close incidents automatically, so nothing is sent at all.
          return "no action required";
        }
        eventAction = c.str("pagerdutyAutoResolve");
      }

      Object monitorName = Notify.get(monitor, "name");
      String summary =
          monitorName != null
              ? "[" + title + "] [" + monitorName + "] " + body
              : "[" + title + "] " + body;
      Object monitorId = Notify.get(monitor, "id");
      String payload =
          Json.obj()
              .put(
                  "payload",
                  Json.obj()
                      .put("summary", summary)
                      .put("severity", c.str("pagerdutyPriority", "warning"))
                      .put("source", monitorUrl(monitor))
                      .map())
              .put("routing_key", c.str("pagerdutyIntegrationKey"))
              .put("event_action", eventAction)
              .put(
                  "dedup_key",
                  monitorId != null ? "Uptime Kuma/" + monitorId : "Uptime Kuma/test")
              .toString();
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("pagerdutyIntegrationUrl"),
                      headers("Content-Type", "application/json"),
                      payload));
      Notify.requireOk(response, "PagerDuty");
      return response.statusText() != null
          ? "PagerDuty notification succeed: " + response.statusText()
          : OK;
    }
  }

  static final class PagerTree implements Provider {
    @Override
    public String name() {
      return "PagerTree";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String title;
      String eventAction;
      if (h == null) {
        title = msg;
        eventAction = "create";
      } else if (Notify.isUp(h) && "resolve".equals(c.str("pagertreeAutoResolve"))) {
        title = null;
        eventAction = "resolve";
      } else if (Notify.isDown(h)) {
        title = "Uptime Kuma Monitor \"" + string(m, "name") + "\" is DOWN";
        eventAction = "create";
      } else {
        // An up beat with automatic resolution turned off is not news this target carries.
        return null;
      }
      Object monitorId = Notify.get(h, "monitorID");
      String payload =
          Json.obj()
              .put("event_type", eventAction)
              .put("id", monitorId == null ? "uptime-kuma" : monitorId)
              .put("title", title)
              .put("urgency", c.str("pagertreeUrgency"))
              .put("heartbeat", h)
              .put("monitor", m)
              .toString();
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("pagertreeIntegrationUrl"),
                      headers("Content-Type", "application/json"),
                      payload));
      Notify.requireOk(response, "PagerTree");
      return response.statusText() != null
          ? "PagerTree notification succeed: " + response.statusText()
          : OK;
    }
  }

  static final class Opsgenie implements Provider {
    @Override
    public String name() {
      return "Opsgenie";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base =
          "eu".equals(c.str("opsgenieRegion"))
              ? "https://api.eu.opsgenie.com/v2/alerts"
              : "https://api.opsgenie.com/v2/alerts";
      Map<String, String> requestHeaders =
          headers(
              "Content-Type", "application/json",
              "Authorization", "GenieKey " + c.str("opsgenieApiKey"));
      String url = base;
      String body;
      if (h == null) {
        body =
            Json.obj()
                .put("message", msg)
                .put("alias", "uptime-kuma-notification-test")
                .put("source", "Uptime Kuma")
                .put("priority", "P5")
                .toString();
      } else if (Notify.isDown(h)) {
        int priority = c.truthy("opsgeniePriority") ? c.intOf("opsgeniePriority", 3) : 3;
        body =
            Json.obj()
                .put(
                    "message",
                    m != null ? "Uptime Kuma Alert: " + string(m, "name") : "Uptime Kuma Alert")
                .put("alias", string(m, "name"))
                .put("description", msg)
                .put("source", "Uptime Kuma")
                .put("priority", "P" + priority)
                .toString();
      } else if (Notify.isUp(h)) {
        // Closing reaches the alert by the alias the opening call gave it, which is the monitor
        // name — so two monitors sharing a name would close each other's incidents.
        url = base + "/" + urlEncode(string(m, "name")) + "/close?identifierType=alias";
        body = Json.obj().put("source", "Uptime Kuma").toString();
      } else {
        return null;
      }
      Sender.Response response =
          ctx.sender().send(Sender.Request.post(url, requestHeaders, body));
      Notify.requireOk(response, "Opsgenie");
      return OK;
    }
  }

  static final class Splunk implements Provider {
    @Override
    public String name() {
      return "Splunk";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String title;
      String body;
      Map<String, Object> monitor = m;
      String eventAction = "trigger";
      if (h == null) {
        title = "Uptime Kuma Alert";
        body = msg;
        monitor = new LinkedHashMap<>();
        monitor.put("type", "ping");
        monitor.put("url", "Uptime Kuma Test Button");
      } else if (Notify.isUp(h)) {
        title = "Uptime Kuma Monitor ✅ Up";
        body = string(h, "msg");
        eventAction = "recovery";
      } else {
        title = "Uptime Kuma Monitor 🔴 Down";
        body = string(h, "msg");
      }
      if ("recovery".equals(eventAction)) {
        if ("0".equals(c.str("splunkAutoResolve"))) {
          return "No action required";
        }
        eventAction = c.str("splunkAutoResolve");
      } else {
        eventAction = c.str("splunkSeverity");
      }
      String payload =
          Json.obj()
              .put("message_type", eventAction)
              .put("state_message", "[" + title + "] [" + monitorUrl(monitor) + "] " + body)
              .put("entity_display_name", "Uptime Kuma Alert: " + Notify.get(monitor, "name"))
              .put("routing_key", c.str("pagerdutyIntegrationKey"))
              .put("entity_id", "Uptime Kuma/" + Notify.get(monitor, "id"))
              .toString();
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("splunkRestURL"), headers("Content-Type", "application/json"), payload));
      Notify.requireOk(response, "Splunk");
      return response.statusText() != null
          ? "Splunk notification succeed: " + response.statusText()
          : OK;
    }
  }

  static final class Squadcast implements Provider {
    @Override
    public String name() {
      return "squadcast";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body = Json.obj();
      Map<String, Object> tags = new LinkedHashMap<>();
      if (h == null) {
        body.put("message", msg).put("description", "").put("tags", tags);
        body.put("heartbeat", null).put("source", "uptime-kuma");
      } else {
        boolean down = Notify.isDown(h);
        body.put("message", string(m, "name") + (down ? " is DOWN" : " is UP"));
        body.put("description", string(h, "msg"));
        tags.put("AlertAddress", Notify.extractAddress(m));
        Object monitorTags = Notify.get(m, "tags");
        if (monitorTags instanceof List<?> list) {
          for (Object tag : list) {
            if (tag instanceof Map<?, ?> entry) {
              Map<String, Object> value = new LinkedHashMap<>();
              value.put("value", entry.get("value"));
              if (entry.get("color") != null) {
                value.put("color", entry.get("color"));
              }
              tags.put(String.valueOf(entry.get("name")), value);
            }
          }
        }
        body.put("tags", tags);
        body.put("heartbeat", h);
        body.put("source", "uptime-kuma");
        body.put("event_id", Notify.get(h, "monitorID"));
        body.put("status", down ? "trigger" : "resolve");
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("squadcastWebhookURL"),
                  headers("Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }
  }

  static final class Signl4 implements Provider {
    @Override
    public String name() {
      return "SIGNL4";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body =
          Json.obj()
              .put("heartbeat", h)
              .put("monitor", m)
              .put("msg", msg)
              .put("X-S4-SourceSystem", "UptimeKuma")
              .put("monitorUrl", Notify.extractAddress(m));
      if (h == null) {
        body.put("title", "Uptime Kuma Alert").put("message", msg);
      } else if (Notify.isUp(h)) {
        body.put("title", "Uptime Kuma Monitor ✅ Up")
            // The source reads a key the monitor object does not carry, so this identifier is
            // the same for every monitor. Reproduced: changing it would send a different key
            // than the source does for the same incident.
            .put("X-S4-ExternalID", "UptimeKuma-" + Notify.get(m, "monitorID"))
            .put("X-S4-Status", "resolved");
      } else if (Notify.isDown(h)) {
        body.put("title", "Uptime Kuma Monitor 🔴 Down")
            .put("X-S4-ExternalID", "UptimeKuma-" + Notify.get(m, "monitorID"))
            .put("X-S4-Status", "new");
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("webhookURL"),
                  headers("Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }
  }

  static final class GrafanaOncall implements Provider {
    @Override
    public String name() {
      return "GrafanaOncall";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      if (!c.truthy("GrafanaOncallURL")) {
        throw new Exception("GrafanaOncallURL cannot be empty");
      }
      String body;
      if (h == null) {
        body =
            Json.obj()
                .put("title", "General notification")
                .put("message", msg)
                .put("state", "alerting")
                .toString();
      } else if (Notify.isDown(h)) {
        body =
            Json.obj()
                .put("title", string(m, "name") + " is down")
                .put("message", string(h, "msg"))
                .put("state", "alerting")
                .toString();
      } else if (Notify.isUp(h)) {
        body =
            Json.obj()
                .put("title", string(m, "name") + " is up")
                .put("message", string(h, "msg"))
                .put("state", "ok")
                .toString();
      } else {
        return null;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("GrafanaOncallURL"), headers("Content-Type", "application/json"), body));
      return OK;
    }
  }

  static final class GoAlert implements Provider {
    @Override
    public String name() {
      return "GoAlert";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body = Json.obj().put("summary", msg);
      if (h != null && Notify.isUp(h)) {
        body.put("action", "close");
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("goAlertBaseURL") + "/api/v2/generic/incoming?token=" + c.str("goAlertToken"),
                      headers("Content-Type", "multipart/form-data"),
                      body.toString()));
      if (!response.ok()) {
        throw new Exception(
            response.body() == null || response.body().isEmpty()
                ? "Error without response"
                : response.body());
      }
      return OK;
    }
  }

  static final class HeiiOnCall implements Provider {
    @Override
    public String name() {
      return "HeiiOnCall";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base = "https://heiioncall.com/triggers/" + c.str("heiiOnCallTriggerId") + "/";
      Map<String, Object> payload = h == null ? new LinkedHashMap<>() : new LinkedHashMap<>(h);
      if (ctx.primaryBaseURL() != null && m != null) {
        payload.put(
            "url", ctx.primaryBaseURL() + Context.monitorRelativeUrl(Notify.get(m, "id")));
      }
      if (h == null) {
        payload.put("msg", msg);
      }
      String endpoint;
      if (h == null || Notify.isDown(h)) {
        endpoint = "alert";
      } else if (Notify.isUp(h)) {
        endpoint = "resolve";
      } else {
        return null;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  base + endpoint,
                  headers(
                      "Accept", "application/json",
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("heiiOnCallApiKey")),
                  Json.write(payload)));
      return OK;
    }
  }

  static final class FlashDuty implements Provider {
    @Override
    public String name() {
      return "FlashDuty";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String title;
      String body;
      Map<String, Object> monitor = m;
      String eventStatus;
      if (h == null) {
        title = "Uptime Kuma Alert";
        body = msg;
        monitor = new LinkedHashMap<>();
        monitor.put("type", "ping");
        monitor.put("url", msg);
        monitor.put("name", "https://flashcat.cloud");
        eventStatus = "Info";
      } else if (Notify.isUp(h)) {
        title = "Uptime Kuma Monitor ✅ Up";
        body = string(h, "msg");
        eventStatus = "Ok";
      } else {
        title = "Uptime Kuma Monitor 🔴 Down";
        body = string(h, "msg");
        eventStatus = c.str("flashdutySeverity");
      }
      Map<String, Object> labels = new LinkedHashMap<>();
      labels.put("resource", monitorUrl(monitor));
      labels.put("check", Notify.get(monitor, "name"));
      Object tags = Notify.get(monitor, "tags");
      if (tags instanceof List<?> list) {
        for (Object tag : list) {
          if (tag instanceof Map<?, ?> entry) {
            labels.put(String.valueOf(entry.get("name")), entry.get("value"));
          }
        }
      }
      Object monitorId = Notify.get(monitor, "id");
      String payload =
          Json.obj()
              .put("description", "[" + title + "] [" + Notify.get(monitor, "name") + "] " + body)
              .put("title", title)
              .put("event_status", eventStatus == null ? "Info" : eventStatus)
              .put(
                  "alert_key",
                  monitorId != null
                      ? String.valueOf(monitorId)
                      : Long.toString((long) (Math.random() * 1e9), 36))
              .put("labels", labels)
              .toString();
      String key = c.str("flashdutyIntegrationKey");
      String url =
          key != null && key.startsWith("http")
              ? key
              : "https://api.flashcat.cloud/event/push/alert/standard?integration_key=" + key;
      Sender.Response response =
          ctx.sender()
              .send(Sender.Request.post(url, headers("Content-Type", "application/json"), payload));
      Notify.requireOk(response, "FlashDuty");
      return response.statusText() != null
          ? "FlashDuty notification succeed: " + response.statusText()
          : OK;
    }
  }

  static final class JiraServiceManagement implements Provider {
    @Override
    public String name() {
      return "JiraServiceManagement";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base = "https://api.atlassian.com/jsm/ops/api/" + c.str("jsmCloudId") + "/v1";
      Map<String, String> requestHeaders =
          headers(
              "Content-Type", "application/json",
              "Accept", "application/json",
              "Authorization", Notify.basic(c.str("jsmEmail"), c.str("jsmApiToken")));
      Sender.Response response;
      if (h == null) {
        response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        base + "/alerts",
                        requestHeaders,
                        Json.obj()
                            .put("message", msg)
                            .put("alias", "uptime-kuma-notification-test")
                            .put("source", "Uptime Kuma")
                            .put("priority", "P5")
                            .put("tags", Json.array("Uptime Kuma"))
                            .toString()));
      } else if (Notify.isDown(h)) {
        int priority = c.intOf("jsmPriority", 3);
        response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        base + "/alerts",
                        requestHeaders,
                        Json.obj()
                            .put(
                                "message",
                                m != null
                                    ? "Uptime Kuma Alert: " + string(m, "name")
                                    : "Uptime Kuma Alert")
                            .put("alias", string(m, "name"))
                            .put("description", msg)
                            .put("source", "Uptime Kuma")
                            .put("priority", "P" + priority)
                            .put("tags", Json.array("Uptime Kuma"))
                            .toString()));
      } else if (Notify.isUp(h)) {
        Sender.Response lookup =
            ctx.sender()
                .send(
                    Sender.Request.get(
                        base + "/alerts/alias?alias=" + urlEncode(string(m, "name")),
                        requestHeaders));
        Object alertId = lookup.json().get("id");
        response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        base + "/alerts/" + alertId + "/close",
                        requestHeaders,
                        Json.obj().put("source", "Uptime Kuma").toString()));
      } else {
        return null;
      }
      Notify.requireOk(response, "Jira Service Management");
      return OK;
    }
  }

  static final class Alerta implements Provider {
    @Override
    public String name() {
      return "alerta";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body = Json.obj();
      if (h == null) {
        body.put("event", "msg")
            .put("text", msg)
            .put("group", "uptimekuma-msg")
            .put("resource", "Message");
      } else if (Notify.isDown(h) || Notify.isUp(h)) {
        boolean down = Notify.isDown(h);
        // The correlate list is written and then overwritten by the shared block below, so what
        // reaches the server is always empty. Reproduced.
        body.put("correlate", new ArrayList<>())
            .put("event", string(m, "type"))
            .put("group", "uptimekuma-" + string(m, "type"))
            .put("resource", string(m, "name"))
            .put("text", "Service " + string(m, "type") + (down ? " is down." : " is up."));
        body.put(
            "severity",
            down ? c.str("alertaAlertState") : c.str("alertaRecoverState"));
      } else {
        return OK;
      }
      body.put("environment", c.str("alertaEnvironment"));
      if (h == null) {
        body.put("severity", "critical").put("correlate", new ArrayList<>());
      }
      body.put("service", Json.array("UptimeKuma"))
          .put("value", "Timeout")
          .put("tags", Json.array("uptimekuma"))
          .put("attributes", new LinkedHashMap<String, Object>())
          .put("origin", "uptimekuma")
          .put("type", "exceptionAlert");
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("alertaApiEndpoint"),
                  headers(
                      "Content-Type", "application/json;charset=UTF-8",
                      "Authorization", "Key " + c.str("alertaApiKey")),
                  body.toString()));
      return OK;
    }
  }

  static final class AlertNow implements Provider {
    @Override
    public String name() {
      return "AlertNow";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String status = "open";
      String eventType = "ERROR";
      // The day, so every alert raised on one day about one monitor is the same incident.
      String eventId = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "");
      String textMsg = "";
      if (h != null && Notify.isUp(h)) {
        textMsg = "[" + string(h, "name") + "] ✅ Application is back online";
        status = "close";
        eventType = "INFO";
        eventId =
            eventId
                + "_"
                + (string(h, "name") == null ? "" : string(h, "name").replaceAll("\\s", ""));
      } else if (h != null && Notify.isDown(h)) {
        textMsg = "[" + string(h, "name") + "] 🔴 Application went down";
      }
      textMsg = textMsg + " - " + msg;
      if (ctx.primaryBaseURL() != null && m != null) {
        textMsg =
            textMsg
                + " >> "
                + ctx.primaryBaseURL()
                + Context.monitorRelativeUrl(Notify.get(m, "id"));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("alertNowWebhookURL"),
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("summary", textMsg)
                      .put("status", status)
                      .put("event_type", eventType)
                      .put("event_id", eventId)
                      .toString()));
      return OK;
    }
  }

  static final class Keep implements Provider {
    @Override
    public String name() {
      return "Keep";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  Notify.stripTrailingSlash(c.str("webhookURL")) + "/alerts/event/uptimekuma",
                  headers("x-api-key", c.str("webhookAPIKey"), "content-type", "application/json"),
                  Json.obj().put("heartbeat", h).put("monitor", m).put("msg", msg).toString()));
      return OK;
    }
  }

  static final class ClickUp implements Provider {
    @Override
    public String name() {
      return "ClickUp";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      StringBuilder content = new StringBuilder(msg);
      if (h != null) {
        content
            .append("\n\n**Time (")
            .append(string(h, "timezone"))
            .append("):** ")
            .append(string(h, "localDateTime"));
      }
      String address = Notify.extractAddress(m);
      if (address != null && !address.isEmpty() && !c.truthy("clickupDisableUrl")) {
        content.append("\n**Address:** ").append(address);
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.clickup.com/api/v3/workspaces/"
                      + c.str("clickupWorkspaceId")
                      + "/chat/channels/"
                      + c.str("clickupChannelId")
                      + "/messages",
                  headers(
                      "Authorization", c.str("clickupToken"),
                      "Content-Type", "application/json"),
                  Json.obj()
                      .put("type", "message")
                      .put("content", content.toString())
                      .put("content_format", "text/md")
                      .toString()));
      return OK;
    }
  }

  static final class HaloPsa implements Provider {
    @Override
    public String name() {
      return "HaloPSA";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String status = "UNKNOWN";
      if (Notify.isUp(h)) {
        status = "UP";
      } else if (Notify.isDown(h)) {
        status = "DOWN";
      } else if (m == null && h != null) {
        status = "NOTIFICATION";
      }
      Map<String, String> requestHeaders = headers("Content-Type", "application/json");
      if (c.truthy("haloUsername") && c.truthy("haloPassword")) {
        requestHeaders.put(
            "Authorization", Notify.basic(c.str("haloUsername"), c.str("haloPassword")));
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("halowebhookurl"),
                      requestHeaders,
                      Json.obj()
                          .put("title", "Uptime Kuma Alert")
                          .put("status", status)
                          .put("monitor", m == null ? "No Monitor" : string(m, "name"))
                          .put("monitor_id", m == null ? null : Notify.get(m, "id"))
                          .put("message", msg)
                          .put("timestamp", java.time.Instant.now().toString())
                          .put(
                              "uptime_kuma_version",
                              ctx.appVersion() == null ? "unknown" : ctx.appVersion())
                          .toString()));
      if (response.status() != 200 && response.status() != 201 && response.status() != 204) {
        throw new Exception(
            "Received unexpected status code "
                + response.status()
                + " from notification provider HaloPSA");
      }
      return "Sent successfully.";
    }
  }

  static final class Flowtriq implements Provider {
    @Override
    public String name() {
      return "Flowtriq";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String status = "info";
      if (Notify.isDown(h)) {
        status = "down";
      } else if (Notify.isUp(h)) {
        status = "up";
      }
      Json.Obj body =
          Json.obj()
              .put("source", "uptime-kuma")
              .put("status", status)
              .put("monitor", m == null ? "Unknown" : string(m, "name"))
              .put("msg", msg);
      if (h != null) {
        body.put(
            "heartbeat",
            Json.obj()
                .put("status", Notify.get(h, "status"))
                .put("time", Notify.get(h, "time"))
                .put("ping", Notify.get(h, "ping"))
                .put("msg", Notify.get(h, "msg"))
                .put("timezone", Notify.get(h, "timezone"))
                .map());
      }
      if (m != null) {
        body.put(
            "monitorInfo",
            Json.obj()
                .put("id", Notify.get(m, "id"))
                .put("name", Notify.get(m, "name"))
                .put("type", Notify.get(m, "type"))
                .put("url", Notify.get(m, "url"))
                .put("hostname", Notify.get(m, "hostname"))
                .put("port", Notify.get(m, "port"))
                .map());
      }
      Map<String, String> requestHeaders = headers("Content-Type", "application/json");
      if (c.truthy("flowtriqApiKey")) {
        requestHeaders.put("X-API-Key", c.str("flowtriqApiKey"));
      }
      Sender.Response response =
          ctx.sender()
              .send(Sender.Request.post(c.str("flowtriqWebhookUrl"), requestHeaders, body.toString()));
      if (!response.ok()) {
        throw new Exception(
            "Flowtriq notification failed with status code " + response.status());
      }
      return OK;
    }
  }

  static final class Notifery implements Provider {
    @Override
    public String name() {
      return "notifery";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String message = msg;
      if (ctx.primaryBaseURL() != null && m != null) {
        message =
            message
                + "\n\nMonitor: "
                + ctx.primaryBaseURL()
                + Context.monitorRelativeUrl(Notify.get(m, "id"));
      }
      Json.Obj body =
          Json.obj()
              .put("title", c.str("notiferyTitle", "Uptime Kuma Alert"))
              .put("message", message)
              .putIfPresent("group", c.str("notiferyGroup"));
      if (h != null) {
        body.put("code", Notify.isUp(h) ? 0 : 1);
        if (Notify.get(h, "ping") != null) {
          body.put("duration", Notify.get(h, "ping"));
        }
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.notifery.com/event",
                  headers(
                      "Content-Type", "application/json",
                      "x-api-key", c.str("notiferyApiKey")),
                  body.toString()));
      return OK;
    }
  }

  static final class Pinglet implements Provider {
    @Override
    public String name() {
      return "pinglet";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  Notify.stripTrailingSlash(c.str("pingletPublishUrl")) + "?rewrite=uptimekuma",
                  headers(
                      "Authorization", "Bearer " + c.str("pingletApiKey"),
                      "Content-Type", "application/json"),
                  Json.obj().put("heartbeat", h).put("monitor", m).put("msg", msg).toString()));
      return OK;
    }
  }
}
