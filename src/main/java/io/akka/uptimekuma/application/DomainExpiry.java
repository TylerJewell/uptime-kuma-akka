package io.akka.uptimekuma.application;

import akka.javasdk.client.ComponentClient;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.notifications.HttpSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How long a monitor's domain registration has left, and warning about it.
 *
 * <p>The answer comes from the registry that holds the name, found through the directory of
 * registries the naming authority publishes. It is cached for a day per domain, because the answer
 * changes about once a year and the beat that asks runs every twenty seconds.
 */
public final class DomainExpiry {

  private static final Logger log = LoggerFactory.getLogger(DomainExpiry.class);

  /** The directory of registries, and how long its answer is kept. */
  private static final String BOOTSTRAP = "https://data.iana.org/rdap/dns.json";

  private static final Duration BOOTSTRAP_TTL = Duration.ofDays(7);
  private static final Duration DOMAIN_TTL = Duration.ofDays(1);

  /** Which field of a monitor holds the name, per type. Types absent from this map have none. */
  private static final Map<String, String> FIELD_BY_TYPE = fieldByType();

  private static Map<String, String> fieldByType() {
    Map<String, String> map = new LinkedHashMap<>();
    for (String type : List.of("http", "keyword", "json-query", "real-browser", "websocket-upgrade")) {
      map.put(type, "url");
    }
    for (String type :
        List.of(
            "port", "ping", "dns", "smtp", "snmp", "gamedig", "steam", "mqtt", "radius",
            "tailscale-ping", "sip-options")) {
      map.put(type, "hostname");
    }
    map.put("grpc-keyword", "grpcUrl");
    return Map.copyOf(map);
  }

  private DomainExpiry() {}

  /** Why a monitor cannot have its domain checked, or null when it can. */
  public static String unsupportedReason(MonitorConfig config) {
    String field = FIELD_BY_TYPE.get(config.type());
    if (field == null) {
      return "domain_expiry_unsupported_monitor_type";
    }
    String target =
        switch (field) {
          case "url" -> config.url();
          case "grpcUrl" -> config.grpcUrl();
          default -> config.hostname();
        };
    if (target == null || target.isBlank()) {
      return "domain_expiry_unsupported_missing_target";
    }
    String domain = registrableDomain(target);
    if (domain == null) {
      return "domain_expiry_unsupported_is_icann";
    }
    return null;
  }

  /**
   * The name a registry would know, taken from a URL or a host.
   *
   * <p>A public suffix list would be needed to be exactly right about a two-part suffix, so the
   * last two labels are taken and the registry directory is asked about the last one — which is
   * what decides whether the name is one anybody registers.
   */
  /** Whether a host is an address literal rather than a name — dotted-quad or bracketed v6. */
  public static boolean isAddress(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    if (host.startsWith("[") || host.contains(":")) {
      return true;
    }
    return host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
  }

  /** The host a target names, with any scheme, port and path taken off. */
  public static String hostOf(String target) {
    String host = target;
    try {
      if (target != null && target.contains("://")) {
        host = URI.create(target).getHost();
      }
    } catch (Exception e) {
      host = target;
    }
    return host == null ? target : host.split(":")[0];
  }

  public static String registrableDomain(String target) {
    String host = target;
    try {
      if (target.contains("://")) {
        host = URI.create(target).getHost();
      }
    } catch (Exception e) {
      host = target;
    }
    if (host == null) {
      return null;
    }
    host = host.split(":")[0];
    // An address is not a name anybody registers, and the registry directory has no entry for
    // one. The source reaches the same answer through its public-suffix list, which reports an
    // address as not being under ICANN's root at all.
    if (isAddress(host)) {
      return null;
    }
    String[] labels = host.split("\\.");
    if (labels.length < 2) {
      return null;
    }
    // A suffix with a country under it — "com.br" and its kind — needs three labels to name
    // something registrable, and the middle label of such a name is always short.
    if (labels.length >= 3 && labels[labels.length - 2].length() <= 3 && labels[labels.length - 1].length() == 2) {
      return labels[labels.length - 3]
          + "."
          + labels[labels.length - 2]
          + "."
          + labels[labels.length - 1];
    }
    return labels[labels.length - 2] + "." + labels[labels.length - 1];
  }

  private static Map<String, Object> bootstrapCache;
  private static Instant bootstrapFetchedAt;
  private static final Map<String, CachedExpiry> DOMAIN_CACHE = new LinkedHashMap<>();

  private record CachedExpiry(Instant expiry, Instant checkedAt, Integer lastNotifiedDays) {}

