package io.akka.uptimekuma.domain;

/**
 * Somewhere a notification is delivered to.
 *
 * <p>uptime-kuma ships 106 of these, one per service it can talk to. This port carries one — an
 * outbound POST — because the slice is the loop around the transport, not the transport.
 */
public record NotificationTarget(String name, String webhookUrl) {

  public NotificationTarget {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a notification target needs a name");
    }
    if (webhookUrl == null || webhookUrl.isBlank()) {
      throw new IllegalArgumentException("a notification target needs a url");
    }
  }
}
