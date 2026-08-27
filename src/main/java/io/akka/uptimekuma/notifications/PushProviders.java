package io.akka.uptimekuma.notifications;

import static io.akka.uptimekuma.notifications.Notify.OK;
import static io.akka.uptimekuma.notifications.Notify.fields;
import static io.akka.uptimekuma.notifications.Notify.form;
import static io.akka.uptimekuma.notifications.Notify.headers;
import static io.akka.uptimekuma.notifications.Notify.string;
import static io.akka.uptimekuma.notifications.Notify.urlEncode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The targets that raise a notification on a device, and the generic webhook shapes. */
final class PushProviders {

  private PushProviders() {}

  static List<Provider> all() {
    return List.of(
        new Pushover(),
        new Pushbullet(),
        new Pushy(),
        new PushDeer(),
        new PushPlus(),
        new Gotify(),
        new Ntfy(),
        new Bark(),
        new PushByTechulus(),
        new WPush(),
        new WxPusher(),
        new SpugPush(),
        new ServerChan(),
        new Gorush(),
        new LunaSea(),
        new Webpush(),
        new HomeAssistant(),
        new Apprise(),
        new GoogleSheets(),
        new Webhook(),
        new Bitrix24(),
        new Yzj());
  }

  static final class Pushover implements Provider {
    @Override
    public String name() {
      return "pushover";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Map<String, String> body =
          fields(
              "message", msg,
              "user", c.str("pushoveruserkey"),
              "token", c.str("pushoverapptoken"),
              "sound", c.str("pushoversounds"),
              "priority", c.str("pushoverpriority"),
              "title", c.str("pushovertitle"),
              // Two minutes between retries for an hour, which is what an emergency priority
              // needs and is ignored at every other priority.
              "retry", "30",
              "expire", "3600",
              "html", "1");
      if (ctx.primaryBaseURL() != null && m != null) {
        body.put("url", ctx.primaryBaseURL() + Context.monitorRelativeUrl(Notify.get(m, "id")));
        body.put("url_title", "Link to Monitor");
      }
      if (c.truthy("pushoverdevice")) {
        body.put("device", c.str("pushoverdevice"));
      }
      if (c.truthy("pushoverttl")) {
        body.put("ttl", c.str("pushoverttl"));
      }
      if (h != null) {
        if (Notify.isUp(h) && c.truthy("pushoversounds_up")) {
          body.put("sound", c.str("pushoversounds_up"));
        }
        body.put(
            "message",
            msg
                + "\n<b>Time ("
                + string(h, "timezone")
                + ")</b>: "
                + string(h, "localDateTime"));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.pushover.net/1/messages.json",
                  headers("Content-Type", "application/x-www-form-urlencoded"),
                  form(body)));
      return OK;
    }
  }

  static final class Pushbullet implements Provider {
    @Override
    public String name() {
      return "pushbullet";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body;
      if (h == null) {
        body = Json.obj().put("type", "note").put("title", "Uptime Kuma Alert").put("body", msg);
      } else if (Notify.isDown(h)) {
        body = note(m, h, "[🔴 Down] ");
      } else if (Notify.isUp(h)) {
        body = note(m, h, "[✅ Up] ");
      } else {
        return OK;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.pushbullet.com/v2/pushes",
                  headers(
                      "Access-Token", c.str("pushbulletAccessToken"),
                      "Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }

    private static Json.Obj note(Map<String, Object> m, Map<String, Object> h, String badge) {
      return Json.obj()
          .put("type", "note")
          .put("title", "UptimeKuma Alert: " + string(m, "name"))
          .put(
              "body",
              badge
                  + string(h, "msg")
                  + "\nTime ("
                  + string(h, "timezone")
                  + "): "
                  + string(h, "localDateTime"));
    }
  }

  static final class Pushy implements Provider {
    @Override
    public String name() {
      return "pushy";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.pushy.me/push?api_key=" + c.str("pushyAPIKey"),
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("to", c.str("pushyToken"))
                      .put("data", Json.obj().put("message", "Uptime-Kuma").map())
                      .put(
                          "notification",
                          Json.obj()
                              .put("body", msg)
                              .put("badge", 1)
                              .put("sound", "ping.aiff")
                              .map())
                      .toString()));
      return OK;
    }
  }

  static final class PushDeer implements Provider {
    @Override
    public String name() {
      return "PushDeer";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      boolean full = msg != null && m != null && h != null;
      String title = "## Uptime Kuma Message";
      if (full && Notify.isUp(h)) {
        title = "## Uptime Kuma: " + string(m, "name") + " up";
      } else if (full && Notify.isDown(h)) {
        title = "## Uptime Kuma: " + string(m, "name") + " down";
      }
      String server =
          Notify.stripTrailingSlashes(c.str("pushdeerServer", "https://api2.pushdeer.com"));
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      server.trim() + "/message/push",
                      headers("Content-Type", "application/json"),
                      Json.obj()
                          .put("pushkey", c.str("pushdeerKey"))
                          .put("text", title)
                          // Markdown needs a blank line between paragraphs.
                          .put("desp", msg.replace("\n", "\n\n"))
                          .put("type", "markdown")
                          .toString()));
      Map<String, Object> body = response.json();
      if (body.containsKey("error")) {
        throw new Exception(String.valueOf(body.get("error")));
      }
      Object content = body.get("content");
      Object result = content instanceof Map<?, ?> map ? map.get("result") : null;
      if (result instanceof List<?> list) {
        if (list.isEmpty()) {
          throw new Exception("Invalid PushDeer key");
        }
        Map<String, Object> first;
        try {
          first = Json.MAPPER.readValue(String.valueOf(list.get(0)), Map.class);
        } catch (Exception e) {
          throw new Exception("Unknown error");
        }
        if (!"ok".equals(first.get("success"))) {
          throw new Exception("Unknown error");
        }
      }
      return OK;
    }
  }

  static final class PushPlus implements Provider {
    @Override
    public String name() {
      return "PushPlus";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://www.pushplus.plus/send",
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("token", c.str("pushPlusSendKey"))
                      .put("title", statusTitle(h, m))
                      .put("content", msg)
                      .put("template", "html")
                      .toString()));
      return OK;
    }
  }

  /** The three-way title four of these targets share. */
  static String statusTitle(Map<String, Object> h, Map<String, Object> m) {
    if (h == null) {
      return "UptimeKuma Message";
    }
    if (Notify.isUp(h)) {
      return "UptimeKuma Monitor Up " + string(m, "name");
    }
    if (Notify.isDown(h)) {
      return "UptimeKuma Monitor Down " + string(m, "name");
    }
    return "UptimeKuma Message";
  }

  static final class Gotify implements Provider {
    @Override
    public String name() {
      return "gotify";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String server = Notify.stripTrailingSlash(c.str("gotifyserverurl"));
      ctx.sender()
          .send(
              Sender.Request.post(
                  server + "/message?token=" + c.str("gotifyapplicationToken"),
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("message", msg)
                      .put("priority", c.truthy("gotifyPriority") ? c.intOf("gotifyPriority", 8) : 8)
                      .put("title", "Uptime-Kuma")
                      .toString()));
      return OK;
    }
  }

  static final class Ntfy implements Provider {
    @Override
    public String name() {
      return "ntfy";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Map<String, String> requestHeaders = headers("Content-Type", "application/json");
      String auth = c.str("ntfyAuthenticationMethod");
      if ("usernamePassword".equals(auth)) {
        requestHeaders.put(
            "Authorization", Notify.basic(c.str("ntfyusername"), c.str("ntfypassword")));
      } else if ("accessToken".equals(auth)) {
        requestHeaders.put("Authorization", "Bearer " + c.str("ntfyaccesstoken"));
      }
      if (c.truthy("ntfyCall")) {
        requestHeaders.put("X-Call", c.str("ntfyCall"));
      }

      int priority = c.truthy("ntfyPriority") ? c.intOf("ntfyPriority", 4) : 4;
      List<Object> tags = new ArrayList<>();
      String title;
      String message;
      if (h == null) {
        title =
            (m != null && string(m, "name") != null ? string(m, "name") : c.str("ntfytopic"))
                + " [Uptime-Kuma]";
        message = msg;
        tags.add("test_tube");
      } else {
        String statusWord = "unknown";
        if (Notify.isDown(h)) {
          tags.add("red_circle");
          statusWord = "Down";
          // Down is worth one step above the configured level unless it is already the highest.
          priority =
              c.truthy("ntfyPriorityDown")
                  ? c.intOf("ntfyPriorityDown", priority)
                  : (priority == 5 ? priority : priority + 1);
        } else if (Notify.isUp(h)) {
          tags.add("green_circle");
          statusWord = "Up";
        }
        title = string(m, "name") + " " + statusWord + " [Uptime-Kuma]";
        message = string(h, "msg");
        Object monitorTags = Notify.get(m, "tags");
        if (monitorTags instanceof List<?> list) {
          for (Object tag : list) {
            if (tag instanceof Map<?, ?> entry) {
              Object value = entry.get("value");
              tags.add(
                  value == null || "".equals(value)
                      ? String.valueOf(entry.get("name"))
                      : entry.get("name") + ": " + value);
            }
          }
        }
      }
      if (c.truthy("ntfyUseTemplate")) {
        String customTitle = c.str("ntfyCustomTitle");
        if (customTitle != null && !customTitle.trim().isEmpty()) {
          title = Notify.renderTemplate(customTitle, msg, m, h);
        }
        String customMessage = c.str("ntfyCustomMessage");
        if (customMessage != null && !customMessage.trim().isEmpty()) {
          message = Notify.renderTemplate(customMessage, msg, m, h);
        }
      }

      Json.Obj body =
          Json.obj()
              .put("topic", c.str("ntfytopic"))
              .put("message", message)
              .put("priority", priority)
              .put("title", title)
              .put("tags", tags);
      String monitorUrl = string(m, "url");
      if (h != null && monitorUrl != null && !"https://".equals(monitorUrl)) {
        body.put(
            "actions",
            Json.array(
                Json.obj()
                    .put("action", "view")
                    .put("label", "Open " + string(m, "name"))
                    .put("url", monitorUrl)
                    .map()));
      }
      if (c.truthy("ntfyIcon")) {
        body.put("icon", c.str("ntfyIcon"));
      }
      ctx.sender()
          .send(Sender.Request.post(c.str("ntfyserverurl"), requestHeaders, body.toString()));
      return OK;
    }
  }

  static final class Bark implements Provider {
    private static final String ICON =
        "https://github.com/louislam/uptime-kuma/raw/master/public/icon.png";

    @Override
    public String name() {
      return "Bark";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      if (msg == null) {
        return null;
      }
      String title = "UptimeKuma Message";
      if (h != null && Notify.isUp(h)) {
        title = "UptimeKuma Monitor Up";
      } else if (h != null && Notify.isDown(h)) {
        title = "UptimeKuma Monitor Down";
      }
      String endpoint = Notify.stripTrailingSlash(c.str("barkEndpoint"));
      String group = c.raw("barkGroup") != null ? c.str("barkGroup") : "UptimeKuma";
      String sound = c.raw("barkSound") != null ? c.str("barkSound") : "telegraph";
      Sender.Response response;
      String apiVersion = c.str("apiVersion");
      if (apiVersion == null || "v1".equals(apiVersion)) {
        // Version one carries everything in the path and the query, and the source does not
        // encode the group or the sound.
        String url =
            endpoint
                + "/"
                + urlEncode(title)
                + "/"
                + urlEncode(msg)
                + "?icon="
                + ICON
                + "&group="
                + group
                + "&sound="
                + sound;
        response = ctx.sender().send(Sender.Request.get(url, headers()));
      } else {
        response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        endpoint,
                        headers("Content-Type", "application/json"),
                        Json.obj()
                            .put("title", title)
                            .put("body", msg)
                            .put("icon", ICON)
                            .put("sound", sound)
                            .put("group", group)
                            .toString()));
      }
      if (response.status() == 0) {
        throw new Exception("Bark notification failed with invalid response!");
      }
      if (!response.ok()) {
        throw new Exception("Bark notification failed with status code " + response.status());
      }
      return response.statusText() != null
          ? "Bark notification succeed: " + response.statusText()
          : "Successes!";
    }
  }

  static final class PushByTechulus implements Provider {
    @Override
    public String name() {
      return "PushByTechulus";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body =
          Json.obj()
              .put("title", c.truthy("pushTitle") ? c.str("pushTitle") : "Uptime-Kuma")
              .put("body", msg)
              .put(
                  "timeSensitive",
                  c.raw("pushTimeSensitive") == null || c.truthy("pushTimeSensitive"));
      if (c.truthy("pushChannel")) {
        body.put("channel", c.str("pushChannel"));
      }
      if (c.truthy("pushSound")) {
        body.put("sound", c.str("pushSound"));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://push.techulus.com/api/v1/notify/" + c.str("pushAPIKey"),
                  headers("Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }
  }

  static final class WPush implements Provider {
    @Override
    public String name() {
      return "WPush";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://api.wpush.cn/api/v1/send",
                      headers("Content-Type", "application/json"),
                      Json.obj()
                          .put("title", statusTitle(h, m))
                          .put("content", msg)
                          .put("apikey", c.str("wpushAPIkey"))
                          .put("channel", c.str("wpushChannel"))
                          .toString()));
      Map<String, Object> body = response.json();
      Object code = body.get("code");
      if (code == null || Double.parseDouble(String.valueOf(code)) != 0) {
        throw new Exception(String.valueOf(body.get("message")));
      }
      return OK;
    }
  }

  static final class WxPusher implements Provider {
    /** How many recipients one call may name. */
    private static final int SPT_PER_REQUEST = 10;

    @Override
    public String name() {
      return "WxPusher";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      List<String> spts = Notify.splitList(c.str("wxpusherSPT"), ",");
      if (spts.isEmpty()) {
        throw new Exception("No WxPusher SPT is configured");
      }
      String summary = statusTitle(h, m);
      summary = summary.substring(0, Math.min(summary.length(), 100));
      for (int i = 0; i < spts.size(); i += SPT_PER_REQUEST) {
        List<String> batch = spts.subList(i, Math.min(i + SPT_PER_REQUEST, spts.size()));
        Sender.Response response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        "https://wxpusher.zjiecode.com/api/send/message/simple-push",
                        headers("Content-Type", "application/json"),
                        Json.obj()
                            .put("content", msg)
                            .put("summary", summary)
                            .put("contentType", 1)
                            .put("sptList", batch)
                            .toString()));
        Object code = response.json().get("code");
        if (code == null || Double.parseDouble(String.valueOf(code)) != 1000) {
          throw new Exception(String.valueOf(response.json().get("msg")));
        }
      }
      return OK;
    }
  }

  static final class SpugPush implements Provider {
    @Override
    public String name() {
      return "SpugPush";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String title = "Uptime Kuma Message";
      String content = msg;
      if (h != null && Notify.isUp(h)) {
        title = "UptimeKuma 「" + string(m, "name") + "」 is Up";
        content = "[✅ Up] " + string(h, "msg");
      } else if (h != null && Notify.isDown(h)) {
        title = "UptimeKuma 「" + string(m, "name") + "」 is Down";
        content = "[🔴 Down] " + string(h, "msg");
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://push.spug.cc/send/" + c.str("templateKey"),
                  headers("Content-Type", "application/json"),
                  Json.obj().put("title", title).put("content", content).toString()));
      return OK;
    }
  }

  static final class ServerChan implements Provider {
    @Override
    public String name() {
      return "ServerChan";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String key = String.valueOf(c.str("serverChanSendKey"));
      // A key of the newer shape names the node that serves it in its own first digits.
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("^sctp(\\d+)t", java.util.regex.Pattern.CASE_INSENSITIVE)
              .matcher(key);
      String url =
          matcher.find()
              ? "https://" + matcher.group(1) + ".push.ft07.com/send/" + key + ".send"
              : "https://sctapi.ftqq.com/" + key + ".send";
      ctx.sender()
          .send(
              Sender.Request.post(
                  url,
                  headers("Content-Type", "application/json"),
                  Json.obj().put("title", statusTitle(h, m)).put("desp", msg).toString()));
      return OK;
    }
  }

  static final class Gorush implements Provider {
    @Override
    public String name() {
      return "gorush";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Integer platform =
          switch (String.valueOf(c.str("gorushPlatform"))) {
            case "ios" -> 1;
            case "android" -> 2;
            case "huawei" -> 3;
            default -> null;
          };
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("gorushServerURL") + "/api/push",
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put(
                          "notifications",
                          Json.array(
                              Json.obj()
                                  .put("tokens", Json.array(c.str("gorushDeviceToken")))
                                  .put("platform", platform)
                                  .put("message", msg)
                                  .put("title", c.str("gorushTitle"))
                                  .put("priority", c.str("gorushPriority"))
                                  .put("retry", c.intOf("gorushRetry", 0))
                                  .put("topic", c.str("gorushTopic"))
                                  .map()))
                      .toString()));
      return OK;
    }
  }

  static final class LunaSea implements Provider {
    @Override
    public String name() {
      return "lunasea";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String target =
          "user".equals(c.str("lunaseaTarget"))
              ? "user/" + c.str("lunaseaUserID")
              : "device/" + c.str("lunaseaDevice");
      Json.Obj body;
      if (h == null) {
        body = Json.obj().put("title", "Uptime Kuma Alert").put("body", msg);
      } else if (Notify.isDown(h)) {
        body = alert(m, h, "[🔴 Down] ");
      } else if (Notify.isUp(h)) {
        body = alert(m, h, "[✅ Up] ");
      } else {
        return null;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://notify.lunasea.app/v1/custom/" + target,
                  headers("Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }

    private static Json.Obj alert(Map<String, Object> m, Map<String, Object> h, String badge) {
      return Json.obj()
          .put("title", "UptimeKuma Alert: " + string(m, "name"))
          .put(
              "body",
              badge
                  + string(h, "msg")
                  + "\nTime ("
                  + string(h, "timezone")
                  + "): "
                  + string(h, "localDateTime"));
    }
  }

  static final class Webpush implements Provider {
    @Override
    public String name() {
      return "Webpush";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      // A browser push is an encrypted payload signed with the server's own key pair and posted
      // to the endpoint the browser handed out — the encryption, not the request shape, is the
      // whole of the protocol. This rebuild does not implement it, and says so rather than
      // reporting a delivery that did not happen. Declared in the README.
      throw new Exception(
          "The Webpush target is not delivered by this port: it needs the Web Push encryption "
              + "and VAPID signing this rebuild does not implement.");
    }
  }

  static final class HomeAssistant implements Provider {
    @Override
    public String name() {
      return "HomeAssistant";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String service = c.str("notificationService", "notify");
      Json.Obj body = Json.obj().put("title", "Uptime Kuma").put("message", msg);
      if (!"persistent_notification".equals(service)) {
        body.put(
            "data",
            Json.obj()
                .put("name", string(m, "name"))
                .put("status", Notify.get(h, "status"))
                .put("channel", "Uptime Kuma")
                .put(
                    "icon_url",
                    "https://github.com/louislam/uptime-kuma/blob/master/public/icon.png?raw=true")
                .map());
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  Notify.stripTrailingSlashes(c.str("homeAssistantUrl").trim())
                      + "/api/services/notify/"
                      + service,
                  headers(
                      "Authorization", "Bearer " + c.str("longLivedAccessToken"),
                      "Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }
  }

  static final class Apprise implements Provider {
    @Override
    public String name() {
      return "apprise";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      List<String> command = new ArrayList<>(List.of("apprise", "-vv", "-b", msg));
      if (c.truthy("title")) {
        command.add("-t");
        command.add(c.str("title"));
      }
      command.add(c.str("appriseURL"));
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      String output;
      try {
        Process process = builder.start();
        output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
      } catch (Exception e) {
        output = "ERROR: maybe apprise not found";
      }
      if (output.isEmpty()) {
        return "No output from apprise";
      }
      if (output.contains("ERROR")) {
        throw new Exception(output);
      }
      return OK;
    }

    /** Whether the command this target shells out to is on the path. */
    public static boolean available() {
      try {
        Process process =
            new ProcessBuilder(
                    System.getProperty("os.name").toLowerCase().contains("win")
                        ? "where"
                        : "which",
                    "apprise")
                .start();
        return process.waitFor() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  }

  static final class GoogleSheets implements Provider {
    @Override
    public String name() {
      return "GoogleSheets";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String status = "N/A";
      String responseTime = "N/A";
      String statusCode = "N/A";
      if (h != null) {
        status = Notify.isDown(h) ? "DOWN" : Notify.isUp(h) ? "UP" : "UNKNOWN";
        Object ping = Notify.get(h, "ping");
        responseTime = ping == null ? "N/A" : String.valueOf(ping);
        Object code = Notify.get(h, "statusCode");
        statusCode = code == null ? "N/A" : String.valueOf(code);
      }
      String monitorName = "N/A";
      String monitorUrl = "N/A";
      if (m != null) {
        monitorName = string(m, "name") == null ? "N/A" : string(m, "name");
        String address = Notify.extractAddress(m);
        monitorUrl = address == null || address.isEmpty() ? "N/A" : address;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("googleSheetsWebhookUrl"),
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("timestamp", java.time.Instant.now().toString())
                      .put("status", status)
                      .put("monitorName", monitorName)
                      .put("monitorUrl", monitorUrl)
                      .put("message", msg)
                      .put("responseTime", responseTime)
                      .put("statusCode", statusCode)
                      .toString()));
      return OK;
    }
  }

  static final class Webhook implements Provider {
    @Override
    public String name() {
      return "webhook";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String method = c.str("httpMethod") == null ? "post" : c.str("httpMethod").toLowerCase();
      Map<String, String> requestHeaders = new LinkedHashMap<>();
      Sender.Request request;

      if ("get".equals(method)) {
        Map<String, String> params = fields("msg", msg);
        if (h != null) {
          params.put("heartbeat", Json.write(h));
        }
        if (m != null) {
          params.put("monitor", Json.write(m));
        }
        applyAdditionalHeaders(c, requestHeaders);
        request =
            Sender.Request.get(
                c.str("webhookURL")
                    + (c.str("webhookURL").contains("?") ? "&" : "?")
                    + form(params),
                requestHeaders);
      } else if ("form-data".equals(c.str("webhookContentType"))) {
        Map<String, String> formFields =
            fields("data", Json.obj().put("heartbeat", h).put("monitor", m).put("msg", msg).toString());
        requestHeaders.put("Content-Type", "multipart/form-data");
        applyAdditionalHeaders(c, requestHeaders);
        request = Sender.Request.form(c.str("webhookURL"), requestHeaders, formFields);
      } else if ("custom".equals(c.str("webhookContentType"))) {
        String body = Notify.renderTemplate(c.str("webhookCustomBody"), msg, m, h);
        applyAdditionalHeaders(c, requestHeaders);
        request = Sender.Request.post(c.str("webhookURL"), requestHeaders, body);
      } else {
        requestHeaders.put("Content-Type", "application/json");
        applyAdditionalHeaders(c, requestHeaders);
        request =
            Sender.Request.post(
                c.str("webhookURL"),
                requestHeaders,
                Json.obj().put("heartbeat", h).put("monitor", m).put("msg", msg).toString());
      }
      ctx.sender().send(request);
      return OK;
    }

    private static void applyAdditionalHeaders(Config c, Map<String, String> target)
        throws Exception {
      if (!c.truthy("webhookAdditionalHeaders")) {
        return;
      }
      try {
        Map<String, Object> extra =
            Json.MAPPER.readValue(c.str("webhookAdditionalHeaders"), Map.class);
        extra.forEach((key, value) -> target.put(key, String.valueOf(value)));
      } catch (Exception e) {
        throw new Exception("Additional Headers is not a valid JSON");
      }
    }
  }

  static final class Bitrix24 implements Provider {
    @Override
    public String name() {
      return "Bitrix24";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Map<String, String> params =
          fields(
              "user_id", c.str("bitrix24UserID"),
              "message", "[B]Uptime Kuma[/B]",
              // Up is painted the warmer colour and everything else the cooler one, which is the
              // source's own pairing.
              "ATTACH[COLOR]", Notify.isUp(h) ? "#b73419" : "#67b518",
              "ATTACH[BLOCKS][0][MESSAGE]", msg);
      ctx.sender()
          .send(
              Sender.Request.get(
                  c.str("bitrix24WebhookURL") + "/im.notify.system.add.json?" + form(params),
                  headers()));
      return OK;
    }
  }

  static final class Yzj implements Provider {
    @Override
    public String name() {
      return "YZJ";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String content = msg;
      if (h != null) {
        String badge = Notify.isDown(h) ? "❌" : Notify.isUp(h) ? "✅" : String.valueOf(Notify.status(h));
        content =
            badge
                + " "
                + string(m, "name")
                + " \n> "
                + string(h, "msg")
                + "\n> Time ("
                + string(h, "timezone")
                + "): "
                + string(h, "localDateTime");
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("yzjWebHookUrl") + "?yzjtype=0&yzjtoken=" + c.str("yzjToken"),
                      headers("Content-Type", "application/json"),
                      Json.obj().put("content", content).toString()));
      Map<String, Object> body = response.json();
      if (!Boolean.TRUE.equals(body.get("success"))) {
        Object error = body.get("errmsg");
        throw new Exception(
            error != null
                ? String.valueOf(error)
                : "yzj's server did not respond with the expected result");
      }
      return OK;
    }
  }
}
