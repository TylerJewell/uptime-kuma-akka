package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.AcceptedStatusCodes;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The monitor types that ask somebody else's service, or a local daemon, about a thing. */
final class ExternalChecks {

  private ExternalChecks() {}

  static List<Check> all() {
    return List.of(
        new PushCheck(),
        new DockerCheck(),
        new SteamCheck(),
        new GamedigCheck(),
        new GlobalpingCheck(),
        new RealBrowserCheck());
  }

  /**
   * The one type with no check at all.
   *
   * <p>A push monitor is written to from outside, so a beat only asks whether one arrived recently
   * enough. A beat that finds one is not recorded — the beat that the caller wrote already was —
   * which is why this returns a marker the loop reads rather than an outcome.
   */
  static final class PushCheck implements Check {
    /** A second of slack, so a caller beating exactly on the interval is not judged late. */
    static final long GRACE_MILLIS = 1000;

    @Override
    public String type() {
      return "push";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      var previous = context.previousBeat();
      if (previous == null) {
        throw new CheckFailed("No heartbeat in the time window");
      }
      long sinceLastBeat = context.nowEpochMillis() - previous.timeEpochMillis();
      Status expected = config.upsideDown() ? Status.DOWN : Status.UP;
      if (previous.statusEnum() != expected
          || sinceLastBeat > config.interval() * 1000L + GRACE_MILLIS) {
        throw new CheckFailed("No heartbeat in the time window");
      }
      // Still inside the window: nothing to record, and the loop re-arms for the remainder.
      return null;
    }

    /** How long until the window this monitor is inside would close. */
    static long remainingMillis(MonitorConfig config, CheckContext context) {
      var previous = context.previousBeat();
      if (previous == null) {
        return config.interval() * 1000L;
      }
      long sinceLastBeat = context.nowEpochMillis() - previous.timeEpochMillis();
      long remaining = config.interval() * 1000L - sinceLastBeat;
      return remaining < 0 ? GRACE_MILLIS : remaining + GRACE_MILLIS;
    }
  }

  /**
   * A container, asked of its daemon.
   *
   * <p>A container that is running but has no health check of its own is counted up, with a message
   * saying so — the daemon knows the process is alive and nothing more, and that is worth saying
   * out loud rather than reporting as a clean pass.
   */
  static final class DockerCheck implements Check {
    @Override
    public String type() {
      return "docker";
    }

    @Override
    public boolean allowCustomStatus() {
      return true;
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      CheckContext.DockerHostConfig host = context.dockerHost();
      if (host == null) {
        throw new CheckFailed("Docker host not found");
      }
      long timeoutMillis = (long) (config.interval() * 1000 * 0.8);
      String path = "/containers/" + config.docker_container() + "/json";
      String body;
      try {
        if ("socket".equals(host.dockerType())) {
          body = UnixSocketHttp.get(host.dockerDaemon(), path, timeoutMillis);
        } else {
          // `tcp://` becomes `http://` and nothing else is supplied — the same rewrite the
          // source applies before handing the address to its own URL parser, which refuses one
          // carrying no scheme rather than guessing at a scheme for it.
          String base = host.dockerDaemon().replace("tcp://", "http://");
          if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new CheckFailed("Invalid URL");
          }
          HttpClient client =
              Http.client(
                  !config.ignoreTls(),
                  null,
                  null,
                  0,
                  Duration.ofMillis(timeoutMillis),
                  null,
                  null,
                  null);
          HttpResponse<String> response =
              client.send(
                  HttpRequest.newBuilder(URI.create(base + path))
                      .timeout(Duration.ofMillis(timeoutMillis))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() >= 400) {
            throw new CheckFailed("Container state is not available");
          }
          body = response.body();
        }
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(TransportErrors.message(e, host.dockerDaemon()));
      }

