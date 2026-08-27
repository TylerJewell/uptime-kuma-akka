package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.Status;
import java.util.List;
import java.util.Map;

/**
 * Everything a check needs that is not on the monitor itself.
 *
 * @param settings the server settings, which four checks read — the Steam API key, the Globalping
 *     token, the Chrome executable and the certificate expiry thresholds
 * @param dockerHost the daemon a docker monitor talks to, already resolved from its id
 * @param proxy the proxy an http monitor was given, already resolved
 * @param remoteBrowserUrl where a real-browser monitor connects, already resolved
 * @param children the last status of each child of a group monitor, in the order the group lists
 *     them. Empty for every other type.
 * @param previousBeat the last beat this monitor recorded, which only the push type reads: it
 *     has no check of its own and decides from how long ago that beat was
 * @param nowEpochMillis the instant this beat is running at. Passed in rather than read so a
 *     sequence of beats can be replayed against a clock the caller controls.
 */
public record CheckContext(
    Map<String, String> settings,
    DockerHostConfig dockerHost,
    ProxyConfig proxy,
    String remoteBrowserUrl,
    List<ChildStatus> children,
    Heartbeat previousBeat,
    long nowEpochMillis) {

  /** @param active a paused child is ignored by a group entirely, rather than counting as down */
  public record ChildStatus(String id, String name, boolean active, Status status) {}

  public record DockerHostConfig(String id, String name, String dockerDaemon, String dockerType) {}

  public record ProxyConfig(
      String id,
      String protocol,
      String host,
      int port,
      boolean auth,
      String username,
      String password) {}

  public static CheckContext plain(long now) {
    return new CheckContext(Map.of(), null, null, null, List.of(), null, now);
  }

  public static CheckContext withSettings(Map<String, String> settings, long now) {
    return new CheckContext(settings, null, null, null, List.of(), null, now);
  }

  public String setting(String key) {
    return settings.get(key);
  }
}
