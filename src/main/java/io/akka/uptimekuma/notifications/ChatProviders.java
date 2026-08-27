package io.akka.uptimekuma.notifications;

import static io.akka.uptimekuma.notifications.Notify.OK;
import static io.akka.uptimekuma.notifications.Notify.fields;
import static io.akka.uptimekuma.notifications.Notify.form;
import static io.akka.uptimekuma.notifications.Notify.headers;
import static io.akka.uptimekuma.notifications.Notify.string;
import static io.akka.uptimekuma.notifications.Notify.urlEncode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The targets that post a message into a conversation.
 *
 * <p>Nearly all of them accept a webhook URL and a body shaped like a card, and the differences
 * between them are which keys that card uses and which colour each status is painted.
 */
final class ChatProviders {

  private ChatProviders() {}

  static List<Provider> all() {
    return List.of(
        new Slack(),
        new Discord(),
        new Teams(),
        new Telegram(),
        new Matrix(),
        new Mattermost(),
        new RocketChat(),
        new GoogleChat(),
        new Feishu(),
        new DingDing(),
        new Kook(),
        new WeCom(),
        new ZohoCliq(),
        new Pumble(),
        new Stackfield(),
        new VkTeams(),
        new Vk(),
        new Line(),
        new Bale(),
        new Max(),
        new Milky(),
        new OneBot(),
        new OneChat(),
        new NextcloudTalk(),
        new Fluxer(),
        new Nostr(),
        new Whatsapp360messenger(),
        new Evolution(),
        new OpenWa(),
        new Waha(),
        new Whapi(),
        new Onesender());
  }

  // ---- the two webhook providers that draw an embed --------------------------------------------

  /**
   * Discord and Fluxer share a body shape: an avatar probe, then either a plain line or an embed
   * whose fields depend on the status. They are written once and parameterised by the four things
   * that differ.
   */
  private abstract static class EmbedWebhook implements Provider {

    private static final String ICON =
        "https://github.com/louislam/uptime-kuma/raw/master/public/icon.png";

    abstract String prefix();

    /** Whether this target understands threads and the notification-suppression flag. */
    abstract boolean supportsThreads();

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String url = c.str(prefix() + "WebhookUrl");
      if (url == null) {
        url = c.str("webhookUrl");
      }
      String displayName = c.str(prefix() + "Username", "Uptime Kuma");

      // Whether the webhook already has a picture decides whether one is supplied. A probe that
      // fails is treated as "it has one", so a webhook that cannot be read is not given an avatar
      // it might not want.
      boolean hasAvatar = true;
      try {
        Sender.Response info = ctx.sender().send(Sender.Request.get(url, headers()));
        hasAvatar = info.json().get("avatar") != null;
      } catch (Exception e) {
        hasAvatar = true;
      }

      String format = c.str(prefix() + "MessageFormat");
      if (format == null) {
        format = c.truthy(prefix() + "UseMessageTemplate") ? "custom" : "normal";
      }
      String template = c.str(prefix() + "MessageTemplate");

      String postUrl = url;
      if (supportsThreads() && "postToThread".equals(c.str("discordChannelType"))) {
        postUrl = url + (url.contains("?") ? "&" : "?") + "thread_id=" + urlEncode(c.str("threadId"));
      }

      Json.Obj body = Json.obj().put("username", displayName);

      if (h == null) {
        String content = msg;
        if ("minimalist".equals(format)) {
          content = "Test: " + msg;
        } else if ("custom".equals(format) && template != null && !template.trim().isEmpty()) {
          content = Notify.renderTemplate(template, msg, m, h);
        }
        body.put("content", content);
        decorate(body, c, hasAvatar);
        ctx.sender().send(Sender.Request.post(postUrl, headers("Content-Type", "application/json"), body.toString()));
        return OK;
      }

      if ("minimalist".equals(format)) {
        body.put(
            "content",
            Notify.isDown(h)
                ? "🔴 " + string(m, "name") + " is down."
                : "🟢 " + string(m, "name") + " is up.");
        decorate(body, c, hasAvatar);
        ctx.sender().send(Sender.Request.post(postUrl, headers("Content-Type", "application/json"), body.toString()));
        return OK;
      }

      if ("custom".equals(format) && template != null && !template.trim().isEmpty()) {
        body.put("content", Notify.renderTemplate(template.trim(), msg, m, h));
        decorate(body, c, hasAvatar);
        ctx.sender().send(Sender.Request.post(postUrl, headers("Content-Type", "application/json"), body.toString()));
        return OK;
      }

      String address = Notify.extractAddress(m);
      long beatSeconds = epochSeconds(string(h, "time"));
      List<Object> fieldList = new ArrayList<>();
      fieldList.add(Json.obj().put("name", "Service Name").put("value", string(m, "name")).map());
      if (!c.truthy("disableUrl") && address != null && !address.isEmpty()) {
        fieldList.add(
            Json.obj()
                .put("name", "push".equals(string(m, "type")) ? "Service Type" : "Service URL")
                .put("value", address)
                .map());
      }

      Json.Obj embed = Json.obj();
      if (Notify.isDown(h)) {
        embed.put("title", "❌ Your service " + string(m, "name") + " went down. ❌");
        embed.put("color", 16711680);
        if (supportsThreads()) {
          embed.put("timestamp", string(h, "time"));
        }
        fieldList.add(
            Json.obj().put("name", "Went Offline").put("value", "<t:" + beatSeconds + ":F>").map());
        fieldList.add(
            Json.obj()
                .put("name", "Time (" + string(h, "timezone") + ")")
                .put("value", string(h, "localDateTime"))
                .map());
        fieldList.add(
            Json.obj()
                .put("name", "Error")
                .put("value", string(h, "msg") == null ? "N/A" : string(h, "msg"))
                .map());
      } else if (Notify.isUp(h)) {
        embed.put("title", "✅ Your service " + string(m, "name") + " is up! ✅");
        embed.put("color", 65280);
        if (supportsThreads()) {
          embed.put("timestamp", string(h, "time"));
        }
        String lastDown = string(h, "lastDownTime");
        if (lastDown != null) {
          long offlineSeconds = epochSeconds(lastDown);
          fieldList.add(
              Json.obj()
                  .put("name", "Went Offline")
                  .put("value", "<t:" + offlineSeconds + ":F>")
                  .map());
          String duration = formatDuration(beatSeconds - offlineSeconds);
          if (!duration.isEmpty()) {
            fieldList.add(
                Json.obj().put("name", "Downtime Duration").put("value", duration).map());
          }
        }
        fieldList.add(
            Json.obj()
                .put("name", "Time (" + string(h, "timezone") + ")")
                .put("value", string(h, "localDateTime"))
                .map());
        if (Notify.get(h, "ping") != null) {
          fieldList.add(
              Json.obj().put("name", "Ping").put("value", Notify.get(h, "ping") + " ms").map());
        }
      } else {
        // Neither up nor down: the source composes nothing at all for this beat.
        return null;
      }
      embed.put("fields", fieldList);
      body.put("embeds", Json.array(embed.map()));
      decorate(body, c, hasAvatar);
      body.putIfPresent("content", c.str(prefix() + "PrefixMessage"));
      ctx.sender().send(Sender.Request.post(postUrl, headers("Content-Type", "application/json"), body.toString()));
      return OK;
    }

