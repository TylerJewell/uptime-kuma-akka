package io.akka.uptimekuma.domain;

import java.util.List;
import java.util.Optional;

/**
 * What a monitor is configured to do.
 *
 * @param intervalSeconds the ordinary cadence
 * @param retryIntervalSeconds the cadence while PENDING; zero means the ordinary cadence is used
 *     there too, rather than meaning no delay
 * @param maxRetries failures tolerated before DOWN; zero means there is no PENDING state at all,
 *     not that one retry is allowed
 * @param resendIntervalSeconds how many unimportant DOWN beats pass before the notification is sent
 *     again; zero disables re-sending. Named for seconds by the source and counted in beats, and
 *     kept that way so a configuration carried across from uptime-kuma means the same thing
 * @param notificationIds fan-out targets; the order is observable
 */
public record MonitorConfig(
    String url,
    int intervalSeconds,
    int retryIntervalSeconds,
    int maxRetries,
    int resendIntervalSeconds,
    List<String> notificationIds) {

  public MonitorConfig {
    // Normalisation only. Validation is `validate()`, called at the boundary — this
    // constructor also runs when a persisted `Created` event is read back, and a rule
    // that rejects a value there makes an entity that can no longer be replayed.
    notificationIds = notificationIds == null ? List.of() : List.copyOf(notificationIds);
  }

  /** The reason this configuration cannot be used, or empty if it can. */
  public Optional<String> validate() {
    if (url == null || url.isBlank()) {
      return Optional.of("a monitor needs a url");
    }
    // The source substitutes one second for a non-positive interval on a field its own
    // validation requires to be at least twenty. A substituted cadence is a monitor
    // checking at a rate nobody configured, so this refuses instead. SPEC-001 §4 OD3.
    if (intervalSeconds <= 0) {
      return Optional.of("intervalSeconds must be positive, was " + intervalSeconds);
    }
    if (retryIntervalSeconds < 0) {
      return Optional.of("retryIntervalSeconds must not be negative");
    }
    if (maxRetries < 0) {
      return Optional.of("maxRetries must not be negative");
    }
    if (resendIntervalSeconds < 0) {
      return Optional.of("resendIntervalSeconds must not be negative");
    }
    return Optional.empty();
  }
}
