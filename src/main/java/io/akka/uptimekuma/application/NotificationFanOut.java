package io.akka.uptimekuma.application;

import akka.javasdk.client.ComponentClient;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers one notifying beat to every notification attached to the monitor.
 *
 * <p>Each delivery is attempted independently. A target that refuses costs its own delivery and
 * nothing else — not the targets after it, and not the beat: uptime-kuma's catch sits inside the
 * loop for exactly this reason, and a monitoring system whose alerting stops because one webhook
 * is down has failed at the thing it is for. SPEC-001 §3 R17–R18.
 */
public final class NotificationFanOut {

  private static final Logger log = LoggerFactory.getLogger(NotificationFanOut.class);

  // One client for the process, for the reason given in HttpProbe.
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private final ComponentClient componentClient;

  public NotificationFanOut(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** The names of the targets that accepted delivery, in the order they were attempted. */
  public List<String> send(String monitorId, MonitorConfig config, Heartbeat beat) {
    String text = beat.status() == Status.UP ? "✅ Up" : "🔴 Down";
    String message = "[" + monitorId + "] [" + text + "] " + nullToNa(beat.message());
    var delivered = new ArrayList<String>();

    for (String notificationId : config.notificationIds()) {
      try {
        var target =
            componentClient
                .forKeyValueEntity(notificationId)
                .method(NotificationEntity::get)
                .invoke()
                .target();
        if (target == null) {
          log.error("Cannot send notification to {}: no such notification", notificationId);
          continue;
        }
        post(target.webhookUrl(), body(monitorId, message, beat));
        delivered.add(target.name());
      } catch (Exception e) {
        log.error("Cannot send notification to {}", notificationId, e);
      }
    }
    return List.copyOf(delivered);
  }

  private void post(String url, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() < 200 || response.statusCode() > 299) {
      throw new IllegalStateException("notification target answered " + response.statusCode());
    }
  }

  private static String body(String monitorId, String message, Heartbeat beat) {
    return "{\"monitor\":\""
        + monitorId
        + "\",\"msg\":\""
        + escape(message)
        + "\",\"status\":\""
        + beat.status()
        + "\",\"sequence\":"
        + beat.sequence()
        + ",\"retries\":"
        + beat.retries()
        + ",\"downCount\":"
        + beat.downCount()
        + ",\"important\":"
        + beat.important()
        + ",\"time\":"
        + beat.atEpochMillis()
        + "}";
  }

  /** A payload with no message at all is rejected by several of the source's own providers. */
  private static String nullToNa(String message) {
    return message == null || message.isBlank() ? "N/A" : message;
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
  }
}