  /** The instant a name's registration ends, or null when no registry would say. */
  public static synchronized Instant expiryOf(String domain) {
    CachedExpiry cached = DOMAIN_CACHE.get(domain);
    if (cached != null
        && cached.checkedAt().isAfter(Instant.now().minus(DOMAIN_TTL))) {
      return cached.expiry();
    }
    String server = rdapServerFor(domain);
    if (server == null) {
      return null;
    }
    try {
      HttpClient client =
          HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(server.replaceAll("/+$", "") + "/domain/" + domain))
                  .header("Accept", "application/rdap+json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        return null;
      }
      Map<String, Object> body =
          io.akka.uptimekuma.notifications.Json.MAPPER.readValue(response.body(), Map.class);
      Object events = body.get("events");
      if (events instanceof List<?> list) {
        for (Object event : list) {
          if (event instanceof Map<?, ?> entry
              && "expiration".equals(entry.get("eventAction"))) {
            Instant expiry = Instant.parse(String.valueOf(entry.get("eventDate")));
            Instant previous = cached == null ? null : cached.expiry();
            // A renewal re-arms every warning, because the thresholds already passed were about
            // a registration that has been extended.
            Integer notified =
                cached == null || (previous != null && expiry.isAfter(previous))
                    ? null
                    : cached.lastNotifiedDays();
            DOMAIN_CACHE.put(domain, new CachedExpiry(expiry, Instant.now(), notified));
            return expiry;
          }
        }
      }
    } catch (Exception e) {
      log.debug("cannot read the registration of {}", domain, e);
    }
    DOMAIN_CACHE.put(domain, new CachedExpiry(null, Instant.now(), null));
    return null;
  }

  private static synchronized String rdapServerFor(String domain) {
    if (bootstrapCache == null
        || bootstrapFetchedAt == null
        || bootstrapFetchedAt.isBefore(Instant.now().minus(BOOTSTRAP_TTL))) {
      try {
        HttpClient client =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create(BOOTSTRAP)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        bootstrapCache =
            io.akka.uptimekuma.notifications.Json.MAPPER.readValue(response.body(), Map.class);
        bootstrapFetchedAt = Instant.now();
      } catch (Exception e) {
        log.debug("cannot read the registry directory", e);
        return null;
      }
    }
    String suffix = domain.substring(domain.indexOf('.') + 1);
    Object services = bootstrapCache.get("services");
    if (!(services instanceof List<?> list)) {
      return null;
    }
    for (Object service : list) {
      if (!(service instanceof List<?> pair) || pair.size() < 2) {
        continue;
      }
      Object suffixes = pair.get(0);
      Object servers = pair.get(1);
      if (suffixes instanceof List<?> names
          && servers instanceof List<?> urls
          && !urls.isEmpty()) {
        for (Object name : names) {
          if (suffix.equalsIgnoreCase(String.valueOf(name))
              || domain.endsWith("." + name)) {
            return String.valueOf(urls.get(0));
          }
        }
      }
    }
    return null;
  }

  /** Ask, and warn if the registration is inside one of the configured thresholds. */
  public static void checkAndNotify(ComponentClient componentClient, MonitorConfig config) {
    if (unsupportedReason(config) != null) {
      return;
    }
    String target =
        switch (FIELD_BY_TYPE.get(config.type())) {
          case "url" -> config.url();
          case "grpcUrl" -> config.grpcUrl();
          default -> config.hostname();
        };
    String domain = registrableDomain(target);
    Instant expiry = expiryOf(domain);
    if (expiry == null) {
      return;
    }
    long daysRemaining = ChronoUnit.DAYS.between(Instant.now(), expiry);
    componentClient
        .forEventSourcedEntity(config.id())
        .method(MonitorEntity::recordDomainInfo)
        .invoke(Map.of("domain", domain, "daysRemaining", daysRemaining, "expiry", expiry.toString()));

    List<Integer> thresholds =
        Settings.days(
            componentClient, "domainExpiryNotifyDays", Settings.DEFAULT_DOMAIN_EXPIRY_NOTIFY_DAYS);
    List<Integer> sorted = new java.util.ArrayList<>(thresholds);
    java.util.Collections.sort(sorted);
    synchronized (DomainExpiry.class) {
      CachedExpiry cached = DOMAIN_CACHE.get(domain);
      for (Integer threshold : sorted) {
        if (daysRemaining > threshold) {
          continue;
        }
        if (cached != null
            && cached.lastNotifiedDays() != null
            && cached.lastNotifiedDays() <= threshold) {
          return;
        }
        Notifications.sendDomainWarning(
            componentClient,
            new HttpSender(),
            Versions.APP_VERSION,
            config,
            domain,
            daysRemaining);
        DOMAIN_CACHE.put(
            domain,
            new CachedExpiry(
                expiry, cached == null ? Instant.now() : cached.checkedAt(), threshold));
        return;
      }
    }
  }
}