      Map<String, Object> container;
      try {
        container = JsonQuery.Json.MAPPER.readValue(body, Map.class);
      } catch (Exception e) {
        throw new CheckFailed("Container state is not available");
      }
      Object stateObject = container.get("State");
      if (!(stateObject instanceof Map<?, ?> state)) {
        throw new CheckFailed("Container state is not available");
      }
      String status = String.valueOf(state.get("Status"));
      if ("running".equals(status)) {
        Object healthObject = state.get("Health");
        if (healthObject instanceof Map<?, ?> health) {
          String healthStatus = String.valueOf(health.get("Status"));
          if ("healthy".equals(healthStatus)) {
            return CheckOutcome.up("healthy", null);
          }
          if ("unhealthy".equals(healthStatus)) {
            throw new CheckFailed(
                "Container State is unhealthy according to its healthcheck");
          }
          return CheckOutcome.custom(Status.PENDING, healthStatus, null);
        }
        return CheckOutcome.up(
            "Container has not reported health and is currently running. As it is running, it is "
                + "considered UP. Consider adding a health check for better service visibility",
            null);
      }
      if ("restarting".equals(status)) {
        return CheckOutcome.custom(
            Status.PENDING, "Container is reporting it is currently restarting", null);
      }
      if ("paused".equals(status)) {
        throw new CheckFailed("Container is in a paused state");
      }
      throw new CheckFailed("Container State is " + status);
    }
  }

  /** A game server, looked up in the platform's own directory. */
  static final class SteamCheck implements Check {
    @Override
    public String type() {
      return "steam";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String apiKey = context.setting("steamAPIKey");
      if (apiKey == null || apiKey.isBlank()) {
        throw new CheckFailed("Steam API Key not found");
      }
      String filter =
          "addr\\" + config.hostname() + ":" + (config.port() == null ? "" : config.port());
      String url =
          "https://api.steampowered.com/IGameServersService/GetServerList/v1/?key="
              + Http.urlEncode(apiKey)
              + "&filter="
              + Http.urlEncode(filter)
              + "&limit=1";
      try {
        HttpClient client =
            Http.client(
                !config.ignoreTls(),
                null,
                null,
                config.maxredirects(),
                Duration.ofMillis((long) (config.effectiveTimeout() * 1000)),
                null,
                null,
                null);
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (!AcceptedStatusCodes.matches(response.statusCode(), config.accepted_statuscodes())) {
          throw new CheckFailed("Request failed with status code " + response.statusCode());
        }
        Map<String, Object> body = JsonQuery.Json.MAPPER.readValue(response.body(), Map.class);
        Object outer = body.get("response");
        Object servers = outer instanceof Map<?, ?> map ? ((Map<?, ?>) map).get("servers") : null;
        if (servers instanceof List<?> list && !list.isEmpty()) {
          Object first = list.get(0);
          String name =
              first instanceof Map<?, ?> server ? String.valueOf(server.get("name")) : "";
          return CheckOutcome.up(name, null);
        }
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(TransportErrors.message(e, url));
      }
      throw new CheckFailed("Server not found on Steam");
    }
  }

  /**
   * A game server, asked directly in whichever protocol it speaks.
   *
   * <p>The source delegates this to a library covering several hundred titles. Three query
   * protocols are implemented here — the one most titles use, and the two that are their own — and
   * a title outside them fails saying so rather than reporting a server down. Declared in the
   * README.
   */
  static final class GamedigCheck implements Check {
    @Override
    public String type() {
      return "gamedig";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String game = config.game() == null ? "" : config.game();
      Integer defaultPort = GameProtocols.defaultPortFor(game);
      // The given port wins unless the monitor was told to use whichever the title declares.
      int port =
          config.gamedigGivenPortOnly() || defaultPort == null
              ? (config.port() == null ? 27015 : config.port())
              : defaultPort;
      int timeoutMillis = (int) Math.max(1, config.effectiveTimeout() * 1000);
      long startedAt = System.nanoTime();
      String name;
      try {
        name = GameProtocols.query(game, config.hostname(), port, timeoutMillis);
      } catch (Exception e) {
        throw new CheckFailed(DatabaseChecks.rootMessage(e));
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
      return CheckOutcome.up(name, ping);
    }
  }

  /** A check run from somebody else's network rather than from this one. */
  static final class GlobalpingCheck implements Check {
    @Override
    public String type() {
      return "globalping";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String token = context.setting("globalpingApiToken");
      String subtype = config.subtype() == null ? "ping" : config.subtype();
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("type", subtype);
      request.put(
          "target",
          "http".equals(subtype) ? hostOf(config.url()) : config.hostname());
      request.put("limit", 1);
      if (config.location() != null && !config.location().isBlank()) {
        request.put("locations", List.of(Map.of("magic", config.location())));
      }
      Map<String, Object> options = new LinkedHashMap<>();
      if ("ping".equals(subtype)) {
        options.put("packets", config.ping_count());
      } else if ("dns".equals(subtype)) {
        options.put("query", Map.of("type", config.dns_resolve_type()));
        if (config.dns_resolve_server() != null && !config.dns_resolve_server().isBlank()) {
          options.put("resolver", config.dns_resolve_server().split(",")[0].trim());
        }
        options.put("protocol", config.protocol() == null ? "UDP" : config.protocol());
      } else {
        Map<String, Object> httpOptions = new LinkedHashMap<>();
        httpOptions.put("method", config.method() == null ? "GET" : config.method());
        httpOptions.put("path", pathOf(config.url()));
        options.put("request", httpOptions);
        options.put("protocol", config.protocol() == null ? "HTTPS" : config.protocol());
        if (config.port() != null) {
          options.put("port", config.port());
        }
      }
      if (config.ipFamily() != null) {
        options.put("ipVersion", "ipv6".equals(config.ipFamily()) ? 6 : 4);
      }
      request.put("measurementOptions", options);

      try {
        HttpClient client =
            Http.client(
                true,
                null,
                null,
                0,
                Duration.ofMillis((long) (config.effectiveTimeout() * 1000)),
                null,
                null,
                null);
        HttpRequest.Builder create =
            HttpRequest.newBuilder(URI.create("https://api.globalping.io/v1/measurements"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonQuery.Json.write(request)));
        if (token != null && !token.isBlank()) {
          create.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> created =
            client.send(create.build(), HttpResponse.BodyHandlers.ofString());
        if (created.statusCode() == 429) {
          throw new CheckFailed(tooManyRequests(token != null && !token.isBlank()));
        }
        if (created.statusCode() >= 400) {
          throw new CheckFailed(formatApiError(created.body()));
        }
        Map<String, Object> body = JsonQuery.Json.MAPPER.readValue(created.body(), Map.class);
        String id = String.valueOf(body.get("id"));

        Map<String, Object> measurement = null;
        for (int attempt = 0; attempt < 30; attempt++) {
          Thread.sleep(500);
          HttpResponse<String> polled =
              client.send(
                  HttpRequest.newBuilder(
                          URI.create("https://api.globalping.io/v1/measurements/" + id))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          if (polled.statusCode() >= 400) {
            throw new CheckFailed(formatApiError(polled.body()));
          }
          measurement = JsonQuery.Json.MAPPER.readValue(polled.body(), Map.class);
          if ("finished".equals(measurement.get("status"))) {
            break;
          }
        }
        if (measurement == null) {
          throw new CheckFailed("Measurement did not finish");
        }
        Object resultsObject = measurement.get("results");
        if (!(resultsObject instanceof List<?> results) || results.isEmpty()) {
          throw new CheckFailed("No probe answered");
        }
        Map<?, ?> probeResult = (Map<?, ?>) results.get(0);
        Map<?, ?> probe = (Map<?, ?>) probeResult.get("probe");
        Map<?, ?> result = (Map<?, ?>) probeResult.get("result");
        if ("failed".equals(result.get("status"))) {
          throw new CheckFailed(formatResponse(probe, String.valueOf(result.get("rawOutput"))));
        }
        if ("ping".equals(subtype)) {
          Object stats = result.get("stats");
          Object average = stats instanceof Map<?, ?> map ? map.get("avg") : null;
          double ping = average == null ? 0 : Double.parseDouble(String.valueOf(average));
          return CheckOutcome.up(formatResponse(probe, "OK"), ping);
        }
        if ("dns".equals(subtype)) {
          return CheckOutcome.up(formatResponse(probe, "OK"), null);
        }
        Object timings = result.get("timings");
        Object total = timings instanceof Map<?, ?> map ? map.get("total") : null;
        int statusCode =
            result.get("statusCode") == null
                ? 0
                : Integer.parseInt(String.valueOf(result.get("statusCode")));
        if (!AcceptedStatusCodes.matches(statusCode, config.accepted_statuscodes())) {
          throw new CheckFailed(statusCode + " - " + result.get("statusCodeName"));
        }
        return CheckOutcome.up(
            statusCode + " - " + result.get("statusCodeName"),
            total == null ? 0 : Double.parseDouble(String.valueOf(total)));
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(TransportErrors.message(e, "https://api.globalping.io/v1/measurements"));
      }
    }

    static String formatProbeLocation(Map<?, ?> probe) {
      List<String> parts = new ArrayList<>();
      Object city = probe.get("city");
      Object state = probe.get("state");
      parts.add(state == null ? String.valueOf(city) : city + " (" + state + ")");
      parts.add(String.valueOf(probe.get("country")));
      parts.add(String.valueOf(probe.get("continent")));
      parts.add(probe.get("network") + " (AS" + probe.get("asn") + ")");
      String base = String.join(", ", parts);
      Object tags = probe.get("tags");
      if (tags instanceof List<?> list && !list.isEmpty()) {
        List<String> rendered = new ArrayList<>();
        for (Object tag : list) {
          rendered.add(String.valueOf(tag));
        }
        return base + ", (" + String.join(", ", rendered) + ")";
      }
      return base;
    }

    static String formatResponse(Map<?, ?> probe, String text) {
      return formatProbeLocation(probe) + " : " + text;
    }

    static String formatApiError(String body) {
      try {
        Map<String, Object> parsed = JsonQuery.Json.MAPPER.readValue(body, Map.class);
        Object errorObject = parsed.get("error");
        if (errorObject instanceof Map<?, ?> error) {
          StringBuilder out =
              new StringBuilder(error.get("type") + " " + error.get("message") + ".");
          Object params = error.get("params");
          if (params instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
              out.append("\n").append(entry.getKey()).append(": ").append(entry.getValue());
            }
          }
          return out.toString();
        }
      } catch (Exception ignored) {
        // An unparseable body is handed back as it arrived rather than replaced.
      }
      return body;
    }

    static String tooManyRequests(boolean hasToken) {
      return hasToken
          ? "You have run out of credits. Get higher limits by sponsoring us or hosting probes. "
              + "Learn more at https://dash.globalping.io?view=add-credits."
          : "You have run out of credits. Get higher limits by creating an account. Sign up at "
              + "https://dash.globalping.io?view=add-credits.";
    }

    private static String hostOf(String url) {
      try {
        return URI.create(url).getHost();
      } catch (Exception e) {
        return url;
      }
    }

    private static String pathOf(String url) {
      try {
        String path = URI.create(url).getPath();
        return path == null || path.isEmpty() ? "/" : path;
      } catch (Exception e) {
        return "/";
      }
    }
  }

  /**
   * A page loaded in a real browser.
   *
   * <p>Driven over the browser's own debugging protocol against a remote instance, because that is
   * what the source's configuration names — a monitor of this type carries the address of a
   * browser, not a path to one.
   */
  static final class RealBrowserCheck implements Check {
    @Override
    public String type() {
      return "real-browser";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String scheme;
      try {
        scheme = URI.create(config.url()).getScheme();
      } catch (Exception e) {
        throw new CheckFailed("Invalid url protocol, only http and https are allowed.");
      }
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        throw new CheckFailed("Invalid url protocol, only http and https are allowed.");
      }
      if (context.remoteBrowserUrl() == null || context.remoteBrowserUrl().isBlank()) {
        throw new CheckFailed("No remote browser configured for this monitor");
      }
      long timeoutMillis = (long) (config.interval() * 1000 * 0.8);
      try {
        Cdp.Result result =
            Cdp.loadAndScreenshot(
                context.remoteBrowserUrl(),
                config.url(),
                timeoutMillis,
                config.screenshot_delay());
        if (result.status() >= 200 && result.status() < 400) {
          return CheckOutcome.up(String.valueOf(result.status()), result.responseEndMillis());
        }
        throw new CheckFailed(String.valueOf(result.status()));
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(TransportErrors.message(e, context.remoteBrowserUrl()));
      }
    }
  }
}