    private void decorate(Json.Obj body, Config c, boolean hasAvatar) {
      if (!hasAvatar) {
        body.put("avatar_url", ICON);
      }
      if (supportsThreads()) {
        if ("createNewForumPost".equals(c.str("discordChannelType"))) {
          body.put("thread_name", c.str("postName"));
        }
        if (c.truthy("discordSuppressNotifications")) {
          // Bit twelve is the platform's "do not notify anybody" flag.
          body.put("flags", 1 << 12);
        }
      }
    }

    private static long epochSeconds(String isoOrSqlTime) {
      if (isoOrSqlTime == null) {
        return 0;
      }
      try {
        return java.time.Instant.parse(isoOrSqlTime.replace(" ", "T") + "Z").getEpochSecond();
      } catch (Exception e) {
        try {
          return java.time.Instant.parse(isoOrSqlTime).getEpochSecond();
        } catch (Exception ignored) {
          return 0;
        }
      }
    }

    /** Seconds are only shown when the outage was under an hour. */
    static String formatDuration(long totalSeconds) {
      long hours = totalSeconds / 3600;
      long minutes = (totalSeconds % 3600) / 60;
      long seconds = totalSeconds % 60;
      List<String> parts = new ArrayList<>();
      if (hours > 0) {
        parts.add(hours + "h");
      }
      if (minutes > 0) {
        parts.add(minutes + "m");
      }
      if (seconds > 0 && hours == 0) {
        parts.add(seconds + "s");
      }
      return parts.isEmpty() ? "0s" : String.join(" ", parts);
    }
  }

  static final class Discord extends EmbedWebhook {
    @Override
    public String name() {
      return "discord";
    }

    @Override
    String prefix() {
      return "discord";
    }

    @Override
    boolean supportsThreads() {
      return true;
    }
  }

  static final class Fluxer extends EmbedWebhook {
    @Override
    public String name() {
      return "fluxer";
    }

    @Override
    String prefix() {
      return "fluxer";
    }

    @Override
    boolean supportsThreads() {
      return false;
    }
  }

  // ---- the rest -------------------------------------------------------------------------------

  static final class Slack implements Provider {
    @Override
    public String name() {
      return "slack";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text = c.truthy("slackchannelnotify") ? msg + " <!channel>" : msg;

      Json.Obj body =
          Json.obj()
              .put("text", text)
              .put("channel", c.str("slackchannel"))
              .put("username", c.str("slackusername"))
              .put("icon_emoji", c.str("slackiconemo"));

      if (h == null) {
        ctx.sender()
            .send(
                Sender.Request.post(
                    c.str("slackwebhookURL"),
                    headers("Content-Type", "application/json"),
                    body.toString()));
        return OK;
      }
      if (c.truthy("slackUseTemplate")) {
        body.put("text", Notify.renderTemplate(c.str("slackTemplate"), text, m, h));
        ctx.sender()
            .send(
                Sender.Request.post(
                    c.str("slackwebhookURL"),
                    headers("Content-Type", "application/json"),
                    body.toString()));
        return OK;
      }

      boolean includeGroupName =
          c.raw("slackIncludeGroupName") == null || c.truthy("slackIncludeGroupName");
      String groupPath = groupPath(m);
      String title = string(m, "name") == null ? "Uptime Kuma Alert" : string(m, "name");

      if (c.truthy("slackrichmessage")) {
        List<Object> blocks = new ArrayList<>();
        blocks.add(
            Json.obj()
                .put("type", "header")
                .put("text", Json.obj().put("type", "plain_text").put("text", title).map())
                .map());
        if (includeGroupName && groupPath != null) {
          blocks.add(
              Json.obj()
                  .put("type", "context")
                  .put(
                      "elements",
                      Json.array(
                          Json.obj().put("type", "mrkdwn").put("text", "_" + groupPath + "_").map()))
                  .map());
        }
        blocks.add(
            Json.obj()
                .put("type", "section")
                .put(
                    "fields",
                    Json.array(
                        Json.obj().put("type", "mrkdwn").put("text", "*Message*\n" + text).map(),
                        Json.obj()
                            .put("type", "mrkdwn")
                            .put(
                                "text",
                                "*Time ("
                                    + string(h, "timezone")
                                    + ")*\n"
                                    + string(h, "localDateTime"))
                            .map()))
                .map());
        List<Object> actions = new ArrayList<>();
        if (ctx.primaryBaseURL() != null) {
          actions.add(
              Json.obj()
                  .put("type", "button")
                  .put(
                      "text",
                      Json.obj().put("type", "plain_text").put("text", "Visit Uptime Kuma").map())
                  .put("value", "Uptime-Kuma")
                  .put(
                      "url",
                      ctx.primaryBaseURL() + Context.monitorRelativeUrl(Notify.get(m, "id")))
                  .map());
        }
        String address = Notify.extractAddress(m);
        if (address != null && (address.startsWith("http://") || address.startsWith("https://"))) {
          actions.add(
              Json.obj()
                  .put("type", "button")
                  .put(
                      "text", Json.obj().put("type", "plain_text").put("text", "Visit site").map())
                  .put("value", "Site")
                  .put("url", address)
                  .map());
        }
        if (!actions.isEmpty()) {
          blocks.add(Json.obj().put("type", "actions").put("elements", actions).map());
        }
        body.put(
            "attachments",
            Json.array(
                Json.obj()
                    .put("color", Notify.isUp(h) ? "#2eb886" : "#e01e5a")
                    .put("blocks", blocks)
                    .map()));
      } else {
        body.put("attachments", new ArrayList<>());
        body.put(
            "text",
            includeGroupName && groupPath != null
                ? "_" + groupPath + "_\n" + title + "\n" + text
                : title + "\n" + text);
      }

      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("slackwebhookURL"),
                  headers("Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }

    /** The names of every group above this monitor, joined the way the interface shows them. */
    static String groupPath(Map<String, Object> monitor) {
      Object path = Notify.get(monitor, "path");
      if (path instanceof List<?> segments && segments.size() > 1) {
        List<String> parents = new ArrayList<>();
        for (int i = 0; i < segments.size() - 1; i++) {
          parents.add(String.valueOf(segments.get(i)));
        }
        return String.join(" / ", parents);
      }
      return null;
    }
  }

  static final class Teams implements Provider {
    @Override
    public String name() {
      return "teams";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String monitorName = h == null ? null : string(m, "name");
      String monitorUrl = h == null ? null : Notify.extractAddress(m);
      String description = h == null ? msg : string(h, "msg");
      Integer status = Notify.status(h);
      String dashboardUrl =
          h != null && ctx.primaryBaseURL() != null
              ? ctx.primaryBaseURL() + Context.monitorRelativeUrl(Notify.get(m, "id"))
              : null;

      List<Object> facts = new ArrayList<>();
      if (description != null && !description.isEmpty()) {
        facts.add(Json.obj().put("title", "Description").put("value", description).map());
      }
      if (monitorName != null) {
        facts.add(Json.obj().put("title", "Monitor").put("value", monitorName).map());
      }
      boolean hasUrl = monitorUrl != null && !monitorUrl.isEmpty() && !"https://".equals(monitorUrl);
      if (hasUrl) {
        facts.add(
            Json.obj()
                .put("title", "URL")
                .put("value", "[" + monitorUrl + "](" + monitorUrl + ")")
                .map());
      }
      if (h != null && string(h, "localDateTime") != null) {
        String timezone = string(h, "timezone");
        facts.add(
            Json.obj()
                .put("title", "Time")
                .put(
                    "value",
                    string(h, "localDateTime") + (timezone != null ? " (" + timezone + ")" : ""))
                .map());
      }

      List<Object> body = new ArrayList<>();
      body.add(
          Json.obj()
              .put("type", "Container")
              .put("verticalContentAlignment", "Center")
              .put(
                  "items",
                  Json.array(
                      Json.obj()
                          .put("type", "ColumnSet")
                          .put("style", style(status))
                          .put(
                              "columns",
                              Json.array(
                                  Json.obj()
                                      .put("type", "Column")
                                      .put("width", "auto")
                                      .put("verticalContentAlignment", "Center")
                                      .put(
                                          "items",
                                          Json.array(
                                              Json.obj()
                                                  .put("type", "Image")
                                                  .put("width", "32px")
                                                  .put("style", "Person")
                                                  .put(
                                                      "url",
                                                      "https://raw.githubusercontent.com/louislam/uptime-kuma/master/public/icon.png")
                                                  .put("altText", "Uptime Kuma Logo")
                                                  .map()))
                                      .map(),
                                  Json.obj()
                                      .put("type", "Column")
                                      .put("width", "stretch")
                                      .put(
                                          "items",
                                          Json.array(
                                              Json.obj()
                                                  .put("type", "TextBlock")
                                                  .put("size", "Medium")
                                                  .put("weight", "Bolder")
                                                  .put(
                                                      "text",
                                                      "**"
                                                          + statusMessage(status, monitorName, false)
                                                          + "**")
                                                  .map(),
                                              Json.obj()
                                                  .put("type", "TextBlock")
                                                  .put("size", "Small")
                                                  .put("weight", "Default")
                                                  .put("text", "Uptime Kuma Alert")
                                                  .put("isSubtle", true)
                                                  .put("spacing", "None")
                                                  .map()))
                                      .map()))
                          .map()))
              .map());
      body.add(Json.obj().put("type", "FactSet").put("separator", false).put("facts", facts).map());

      boolean enableTags = c.truthy("teamsEnableTags");
      Object tags = Notify.get(m, "tags");
      if (enableTags && tags instanceof List<?> tagList && !tagList.isEmpty()) {
        List<Object> badges = new ArrayList<>();
        for (Object tag : tagList) {
          badges.add(
              Json.obj()
                  .put("type", "Badge")
                  .put("text", tagText(tag))
                  .put("size", "Medium")
                  .put("style", "Accent")
                  .map());
        }
        body.add(
            Json.obj()
                .put("type", "Container")
                .put(
                    "layouts",
                    Json.array(
                        Json.obj()
                            .put("type", "Layout.Flow")
                            .put("columnSpacing", "Small")
                            .put("rowSpacing", "Small")
                            .put("horizontalItemsAlignment", "Left")
                            .map()))
                .put("items", badges)
                .map());
      }

      List<Object> actions = new ArrayList<>();
      if (dashboardUrl != null) {
        actions.add(
            Json.obj()
                .put("type", "Action.OpenUrl")
                .put("title", "Visit Uptime Kuma")
                .put("url", dashboardUrl)
                .map());
      }
      if (hasUrl) {
        actions.add(
            Json.obj()
                .put("type", "Action.OpenUrl")
                .put("title", "Visit Monitor URL")
                .put("url", monitorUrl)
                .map());
      }
      body.add(Json.obj().put("type", "ActionSet").put("actions", actions).map());

      String payload =
          Json.obj()
              .put("type", "message")
              .put("summary", statusMessage(status, monitorName, true))
              .put(
                  "attachments",
                  Json.array(
                      Json.obj()
                          .put("contentType", "application/vnd.microsoft.card.adaptive")
                          .put("contentUrl", "")
                          .put(
                              "content",
                              Json.obj()
                                  .put("type", "AdaptiveCard")
                                  .put("body", body)
                                  .put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
                                  .put("version", "1.5")
                                  .map())
                          .map()))
              .toString();

      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("webhookUrl"), headers("Content-Type", "application/json"), payload));
      return OK;
    }

    private static String statusMessage(Integer status, String name, boolean withSymbol) {
      if (status == null) {
        return "Notification";
      }
      if (status == Notify.DOWN) {
        return (withSymbol ? "🔴 " : "") + "[" + name + "] went down";
      }
      if (status == Notify.UP) {
        return (withSymbol ? "✅ " : "") + "[" + name + "] is back online";
      }
      return "Notification";
    }

    private static String style(Integer status) {
      if (status == null) {
        return "emphasis";
      }
      return status == Notify.DOWN ? "attention" : status == Notify.UP ? "good" : "emphasis";
    }

    private static String tagText(Object tag) {
      if (tag instanceof Map<?, ?> entry) {
        Object value = entry.get("value");
        if (value == null || "".equals(value)) {
          return String.valueOf(entry.get("name"));
        }
        return entry.get("name") + ": " + value;
      }
      return String.valueOf(tag);
    }
  }

  static final class Telegram implements Provider {
    @Override
    public String name() {
      return "telegram";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base = c.str("telegramServerUrl", "https://api.telegram.org");
      Json.Obj params =
          Json.obj()
              .put("chat_id", c.str("telegramChatID"))
              .put("text", msg)
              .put("disable_notification", c.truthy("telegramSendSilently"))
              .put("protect_content", c.truthy("telegramProtectContent"))
              .put("link_preview_options", Json.obj().put("is_disabled", true).map());
      if (c.truthy("telegramMessageThreadID")) {
        params.put("message_thread_id", c.str("telegramMessageThreadID"));
      }
      if (c.truthy("telegramUseTemplate")) {
        String parseMode = c.str("telegramTemplateParseMode");
        String text = msg;
        Map<String, Object> monitorCopy = m;
        Map<String, Object> heartbeatCopy = h;
        if ("MarkdownV2".equals(parseMode)) {
          text = escapeMarkdownV2(msg);
          monitorCopy = escapeRecursive(m);
          heartbeatCopy = escapeRecursive(h);
          if (monitorCopy == null) {
            monitorCopy = new LinkedHashMap<>();
            monitorCopy.put("name", escapeMarkdownV2("Monitor Name not available"));
            monitorCopy.put("hostname", escapeMarkdownV2("testing.hostname"));
            monitorCopy.put("url", escapeMarkdownV2("testing.hostname"));
          }
        }
        params.put(
            "text", Notify.renderTemplate(c.str("telegramTemplate"), text, monitorCopy, heartbeatCopy));
        if (parseMode != null && !"plain".equals(parseMode)) {
          params.put("parse_mode", parseMode);
        }
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  base + "/bot" + c.str("telegramBotToken") + "/sendMessage",
                  headers("Content-Type", "application/json"),
                  params.toString()));
      return OK;
    }

    /** Every character the format treats as punctuation has to be backslashed. */
    static String escapeMarkdownV2(String text) {
      if (text == null || text.isEmpty()) {
        return text;
      }
      return text.replaceAll("([_*\\[\\]()~>#+\\-=|{}.!\\\\])", "\\\\$1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> escapeRecursive(Map<String, Object> source) {
      if (source == null) {
        return null;
      }
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : source.entrySet()) {
        Object value = entry.getValue();
        if (value instanceof String s) {
          out.put(entry.getKey(), escapeMarkdownV2(s));
        } else if (value instanceof Map<?, ?> nested) {
          out.put(entry.getKey(), escapeRecursive((Map<String, Object>) nested));
        } else {
          out.put(entry.getKey(), value);
        }
      }
      return out;
    }
  }

  static final class Matrix implements Provider {
    @Override
    public String name() {
      return "matrix";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      byte[] random = new byte[20];
      new SecureRandom().nextBytes(random);
      // The transaction identifier makes a retried send land once. Twenty base-sixty-four
      // characters is what the source takes.
      String transaction =
          urlEncode(Base64.getEncoder().encodeToString(random).substring(0, 20));
      String body =
          Json.obj().put("msgtype", "m.text")
              .put(
                  "body",
                  c.truthy("matrixUseTemplate")
                      ? Notify.renderTemplate(c.str("matrixTemplate"), msg, m, h)
                      : msg)
              .toString();
      ctx.sender()
          .send(
              new Sender.Request(
                  "PUT",
                  c.str("homeserverUrl")
                      + "/_matrix/client/r0/rooms/"
                      + urlEncode(c.str("internalRoomId"))
                      + "/send/m.room.message/"
                      + transaction,
                  headers("Authorization", "Bearer " + c.str("accessToken")),
                  body,
                  null));
      return OK;
    }
  }

  static final class Mattermost implements Provider {
    @Override
    public String name() {
      return "mattermost";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String username = c.str("mattermostusername", "Uptime Kuma");
      if (h == null) {
        ctx.sender()
            .send(
                Sender.Request.post(
                    c.str("mattermostWebhookUrl"),
                    headers("Content-Type", "application/json"),
                    Json.obj().put("username", username).put("text", msg).toString()));
        return OK;
      }
      String iconEmoji = c.str("mattermosticonemo");
      String onlineEmoji = iconEmoji;
      String offlineEmoji = iconEmoji;
      if (iconEmoji != null) {
        String[] parts = iconEmoji.split(" ");
        if (parts.length >= 2) {
          onlineEmoji = parts[0];
          offlineEmoji = parts[1];
        }
      }
      String statusText = "unknown";
      String color = "#000000";
      Map<String, Object> statusField =
          Json.obj().put("short", false).put("title", "Error").put("value", string(h, "msg")).map();
      String chosenEmoji = iconEmoji;
      if (Notify.isDown(h)) {
        chosenEmoji = offlineEmoji == null ? iconEmoji : offlineEmoji;
        statusText = "down.";
        color = "#FF0000";
      } else if (Notify.isUp(h)) {
        chosenEmoji = onlineEmoji == null ? iconEmoji : onlineEmoji;
        statusField =
            Json.obj()
                .put("short", false)
                .put("title", "Ping")
                .put("value", Notify.get(h, "ping") + "ms")
                .map();
        statusText = "up!";
        color = "#32CD32";
      }
      String channel = c.str("mattermostchannel");
      String body =
          Json.obj()
              .put("username", string(m, "name") + " " + username)
              .put("channel", channel == null ? null : channel.toLowerCase())
              .put("icon_emoji", chosenEmoji)
              .put("icon_url", c.str("mattermosticonurl"))
              .put(
                  "attachments",
                  Json.array(
                      Json.obj()
                          .put("fallback", "Your " + string(m, "pathName") + " service went " + statusText)
                          .put("color", color)
                          .put("title", string(m, "pathName") + " service went " + statusText)
                          .put("title_link", string(m, "url"))
                          .put(
                              "fields",
                              Json.array(
                                  statusField,
                                  Json.obj()
                                      .put("short", true)
                                      .put("title", "Time (" + string(h, "timezone") + ")")
                                      .put("value", string(h, "localDateTime"))
                                      .map()))
                          .map()))
              .toString();
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("mattermostWebhookUrl"), headers("Content-Type", "application/json"), body));
      return OK;
    }
  }

  static final class RocketChat implements Provider {
    @Override
    public String name() {
      return "rocket.chat";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body =
          Json.obj()
              .put("text", h == null ? msg : "Uptime Kuma Alert")
              .put("channel", c.str("rocketchannel"))
              .put("username", c.str("rocketusername"))
              .put("icon_emoji", c.str("rocketiconemo"));
      if (h != null) {
        Json.Obj attachment =
            Json.obj()
                .put(
                    "title",
                    "Uptime Kuma Alert *Time ("
                        + string(h, "timezone")
                        + ")*\n"
                        + string(h, "localDateTime"))
                .put("text", "*Message*\n" + msg)
                .put("color", Notify.isDown(h) ? "#ff0000" : "#32cd32");
        if (ctx.primaryBaseURL() != null) {
          attachment.put(
              "title_link", ctx.primaryBaseURL() + Context.monitorRelativeUrl(Notify.get(m, "id")));
        }
        body.put("attachments", Json.array(attachment.map()));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("rocketwebhookURL"),
                  headers("Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }
  }

  static final class GoogleChat implements Provider {
    @Override
    public String name() {
      return "GoogleChat";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String body;
      if (c.truthy("googleChatUseTemplate") && c.truthy("googleChatTemplate")) {
        body =
            Json.obj()
                .put("text", Notify.renderTemplate(c.str("googleChatTemplate"), msg, m, h))
                .toString();
      } else {
        String title = "Uptime Kuma Alert";
        if (m != null && h != null) {
          title =
              Notify.isUp(h)
                  ? "✅ " + string(m, "name") + " is back online"
                  : "🔴 " + string(m, "name") + " went down";
        }
        List<Object> widgets = new ArrayList<>();
        widgets.add(
            Json.obj()
                .put("textParagraph", Json.obj().put("text", "<b>Message:</b>\n" + msg).map())
                .map());
        if (h != null) {
          widgets.add(
              Json.obj()
                  .put(
                      "textParagraph",
                      Json.obj()
                          .put(
                              "text",
                              "<b>Time ("
                                  + string(h, "timezone")
                                  + "):</b>\n"
                                  + string(h, "localDateTime"))
                          .map())
                  .map());
        }
        String address = Notify.extractAddress(m);
        if (address != null && !address.isEmpty()) {
          widgets.add(
              Json.obj()
                  .put("textParagraph", Json.obj().put("text", "<b>Address:</b>\n" + address).map())
                  .map());
        }
        if (ctx.primaryBaseURL() != null) {
          String path = m != null ? Context.monitorRelativeUrl(Notify.get(m, "id")) : "/";
          widgets.add(
              Json.obj()
                  .put(
                      "buttonList",
                      Json.obj()
                          .put(
                              "buttons",
                              Json.array(
                                  Json.obj()
                                      .put("text", "Visit Uptime Kuma")
                                      .put(
                                          "onClick",
                                          Json.obj()
                                              .put(
                                                  "openLink",
                                                  Json.obj()
                                                      .put("url", ctx.primaryBaseURL() + path)
                                                      .map())
                                              .map())
                                      .map()))
                          .map())
                  .map());
        }
        Map<String, Object> header = Json.obj().put("title", title).map();
        body =
            Json.obj()
                .put("fallbackText", title)
                .put(
                    "cardsV2",
                    Json.array(
                        Json.obj()
                            .put(
                                "card",
                                Json.obj()
                                    .put("header", header)
                                    .put(
                                        "sections",
                                        Json.array(Json.obj().put("widgets", widgets).map()))
                                    .map())
                            .map()))
                .toString();
      }
      // The platform answers a burst with a refusal rather than a queue, so a refused call is
      // retried after a wait rather than reported. Nothing else is retried.
      int retries = Math.min(c.intOf("googleChatMaxRetries", 1), 10);
      while (true) {
        Sender.Response response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        c.str("googleChatWebhookURL"),
                        headers("Content-Type", "application/json"),
                        body));
        if (response.status() != 429) {
          return OK;
        }
        retries--;
        if (retries <= 0) {
          throw new Exception(Notify.axiosError("Request failed", response));
        }
        Thread.sleep(60000 + (long) (Math.random() * 120000));
      }
    }
  }

  static final class Feishu implements Provider {
    @Override
    public String name() {
      return "Feishu";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String body;
      if (h == null) {
        body =
            Json.obj()
                .put("msg_type", "text")
                .put("content", Json.obj().put("text", msg).map())
                .toString();
      } else if (Notify.isDown(h) || Notify.isUp(h)) {
        boolean down = Notify.isDown(h);
        body =
            Json.obj()
                .put("msg_type", "interactive")
                .put(
                    "card",
                    Json.obj()
                        .put(
                            "config",
                            Json.obj()
                                .put("update_multi", false)
                                .put("wide_screen_mode", true)
                                .map())
                        .put(
                            "header",
                            Json.obj()
                                .put(
                                    "title",
                                    Json.obj()
                                        .put("tag", "plain_text")
                                        .put(
                                            "content",
                                            "UptimeKuma Alert: ["
                                                + (down ? "Down" : "UP")
                                                + "] "
                                                + string(m, "name"))
                                        .map())
                                .put("template", down ? "red" : "green")
                                .map())
                        .put(
                            "elements",
                            Json.array(
                                Json.obj()
                                    .put("tag", "div")
                                    .put(
                                        "text",
                                        Json.obj()
                                            .put("tag", "lark_md")
                                            .put("content", content(h))
                                            .map())
                                    .map()))
                        .map())
                .toString();
      } else {
        return null;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("feishuWebHookUrl"), headers("Content-Type", "application/json"), body));
      return OK;
    }

    private static String content(Map<String, Object> h) {
      Object ping = Notify.get(h, "ping");
      return "**Message**: "
          + string(h, "msg")
          + "\n**Ping**: "
          + (ping == null ? "N/A" : ping + " ms")
          + "\n**Time ("
          + string(h, "timezone")
          + ")**: "
          + string(h, "localDateTime");
    }
  }

  static final class DingDing implements Provider {
    @Override
    public String name() {
      return "DingDing";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      boolean mentionAll = "everyone".equals(c.str("mentioning"));
      Object mobileList = "specify-mobiles".equals(c.str("mentioning")) ? c.raw("mobileList") : List.of();
      Object userList = "specify-users".equals(c.str("mentioning")) ? c.raw("userList") : List.of();
      int mentionCount = size(mobileList) + size(userList);
      // The source builds this with an unparenthesised conditional, so a non-empty list produces
      // a bare newline and no names at all. Reproduced: the message a person receives is what
      // the source sends, not what it appears to intend.
      String mentionStr = mentionCount > 0 ? "\n" : "";

      Json.Obj at =
          Json.obj()
              .put("isAtAll", mentionAll)
              .put("atUserIds", userList)
              .put("atMobiles", mobileList);
      Json.Obj params;
      if (h != null) {
        String statusWord = Notify.isDown(h) ? "DOWN" : Notify.isUp(h) ? "UP" : String.valueOf(Notify.status(h));
        params =
            Json.obj()
                .put("msgtype", "markdown")
                .put(
                    "markdown",
                    Json.obj()
                        .put("title", "[" + statusWord + "] " + string(m, "name"))
                        .put(
                            "text",
                            "## ["
                                + statusWord
                                + "] "
                                + string(m, "name")
                                + " \n> "
                                + string(h, "msg")
                                + "\n> Time ("
                                + string(h, "timezone")
                                + "): "
                                + string(h, "localDateTime")
                                + mentionStr)
                        .map())
                .put("at", at.map());
      } else {
        params =
            Json.obj()
                .put("msgtype", "text")
                .put("text", Json.obj().put("content", msg + mentionStr).map())
                .put("at", at.map());
      }
      long timestamp = System.currentTimeMillis();
      String secret = c.str("secretKey");
      String signature =
          Notify.hmacBase64(
              "HmacSHA256",
              (secret == null ? "" : secret).getBytes(java.nio.charset.StandardCharsets.UTF_8),
              timestamp + "\n" + secret);
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("webHookUrl") + "&timestamp=" + timestamp + "&sign=" + urlEncode(signature),
                      headers("Content-Type", "application/json"),
                      params.toString()));
      Object errmsg = response.json().get("errmsg");
      if (!"ok".equals(errmsg)) {
        throw new Exception(String.valueOf(errmsg));
      }
      return OK;
    }

    private static int size(Object list) {
      return list instanceof List<?> l ? l.size() : 0;
    }
  }

  static final class Kook implements Provider {
    @Override
    public String name() {
      return "Kook";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://www.kookapp.cn/api/v3/message/create",
                  headers(
                      "Authorization", "Bot " + c.str("kookBotToken"),
                      "Content-Type", "application/json"),
                  Json.obj()
                      .put("target_id", c.str("kookGuildID"))
                      .put("content", msg)
                      .toString()));
      return OK;
    }
  }

  static final class WeCom implements Provider {
    @Override
    public String name() {
      return "WeCom";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String title = "UptimeKuma Message";
      if (h != null) {
        if (Notify.isUp(h)) {
          title = "UptimeKuma Monitor Up";
        } else if (Notify.isDown(h)) {
          title = "UptimeKuma Monitor Down";
        }
      }
      Json.Obj text = Json.obj().put("content", title + "\n" + msg);
      List<String> mobiles = Notify.splitList(c.str("weComMentionedMobileList"), ",");
      if (!mobiles.isEmpty()) {
        text.put("mentioned_mobile_list", mobiles);
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + c.str("weComBotKey"),
                  headers("Content-Type", "application/json"),
                  Json.obj().put("msgtype", "text").put("text", text.map()).toString()));
      return OK;
    }
  }

  static final class ZohoCliq implements Provider {
    @Override
    public String name() {
      return "ZohoCliq";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      List<String> lines = new ArrayList<>();
      Integer status = Notify.status(h);
      String monitorName = h == null ? null : string(m, "name");
      if (status == null) {
        lines.add("Notification\n");
      } else if (status == Notify.DOWN) {
        lines.add("🔴 [" + monitorName + "] went down\n");
      } else if (status == Notify.UP) {
        lines.add("### ✅ [" + monitorName + "] is back online\n");
      } else {
        lines.add("Notification\n");
      }
      lines.add("*Description:* " + (h == null ? msg : string(h, "msg")));
      String address = h == null ? null : Notify.extractAddress(m);
      if (address != null && !address.isEmpty() && !"https://".equals(address)) {
        lines.add("*URL:* " + address);
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("webhookUrl"),
                  headers("Content-Type", "application/json"),
                  Json.obj().put("text", String.join("\n", lines)).toString()));
      return OK;
    }
  }

  static final class Pumble implements Provider {
    @Override
    public String name() {
      return "pumble";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj attachment;
      if (h == null && m == null) {
        attachment =
            Json.obj().put("title", "Uptime Kuma Alert").put("text", msg).put("color", "#5BDD8B");
      } else {
        attachment =
            Json.obj()
                .put("title", string(m, "name") + " is " + (Notify.isUp(h) ? "up" : "down"))
                .put("text", string(h, "msg"))
                .put("color", Notify.isUp(h) ? "#5BDD8B" : "#DC3645");
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("webhookURL"),
                  headers("Content-Type", "application/json"),
                  Json.obj().put("attachments", Json.array(attachment.map())).toString()));
      return OK;
    }
  }

  static final class Stackfield implements Provider {
    @Override
    public String name() {
      return "stackfield";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      StringBuilder text = new StringBuilder("+Uptime Kuma Alert+");
      if (m != null && string(m, "name") != null) {
        text.append("\n*").append(string(m, "name")).append("*");
      }
      text.append("\n").append(msg);
      if (ctx.primaryBaseURL() != null) {
        String path = m != null ? Context.monitorRelativeUrl(Notify.get(m, "id")) : "/";
        text.append("\n").append(ctx.primaryBaseURL()).append(path);
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("stackfieldwebhookURL"),
                  headers("Content-Type", "application/json"),
                  Json.obj().put("Title", text.toString()).toString()));
      return OK;
    }
  }

  static final class VkTeams implements Provider {
    @Override
    public String name() {
      return "VKTeams";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base = Notify.stripTrailingSlash(c.str("vkteamsBaseUrl", "https://myteam.mail.ru"));
      Map<String, String> params =
          fields("token", c.str("vkteamsBotToken"), "chatId", c.str("vkteamsChatId"), "text", msg);
      if (c.truthy("vkteamsUseTemplate") && c.truthy("vkteamsTemplate")) {
        params.put("text", Notify.renderTemplate(c.str("vkteamsTemplate"), msg, m, h));
        String format = c.str("vkteamsTemplateFormat");
        if (format != null && !"plain".equals(format)) {
          params.put("parseMode", format);
        }
      }
      Sender.Response response =
          ctx.sender()
              .send(Sender.Request.get(base + "/bot/v1/messages/sendText?" + form(params), headers()));
      if (Boolean.FALSE.equals(response.json().get("ok"))) {
        throw new Exception("VKTeams API returned error: " + response.json().get("description"));
      }
      return OK;
    }
  }

  static final class Vk implements Provider {
    @Override
    public String name() {
      return "VK";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://api.vk.ru/method/messages.send",
                      headers("Content-Type", "application/x-www-form-urlencoded"),
                      form(
                          fields(
                              "access_token", c.str("vkAccessToken"),
                              "v", c.str("vkApiVersion"),
                              "peer_id", c.str("vkPeerId"),
                              "message", msg,
                              "dont_parse_links", c.truthy("vkDontParseLinks") ? "1" : "0",
                              // The identifier makes a retried send land once.
                              "random_id",
                                  String.valueOf(
                                      (long) (Math.random() * Integer.MAX_VALUE))))));
      Map<String, Object> body = response.json();
      if (body.get("error") instanceof Map<?, ?> error) {
        throw new Exception(
            "VK API returned error " + error.get("error_code") + ": " + error.get("error_msg"));
      }
      if (!body.containsKey("response")) {
        throw new Exception("Invalid VK API response");
      }
      return OK;
    }
  }

  static final class Line implements Provider {
    @Override
    public String name() {
      return "line";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text;
      if (h == null) {
        text = "Test Successful!";
      } else if (Notify.isDown(h)) {
        text = alert("UptimeKuma Alert: [🔴 Down]\n", m, h);
      } else if (Notify.isUp(h)) {
        text = alert("UptimeKuma Alert: [✅ Up]\n", m, h);
      } else {
        return OK;
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.line.me/v2/bot/message/push",
                  headers(
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("lineChannelAccessToken")),
                  Json.obj()
                      .put("to", c.str("lineUserID"))
                      .put(
                          "messages",
                          Json.array(Json.obj().put("type", "text").put("text", text).map()))
                      .toString()));
      return OK;
    }

    private static String alert(String prefix, Map<String, Object> m, Map<String, Object> h) {
      return prefix
          + "Name: "
          + string(m, "name")
          + " \n"
          + string(h, "msg")
          + "\nTime ("
          + string(h, "timezone")
          + "): "
          + string(h, "localDateTime");
    }
  }

  static final class Bale implements Provider {
    @Override
    public String name() {
      return "bale";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://tapi.bale.ai/bot" + c.str("baleBotToken") + "/sendMessage",
                  headers("content-type", "application/json"),
                  Json.obj().put("chat_id", c.str("baleChatID")).put("text", msg).toString()));
      return OK;
    }
  }

  static final class Max implements Provider {
    @Override
    public String name() {
      return "max";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base = Notify.stripTrailingSlash(c.str("maxApiUrl", "https://platform-api.max.ru"));
      Json.Obj body = Json.obj().put("text", msg);
      if (c.truthy("maxUseTemplate") && c.truthy("maxTemplate")) {
        body.put("text", Notify.renderTemplate(c.str("maxTemplate"), msg, m, h));
        String format = c.str("maxTemplateFormat");
        if (format != null && !"plain".equals(format)) {
          body.put("format", format);
        }
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  base + "/messages?chat_id=" + urlEncode(c.str("maxChatID")),
                  headers(
                      "Authorization", c.str("maxBotToken"),
                      "Content-Type", "application/json"),
                  body.toString()));
      return OK;
    }
  }

  static final class Milky implements Provider {
    @Override
    public String name() {
      return "Milky";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String url = c.str("httpAddr");
      if (!url.startsWith("http")) {
        url = "http://" + url;
      }
      if (!url.endsWith("/")) {
        url = url + "/api/";
      }
      boolean group = "group".equals(c.str("msgType"));
      url = url + (group ? "send_group_message" : "send_private_message");
      Json.Obj body =
          Json.obj()
              .put(
                  "message",
                  Json.array(
                      Json.obj()
                          .put("type", "text")
                          .put("data", Json.obj().put("text", "UptimeKuma Alert: " + msg).map())
                          .map()));
      body.put(group ? "group_id" : "user_id", c.str("recieverId"));
      ctx.sender()
          .send(
              Sender.Request.post(
                  url,
                  headers(
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("accessToken")),
                  body.toString()));
      return OK;
    }
  }

  static final class OneBot implements Provider {
    @Override
    public String name() {
      return "OneBot";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String url = c.str("httpAddr");
      if (!url.startsWith("http")) {
        url = "http://" + url;
      }
      if (!url.endsWith("/")) {
        url = url + "/";
      }
      boolean group = "group".equals(c.str("msgType"));
      Json.Obj body =
          Json.obj().put("auto_escape", true).put("message", "UptimeKuma Alert: " + msg);
      body.put("message_type", group ? "group" : "private");
      body.put(group ? "group_id" : "user_id", c.str("recieverId"));
      ctx.sender()
          .send(
              Sender.Request.post(
                  url + "send_msg",
                  headers(
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("accessToken")),
                  body.toString()));
      return OK;
    }
  }

  static final class OneChat implements Provider {
    @Override
    public String name() {
      return "OneChat";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String message;
      if (h == null) {
        message = "Test Successful!";
      } else if (Notify.isDown(h)) {
        message = alert("[🔴 Down]", m, h);
      } else if (Notify.isUp(h)) {
        // A different tick from every other provider, which is the source's own choice.
        message = alert("[🟢 Up]", m, h);
      } else {
        return OK;
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://chat-api.one.th/message/api/v1/push_message",
                      headers(
                          "Content-Type", "application/json",
                          "Authorization", "Bearer " + c.str("accessToken")),
                      Json.obj()
                          .put("to", c.str("recieverId"))
                          .put("bot_id", c.str("botId"))
                          .put("type", "text")
                          .put("message", message)
                          .toString()));
      if (!response.ok()) {
        Object reason = response.json().get("message");
        throw new Exception(
            "OneChat API Error: "
                + (reason == null ? "Unknown API error occurred." : reason));
      }
      return OK;
    }

    private static String alert(String badge, Map<String, Object> m, Map<String, Object> h) {
      return "UptimeKuma Alert:\n"
          + badge
          + "\nName: "
          + string(m, "name")
          + "\n"
          + string(h, "msg")
          + "\nTime ("
          + string(h, "timezone")
          + "): "
          + string(h, "localDateTime");
    }
  }

  static final class NextcloudTalk implements Provider {
    @Override
    public String name() {
      return "nextcloudtalk";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      String nonce = java.util.HexFormat.of().formatHex(random);
      String signature =
          Notify.hmacHex(
              "HmacSHA256",
              c.str("botSecret").getBytes(java.nio.charset.StandardCharsets.UTF_8),
              nonce + msg);
      boolean silent =
          (Notify.isUp(h) && c.truthy("sendSilentUp"))
              || (Notify.isDown(h) && c.truthy("sendSilentDown"));
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("host")
                          + "/ocs/v2.php/apps/spreed/api/v1/bot/"
                          + c.str("conversationToken")
                          + "/message",
                      headers(
                          "X-Nextcloud-Talk-Bot-Random", nonce,
                          "X-Nextcloud-Talk-Bot-Signature", signature,
                          "OCS-APIRequest", "true"),
                      Json.obj().put("message", msg).put("silent", silent).toString()));
      if (response.status() != 201) {
        throw new Exception("Nextcloud Talk Error " + response.status());
      }
      return OK;
    }
  }

  static final class Nostr implements Provider {
    @Override
    public String name() {
      return "nostr";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      // The only target whose transport is neither HTTP nor a mail server: it opens a socket to
      // each relay and publishes a gift-wrapped event, and the protocol's key exchange is the
      // whole of what it does. Rebuilding that is out of this port's reach, so the target refuses
      // rather than silently reporting a delivery that did not happen. Declared in the README.
      throw new Exception(
          "The nostr target is not delivered by this port: its transport is the nostr relay "
              + "protocol, which this rebuild does not speak.");
    }
  }

  static final class Whatsapp360messenger implements Provider {
    @Override
    public String name() {
      return "Whatsapp360messenger";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String message = msg;
      if (c.truthy("Whatsapp360messengerUseTemplate")
          && c.truthy("Whatsapp360messengerTemplate")) {
        message = applyTemplate(c.str("Whatsapp360messengerTemplate"), msg, m);
      }
      Map<String, String> requestHeaders =
          headers(
              "Accept", "application/json",
              "Content-Type", "application/json",
              "Authorization", "Bearer " + c.str("Whatsapp360messengerAuthToken"));

      List<String> recipients = Notify.splitList(c.str("Whatsapp360messengerRecipient"), "[;,]");
      List<String> groupIds = groupIds(c);
      if (recipients.isEmpty() && groupIds.isEmpty()) {
        throw new Exception("No recipient or group specified");
      }
      for (String recipient : recipients) {
        ctx.sender()
            .send(
                Sender.Request.post(
                    "https://api.360messenger.com/v2/sendMessage",
                    requestHeaders,
                    Json.obj().put("phonenumber", recipient).put("text", message).toString()));
      }
      for (String groupId : groupIds) {
        ctx.sender()
            .send(
                Sender.Request.post(
                    "https://api.360messenger.com/v2/sendGroup",
                    requestHeaders,
                    Json.obj().put("groupId", groupId).put("text", message).toString()));
      }
      if (!recipients.isEmpty() && !groupIds.isEmpty()) {
        return OK
            + " (Sent to "
            + recipients.size()
            + " recipient(s) and "
            + groupIds.size()
            + " group(s))";
      }
      if (!groupIds.isEmpty()) {
        return OK + " (Sent to " + groupIds.size() + " group(s))";
      }
      return OK + " (Sent to " + recipients.size() + " recipient(s))";
    }

    private static List<String> groupIds(Config c) {
      Object raw = c.raw("Whatsapp360messengerGroupIds");
      if (raw == null) {
        raw = c.raw("Whatsapp360messengerGroupId");
      }
      List<String> out = new ArrayList<>();
      if (raw instanceof List<?> list) {
        for (Object entry : list) {
          if (entry instanceof Map<?, ?> map && map.get("id") != null) {
            out.add(String.valueOf(map.get("id")).trim());
          } else if (entry != null) {
            String value = String.valueOf(entry).trim();
            if (!value.isEmpty()) {
              out.add(value);
            }
          }
        }
        out.removeIf(String::isEmpty);
        return out;
      }
      return Notify.splitList(raw == null ? null : String.valueOf(raw), "[;,]");
    }

    /**
     * This one target does not use the interface's Liquid templates: it substitutes three fixed
     * placeholders itself, and falls back to the plain message if anything goes wrong.
     */
    private static String applyTemplate(
        String template, String msg, Map<String, Object> monitor) {
      try {
        String out = template;
        if (monitor != null) {
          out =
              out.replace(
                      "{{ monitorJSON['name'] }}",
                      string(monitor, "name") == null ? "" : string(monitor, "name"))
                  .replace(
                      "{{ monitorJSON['url'] }}",
                      string(monitor, "url") == null ? "" : string(monitor, "url"));
        }
        out = out.replace("{{ msg }}", msg);
        out =
            out.replaceAll(
                "(?s)\\{%\\s*if\\s+monitorJSON\\s*%\\}(.*?)\\{%\\s*endif\\s*%\\}",
                monitor != null ? "$1" : "");
        return out;
      } catch (Exception e) {
        return msg;
      }
    }
  }

  static final class Evolution implements Provider {
    @Override
    public String name() {
      return "evolution";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text =
          c.truthy("evolutionUseCustomMessage") && c.truthy("evolutionCustomMessage")
              ? Notify.renderTemplate(c.str("evolutionCustomMessage"), msg, m, h)
              : msg;
      String base =
          Notify.stripTrailingSlashes(c.str("evolutionApiUrl", "https://evolapicloud.com/"));
      ctx.sender()
          .send(
              Sender.Request.post(
                  base + "/message/sendText/" + urlEncode(c.str("evolutionInstanceName")),
                  headers(
                      "Accept", "application/json",
                      "Content-Type", "application/json",
                      "apikey", c.str("evolutionAuthToken")),
                  Json.obj()
                      .put("number", c.str("evolutionRecipient"))
                      .put("text", text)
                      .toString()));
      return OK;
    }
  }

  static final class OpenWa implements Provider {
    @Override
    public String name() {
      return "openwa";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text =
          c.truthy("openwaUseCustomMessage") && c.truthy("openwaCustomMessage")
              ? Notify.renderTemplate(c.str("openwaCustomMessage"), msg, m, h)
              : msg;
      List<String> chatIds = Notify.splitList(c.str("openwaChatId"), ",");
      if (chatIds.isEmpty()) {
        throw new Exception("No valid OpenWA chat ID found.");
      }
      String base = Notify.stripTrailingSlashes(c.str("openwaApiUrl"));
      for (String chatId : chatIds) {
        ctx.sender()
            .send(
                Sender.Request.post(
                    base + "/api/sessions/" + urlEncode(c.str("openwaSession")) + "/messages/send-text",
                    headers(
                        "Accept", "application/json",
                        "Content-Type", "application/json",
                        "X-Api-Key", c.str("openwaApiKey")),
                    Json.obj().put("chatId", chatId).put("text", text).toString()));
      }
      return OK;
    }
  }

  static final class Waha implements Provider {
    @Override
    public String name() {
      return "waha";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  Notify.stripTrailingSlashes(c.str("wahaApiUrl")) + "/api/sendText",
                  headers(
                      "Accept", "application/json",
                      "Content-Type", "application/json",
                      "X-Api-Key", c.str("wahaApiKey")),
                  Json.obj()
                      .put("session", c.str("wahaSession"))
                      .put("chatId", c.str("wahaChatId"))
                      .put("text", msg)
                      .toString()));
      return OK;
    }
  }

  static final class Whapi implements Provider {
    @Override
    public String name() {
      return "whapi";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  Notify.stripTrailingSlashes(c.str("whapiApiUrl", "https://gate.whapi.cloud/"))
                      + "/messages/text",
                  headers(
                      "Accept", "application/json",
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("whapiAuthToken")),
                  Json.obj().put("to", c.str("whapiRecipient")).put("body", msg).toString()));
      return OK;
    }
  }

  static final class Onesender implements Provider {
    @Override
    public String name() {
      return "Onesender";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      boolean priv = "private".equals(c.str("onesenderTypeReceiver"));
      String to =
          c.str("onesenderReceiver") + (priv ? "@s.whatsapp.net" : "@g.us");
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("onesenderURL"),
                  headers(
                      "Authorization", "Bearer " + c.str("onesenderToken"),
                      "Content-Type", "application/json"),
                  Json.obj()
                      .put("heartbeat", h)
                      .put("monitor", m)
                      .put("msg", msg)
                      .put("to", to)
                      .put("type", "text")
                      .put("recipient_type", priv ? "individual" : "group")
                      .put("text", Json.obj().put("body", msg).map())
                      .toString()));
      return OK;
    }
  }
}
