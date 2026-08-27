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
import io.akka.uptimekuma.application.ApiKeyEntity;
import io.akka.uptimekuma.application.MonitorEntity;
import io.akka.uptimekuma.application.MonitorListView;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Passwords;
import io.akka.uptimekuma.domain.UptimeCalculator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a metrics collector scrapes.
 *
 * <p>Six gauges per monitor, with the labels the source declares. A collector configured against
 * the source scrapes this without being told anything is different — which is the point of getting
 * the names and the label set exactly right rather than approximately.
 *
 * <p>Behind the same authentication as the source's: either an API key or an account's own
 * credentials, sent as ordinary browser authentication.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/metrics")
public class MetricsEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public MetricsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("")
  public HttpResponse scrape() {
    if (!authorised()) {
      return HttpResponse.create()
          .withStatus(StatusCodes.UNAUTHORIZED)
          .addHeader(
              akka.http.javadsl.model.headers.RawHeader.create(
                  "WWW-Authenticate", "Basic realm=\"Uptime Kuma\""));
    }
    StringBuilder out = new StringBuilder();
    out.append(
        "# HELP monitor_cert_days_remaining The number of days remaining until the certificate expires\n");
    out.append("# TYPE monitor_cert_days_remaining gauge\n");
    out.append("# HELP monitor_cert_is_valid Is the certificate still valid? (1 = Yes, 0= No)\n");
    out.append("# TYPE monitor_cert_is_valid gauge\n");
    out.append(
        "# HELP monitor_uptime_ratio Uptime ratio calculated over sliding window specified by the 'window' label. (0.0 - 1.0)\n");
    out.append("# TYPE monitor_uptime_ratio gauge\n");
    out.append(
        "# HELP monitor_response_time_seconds Average response time in seconds calculated over sliding window specified by the 'window' label\n");
    out.append("# TYPE monitor_response_time_seconds gauge\n");
    out.append("# HELP monitor_response_time Monitor Response Time (ms)\n");
    out.append("# TYPE monitor_response_time gauge\n");
    out.append(
        "# HELP monitor_status Monitor Status (1 = UP, 0= DOWN, 2= PENDING, 3= MAINTENANCE)\n");
    out.append("# TYPE monitor_status gauge\n");

    long now = System.currentTimeMillis();
    for (MonitorListView.MonitorRow row :
        componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
      MonitorEntity.State state =
          componentClient.forEventSourcedEntity(row.id()).method(MonitorEntity::get).invoke();
      if (!state.created() || !state.active()) {
        // A monitor that is not running has no gauges at all. The source builds them when a
        // monitor starts and removes every one of them when it stops, so a paused monitor
        // disappears from the scrape rather than freezing at whatever it last reported —
        // which is what stops an alert firing on a figure nobody is updating. R88.
        continue;
      }
      MonitorConfig config = state.config();
      String labels = labelsFor(config, state);
      Heartbeat last = state.last();
      out.append("monitor_status{")
          .append(labels)
          .append("} ")
          .append(row.lastStatus())
          .append('\n');
      out.append("monitor_response_time{")
          .append(labels)
          .append("} ")
          .append(last == null || last.ping() == null ? -1 : last.ping().intValue())
          .append('\n');
      appendWindow(out, labels, "1d", state.stats().get24Hour(now));
      appendWindow(out, labels, "30d", state.stats().get30Day(now));
      appendWindow(out, labels, "365d", state.stats().get1Year(now));
      Object certInfo = state.tlsInfo() == null ? null : state.tlsInfo().get("certInfo");
      if (certInfo instanceof Map<?, ?> map && map.get("daysRemaining") != null) {
        out.append("monitor_cert_days_remaining{")
            .append(labels)
            .append("} ")
            .append(map.get("daysRemaining"))
            .append('\n');
        out.append("monitor_cert_is_valid{")
            .append(labels)
            .append("} ")
            .append(Boolean.TRUE.equals(state.tlsInfo().get("valid")) ? 1 : 0)
            .append('\n');
      }
    }
    return HttpResponse.create()
        .withEntity(
            HttpEntities.create(
                ContentTypes.parse("text/plain; version=0.0.4; charset=UTF-8"),
                out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static void appendWindow(
      StringBuilder out, String labels, String window, UptimeCalculator.Window figures) {
    out.append("monitor_uptime_ratio{")
        .append(labels)
        .append(",window=\"")
        .append(window)
        .append("\"} ")
        .append(figures.uptime())
        .append('\n');
    if (figures.avgPing() != null) {
      out.append("monitor_response_time_seconds{")
          .append(labels)
          .append(",window=\"")
          .append(window)
          .append("\"} ")
          .append(figures.avgPing() / 1000.0)
          .append('\n');
    }
  }

  /**
   * The labels every gauge carries.
   *
   * <p>A monitor's labels also carry every one of its own tags, so a collector can group by them.
   * A tag name is sanitised to the characters a label name may hold.
   */
  private static String labelsFor(MonitorConfig config, MonitorEntity.State state) {
    StringBuilder labels = new StringBuilder();
    if (config.tags() != null) {
      for (Map<String, Object> tag : config.tags()) {
        String name = sanitize(String.valueOf(tag.get("name")));
        if (name.isEmpty()) {
          continue;
        }
        labels
            .append(name)
            .append("=\"")
            .append(escape(String.valueOf(tag.getOrDefault("value", ""))))
            .append("\",");
      }
    }
    labels
        .append("monitor_id=\"")
        .append(escape(config.id()))
        .append("\",monitor_name=\"")
        .append(escape(config.name()))
        .append("\",monitor_type=\"")
        .append(escape(config.type()))
        .append("\",monitor_url=\"")
        .append(escape(config.url()))
        .append("\",monitor_hostname=\"")
        .append(escape(config.hostname()))
        .append("\",monitor_port=\"")
        .append(config.port() == null ? "" : config.port())
        .append("\"");
    return labels.toString();
  }

  /** A label name may hold letters, digits and underscores, and may not start with a digit. */
  static String sanitize(String name) {
    String stripped = name == null ? "" : name.replaceAll("[^a-zA-Z0-9_]", "");
    return stripped.replaceAll("^[^a-zA-Z_]+", "");
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private boolean authorised() {
    if (Settings.flag(componentClient, "disableAuth")) {
      return true;
    }
    Optional<String> header = requestContext().requestHeader("Authorization").map(h -> h.value());
    if (header.isEmpty() || !header.get().startsWith("Basic ")) {
      return false;
    }
    String decoded = new String(Base64.getDecoder().decode(header.get().substring(6)));
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

  private boolean verifyApiKey(String presented) {
    int underscore = presented.indexOf('_');
    // A key is `uk<id>_<secret>`. Anything else is not a key, and saying so is the answer —
    // taking it apart anyway turns a malformed header from a refusal into a server error.
    if (underscore <= 2 || underscore == presented.length() - 1) {
      return false;
    }
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
