package io.akka.uptimekuma.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.uptimekuma.application.ApiKeyEntity;
import io.akka.uptimekuma.application.ApiKeyListView;
import io.akka.uptimekuma.application.HeartbeatAnnouncementEntity;
import io.akka.uptimekuma.application.Ids;
import io.akka.uptimekuma.application.Maintenances;
import io.akka.uptimekuma.application.MonitorEntity;
import io.akka.uptimekuma.application.MonitorListView;
import io.akka.uptimekuma.application.RecordRow;
import io.akka.uptimekuma.application.StatusPageListView;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.application.Versions;
import io.akka.uptimekuma.domain.BeatDecision;
import io.akka.uptimekuma.domain.BadgeMaker;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Passwords;
import io.akka.uptimekuma.domain.Status;
import io.akka.uptimekuma.domain.UptimeCalculator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The surface that is not the interface: the push endpoint, the badges, the metrics and the two
 * calls a client makes before it has signed in.
 *
 * <p>These are what other systems reach — a job that beats into a push monitor, a page that embeds
 * a badge, a metrics collector — so their paths, their query parameters and the shape of what they
 * answer are the source's exactly.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api")
public class ApiEndpoint extends AbstractHttpEndpoint {

  /** The largest response time a push may report, in milliseconds. */
  private static final double MAX_PING_MS = 100000000000d;

  private final ComponentClient componentClient;
  private final akka.javasdk.timer.TimerScheduler timers;

  public ApiEndpoint(ComponentClient componentClient, akka.javasdk.timer.TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  /** Where a visitor arriving at the root should be sent. */
  @Get("/entry-page")
  public HttpResponse entryPage() {
    Map<String, Object> body = new LinkedHashMap<>();
    String entry = Settings.storedString(componentClient, "entryPage");
    body.put("type", "entryPage");
    body.put("entryPage", entry);
    return HttpResponses.ok(body);
  }

  /**
   * A beat written from outside.
   *
   * <p>The only route that records a heartbeat without a check having run: a push monitor is beaten
   * into rather than probed, so this is where its status is decided.
   */
  @Get("/push/{pushToken}")
  public HttpResponse push(String pushToken) {
    // The first monitor holding this token, which is what the source's own lookup takes. Two
    // monitors can hold one: the interface generates a token but a person can type one, and
    // cloning a monitor copies it.
    List<MonitorListView.MonitorRow> holders =
        componentClient.forView().method(MonitorListView::byPushToken).invoke(pushToken).monitors();
    if (holders.isEmpty() || !holders.get(0).active()) {
      return failure("Monitor not found or not active.");
    }
    String monitorId = holders.get(0).id();

    String msg = requestContext().queryParams().getString("msg").orElse("OK");
    Double ping = null;
    Optional<String> pingParam = requestContext().queryParams().getString("ping");
    if (pingParam.isPresent() && !pingParam.get().isBlank()) {
      try {
        ping = Double.valueOf(pingParam.get());
      } catch (NumberFormatException e) {
        ping = null;
      }
      if (ping != null && (ping < 0 || ping > MAX_PING_MS)) {
        return failure("Invalid ping value. Must be between 0 and 100000000000 ms.");
      }
    }
    boolean up = "up".equals(requestContext().queryParams().getString("status").orElse("up"));

    MonitorEntity.BeatContext state =
        componentClient
            .forEventSourcedEntity(monitorId)
            .method(MonitorEntity::beatContext)
            .invoke();
    if (!state.created()) {
      return failure("Monitor not found or not active.");
    }

    boolean underMaintenance =
        Maintenances.covers(
            componentClient, monitorId, Settings.timezone(componentClient), System.currentTimeMillis());
    BeatDecision.CheckOutcome outcome =
        underMaintenance
            ? BeatDecision.CheckOutcome.maintenance()
            : up
                ? BeatDecision.CheckOutcome.up(msg, ping)
                : BeatDecision.CheckOutcome.failed(msg, ping, null);

    MonitorEntity.BeatResult result =
        componentClient
            .forEventSourcedEntity(monitorId)
            .method(MonitorEntity::recordBeat)
            .invoke(
                new MonitorEntity.RecordBeat(
                    outcome, state.nextSequence(), System.currentTimeMillis(), true));
    // A beat the monitor had already recorded is not published again. Two pushes can carry the
    // same number — a caller repeating itself, or the runtime retrying a call it could not confirm
    // — and the beat that number names may since have been cleared away, in which case publishing
    // it again writes to a key that has been deleted and the whole request fails.
    if (!result.duplicate()) {
      long feedSequence = Ids.nextNumber(componentClient, "feed");
      componentClient
          .forKeyValueEntity(
              HeartbeatAnnouncementEntity.key(monitorId, result.heartbeat().sequence()))
          .method(HeartbeatAnnouncementEntity::publish)
          .invoke(new HeartbeatAnnouncementEntity.Announcement(feedSequence, result.heartbeat()));
    }

    if (result.sendNotification()) {
      MonitorEntity.State full =
          componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
      io.akka.uptimekuma.application.Notifications.send(
          componentClient,
          new io.akka.uptimekuma.notifications.HttpSender(),
          Versions.APP_VERSION,
          state.config(),
          result.heartbeat(),
          full.importantHistory());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    return HttpResponses.ok(body);
  }

  // ---- badges ---------------------------------------------------------------------------------

  @Get("/badge/{id}/status")
  public HttpResponse statusBadge(String id) {
    var params = requestContext().queryParams();
    Integer override = params.getInteger("value").orElse(null);
    Integer status = override != null ? override : (isMonitorPublic(id) ? lastStatus(id) : null);
    String label = params.getString("label").orElse("Status");
    String style = params.getString("style").orElse("flat");
    String message;
    String color;
    if (status == null) {
      // One part, not two. The source sets only the message when it has nothing to report, and a
      // badge with no label is drawn as a single grey pill rather than as `Status: N/A`.
      return svg(BadgeMaker.make(null, "N/A", "#999", null, style));
    }
    {
      switch (Status.of(status)) {
        case DOWN -> {
          message = params.getString("downLabel").orElse("Down");
          color = params.getString("downColor").orElse("#c2290a");
        }
        case UP -> {
          message = params.getString("upLabel").orElse("Up");
          color = params.getString("upColor").orElse("#66c20a");
        }
        case PENDING -> {
          message = params.getString("pendingLabel").orElse("Pending");
          color = params.getString("pendingColor").orElse("#f8a306");
        }
        default -> {
          message = params.getString("maintenanceLabel").orElse("Maintenance");
          color = params.getString("maintenanceColor").orElse("#1747f5");
        }
      }
    }
    return svg(BadgeMaker.make(label, message, color, null, style));
  }

  @Get("/badge/{id}/uptime/{duration}")
  public HttpResponse uptimeBadge(String id, String duration) {
    return renderUptime(id, duration);
  }

  @Get("/badge/{id}/uptime")
  public HttpResponse uptimeBadgeDefault(String id) {
    return renderUptime(id, "24h");
  }

  /** The source's own refusal shape for a badge it cannot draw: a JSON body under 403. */
  private HttpResponse badgeRefusal(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "fail");
    body.put("msg", message);
    return HttpResponse.create()
        .withStatus(StatusCodes.FORBIDDEN)
        .withEntity(
            HttpEntities.create(
                ContentTypes.APPLICATION_JSON,
                io.akka.uptimekuma.notifications.Json.write(body)));
  }

  private HttpResponse renderUptime(String id, String rawDuration) {
    var params = requestContext().queryParams();
    String duration = rawDuration.matches("^[0-9]+$") ? rawDuration + "h" : rawDuration;
    Double override = params.getDouble("value").orElse(null);
    double uptime;
    if (override != null) {
      uptime = override;
    } else {
      UptimeCalculator stats = isMonitorPublic(id) ? statsOf(id) : null;
      if (stats == null) {
        return svg(BadgeMaker.make(null, "N/A", "#999", null,
            params.getString("style").orElse("flat")));
      }
      try {
        uptime = stats.getDataByDuration(duration, System.currentTimeMillis()).uptime();
      } catch (IllegalArgumentException e) {
        // A duration nothing can read is refused rather than drawn, in the shape the source's own
        // badge routes refuse in: a JSON body under 403.
        return badgeRefusal(e.getMessage());
      }
    }
    String clean = new java.math.BigDecimal(uptime * 100).round(new java.math.MathContext(4)).toString();
    String label =
        params
            .getString("label")
            .orElse(
                "Uptime ("
                    + duration.substring(0, duration.length() - 1)
                    + params.getString("labelSuffix").orElse("h")
                    + ")");
    String message =
        join(
            params.getString("prefix").orElse(null),
            clean,
            params.getString("suffix").orElse("%"));
    String color = params.getString("color").orElse(percentageToColor(uptime));
    return svg(
        BadgeMaker.make(
            join(params.getString("labelPrefix").orElse(null), label, null),
            message,
            color,
            params.getString("labelColor").orElse(null),
            params.getString("style").orElse("flat")));
  }

  @Get("/badge/{id}/ping/{duration}")
  public HttpResponse pingBadge(String id, String duration) {
    return renderPing(id, duration);
  }

  @Get("/badge/{id}/ping")
  public HttpResponse pingBadgeDefault(String id) {
    return renderPing(id, "24h");
  }

  private HttpResponse renderPing(String id, String rawDuration) {
    var params = requestContext().queryParams();
    String duration = rawDuration.matches("^[0-9]+$") ? rawDuration + "h" : rawDuration;
    Double override = params.getDouble("value").orElse(null);
    Double average = override;
    if (average == null) {
      UptimeCalculator stats = statsOf(id);
      if (stats != null) {
        average = stats.getDataByDuration(duration, System.currentTimeMillis()).avgPing();
      }
    }
    String label =
        params
            .getString("label")
            .orElse(
                "Avg. Ping ("
                    + duration.substring(0, duration.length() - 1)
                    + params.getString("labelSuffix").orElse("h")
                    + ")");
    if (average == null || !isMonitorPublic(id)) {
      // One part: the source's empty branches set a message and never a label.
      return svg(BadgeMaker.make(null, "N/A", "#999", null,
          params.getString("style").orElse("flat")));
    }
    String message =
        join(
            params.getString("prefix").orElse(null),
            String.valueOf((int) (double) average),
            params.getString("suffix").orElse("ms"));
    return svg(
        BadgeMaker.make(
            join(params.getString("labelPrefix").orElse(null), label, null),
            message,
            params.getString("color").orElse("blue"),
            params.getString("labelColor").orElse(null),
            params.getString("style").orElse("flat")));
  }

  @Get("/badge/{id}/avg-response/{duration}")
  public HttpResponse avgResponseBadge(String id, String duration) {
    return renderAvgResponse(id, duration);
  }

  @Get("/badge/{id}/avg-response")
  public HttpResponse avgResponseBadgeDefault(String id) {
    return renderAvgResponse(id, "24");
  }

  private HttpResponse renderAvgResponse(String id, String rawDuration) {
    var params = requestContext().queryParams();
    int hours;
    try {
      hours = Math.min(Integer.parseInt(rawDuration), 720);
    } catch (NumberFormatException e) {
      hours = 24;
    }
    Double override = params.getDouble("value").orElse(null);
    Double average = override;
    if (average == null) {
      UptimeCalculator stats = statsOf(id);
      if (stats != null) {
        average = stats.getData(hours, "hour", System.currentTimeMillis()).avgPing();
      }
    }
    String label = params.getString("label").orElse("Avg. Response (" + hours + "h)");
    if (average == null || average == 0 || !isMonitorPublic(id)) {
      // One part: the source's empty branches set a message and never a label.
      return svg(BadgeMaker.make(null, "N/A", "#999", null,
          params.getString("style").orElse("flat")));
    }
    return svg(
        BadgeMaker.make(
            join(params.getString("labelPrefix").orElse(null), label, null),
            join(
                params.getString("prefix").orElse(null),
                String.valueOf((int) (double) average),
                params.getString("suffix").orElse("ms")),
            params.getString("color").orElse("blue"),
            params.getString("labelColor").orElse(null),
            params.getString("style").orElse("flat")));
  }

  @Get("/badge/{id}/response")
  public HttpResponse responseBadge(String id) {
    var params = requestContext().queryParams();
    Double override = params.getDouble("value").orElse(null);
    Double ping = override;
    if (ping == null) {
      Heartbeat last = lastBeat(id);
      ping = last == null ? null : last.ping();
    }
    String label = params.getString("label").orElse("Response");
    if (ping == null || ping == 0 || !isMonitorPublic(id)) {
      // One part: the source's empty branches set a message and never a label.
      return svg(BadgeMaker.make(null, "N/A", "#999", null,
          params.getString("style").orElse("flat")));
    }
    return svg(
        BadgeMaker.make(
            join(params.getString("labelPrefix").orElse(null), label, null),
            join(
                params.getString("prefix").orElse(null),
                String.valueOf((int) (double) ping),
                params.getString("suffix").orElse("ms")),
            params.getString("color").orElse("blue"),
            params.getString("labelColor").orElse(null),
            params.getString("style").orElse("flat")));
  }

  @Get("/badge/{id}/cert-exp")
  public HttpResponse certExpiryBadge(String id) {
    var params = requestContext().queryParams();
    boolean asDate = params.getString("date").isPresent();
    String label = params.getString("label").orElse("Cert Exp.");
    String style = params.getString("style").orElse("flat");
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    Map<String, Object> tls = isMonitorPublic(id) ? state.tlsInfo() : null;
    if (tls == null || tls.isEmpty()) {
      // A monitor nobody published, and a monitor with no certificate stored, are the same answer
      // here: the source's not-public branch and its no-certificate branch both draw one grey
      // pill, differing only in what it says.
      return svg(BadgeMaker.make(null, isMonitorPublic(id) ? "No/Bad Cert" : "N/A", "#999", null, style));
    }
    if (Boolean.FALSE.equals(tls.get("valid"))) {
      return svg(
          BadgeMaker.make(
              null, "Bad Cert", params.getString("downColor").orElse("#c2290a"), null, style));
    }
    Object certInfo = tls.get("certInfo");
    Object daysObject =
        certInfo instanceof Map<?, ?> map ? ((Map<?, ?>) map).get("daysRemaining") : null;
    long days = daysObject == null ? 0 : (long) Double.parseDouble(String.valueOf(daysObject));
    int warnDays = Integer.parseInt(params.getString("warnDays").orElse("14"));
    int downDays = Integer.parseInt(params.getString("downDays").orElse("7"));
    String color =
        days > warnDays
            ? params.getString("upColor").orElse("#66c20a")
            : days > downDays
                ? params.getString("warnColor").orElse("#eed202")
                : params.getString("downColor").orElse("#c2290a");
    String message;
    if (asDate) {
      Object validTo =
          certInfo instanceof Map<?, ?> map ? ((Map<?, ?>) map).get("validTo") : null;
      message =
          join(
              params.getString("prefix").orElse(null),
              String.valueOf(validTo),
              params.getString("suffix").orElse(""));
    } else {
      message =
          join(
              params.getString("prefix").orElse(null),
              String.valueOf(days),
              params.getString("suffix").orElse(" days"));
    }
    return svg(
        BadgeMaker.make(
            join(params.getString("labelPrefix").orElse(null), label, null),
            message,
            color,
            params.getString("labelColor").orElse(null),
            style));
  }

  // ---- shared ---------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJson(String json) {
    try {
      return io.akka.uptimekuma.notifications.Json.MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }

  private Integer lastStatus(String id) {
    Heartbeat last = lastBeat(id);
    return last == null ? null : last.status();
  }

  /**
   * Whether a badge is allowed to say anything about this monitor.
   *
   * <p>A badge URL takes no credentials, so a badge for any monitor at all would tell the world
   * whether a private service is up. The source answers a badge only for a monitor that somebody
   * has put into a group on a published status page, and draws `N/A` for every other -- so a
   * monitor becomes public by being published, deliberately, and not by existing. R112.
   */
  private boolean isMonitorPublic(String id) {
    for (RecordRow row :
        componentClient.forView().method(StatusPageListView::all).invoke().entries()) {
      Map<String, Object> page = readJson(row.json());
      if (!Boolean.TRUE.equals(page.get("published"))) {
        continue;
      }
      Object groups = page.get("publicGroupList");
      if (!(groups instanceof List<?> list)) {
        continue;
      }
      for (Object entry : list) {
        if (!(entry instanceof Map<?, ?> group)) {
          continue;
        }
        Object monitors = group.get("monitorList");
        if (!(monitors instanceof List<?> inside)) {
          continue;
        }
        for (Object monitor : inside) {
          if (monitor instanceof Map<?, ?> one && id.equals(String.valueOf(one.get("id")))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private Heartbeat lastBeat(String id) {
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    return state.created() ? state.last() : null;
  }

  private UptimeCalculator statsOf(String id) {
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    return state.created() ? state.stats() : null;
  }

  /**
   * A colour on the red-to-green sweep, at the position the ratio names.
   *
   * <p>Ten degrees of hue is red and ninety is green, so a badge's colour carries the figure as
   * well as the number does.
   */
  static String percentageToColor(double ratio) {
    double hue = ratio * (90 - 10) + 10;
    java.awt.Color color = java.awt.Color.getHSBColor((float) (hue / 360.0), 0.90f, 0.40f);
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
  }

  private static String join(String prefix, String middle, String suffix) {
    List<String> parts = new ArrayList<>();
    if (prefix != null && !prefix.isEmpty()) {
      parts.add(prefix);
    }
    if (middle != null && !middle.isEmpty()) {
      parts.add(middle);
    }
    if (suffix != null && !suffix.isEmpty()) {
      parts.add(suffix);
    }
    return String.join(" ", parts);
  }

  private static HttpResponse svg(String body) {
    return HttpResponse.create()
        .withEntity(
            HttpEntities.create(
                ContentTypes.parse("image/svg+xml; charset=UTF-8"),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private HttpResponse failure(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    body.put("msg", message);
    return HttpResponse.create()
        .withStatus(StatusCodes.NOT_FOUND)
        .withEntity(
            HttpEntities.create(
                ContentTypes.APPLICATION_JSON,
                io.akka.uptimekuma.notifications.Json.write(body)));
  }

  /** Either an API key in the password field, or an account's own username and password. */
  private boolean authorised() {
    if (Settings.flag(componentClient, "disableAuth")) {
      return true;
    }
    Optional<String> header = requestContext().requestHeader("Authorization").map(h -> h.value());
    if (header.isEmpty() || !header.get().startsWith("Basic ")) {
      return false;
    }
    String decoded =
        new String(Base64.getDecoder().decode(header.get().substring(6)));
    int split = decoded.indexOf(':');
    if (split < 0) {
      return false;
    }
    String username = decoded.substring(0, split);
    String password = decoded.substring(split + 1);

    if (password.startsWith("uk") && password.contains("_")) {
      return verifyApiKey(password);
    }
    StoredRecord user = Sessions.byUsername(componentClient, username);
    return user != null && user.flag("active") && Passwords.verify(password, user.str("password"));
  }

  /**
   * A key names its own row: the digits between the prefix and the underscore are the identifier,
   * and what follows is the secret, which is checked against the stored hash.
   */
  private boolean verifyApiKey(String presented) {
    int underscore = presented.indexOf('_');
    String id = presented.substring(2, underscore);
    String clear = presented.substring(underscore + 1);
    StoredRecord key = componentClient.forKeyValueEntity(id).method(ApiKeyEntity::get).invoke();
    if (!key.exists() || !key.flag("active")) {
      return false;
    }
    Object expires = key.get("expires");
    if (expires != null && !String.valueOf(expires).isBlank()) {
      try {
        if (java.time.Instant.parse(String.valueOf(expires)).isBefore(java.time.Instant.now())) {
          return false;
        }
      } catch (Exception ignored) {
        // An expiry that cannot be read is treated as no expiry, which is what the source does
        // with a null one.
      }
    }
    return Passwords.verify(clear, key.str("key"));
  }
}
