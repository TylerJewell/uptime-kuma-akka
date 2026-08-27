package io.akka.uptimekuma.checks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The three "does this work" calls the settings screens make.
 *
 * <p>They are not monitor checks — nothing is recorded and no beat follows — but they reach the
 * same daemons and browsers a check would, so they live beside the checks rather than beside the
 * screens that call them.
 */
public final class Probes {

  private Probes() {}

  /** Every container a daemon knows about, so the settings screen can say how many there are. */
  public static String dockerContainers(String daemon, boolean overSocket) throws Exception {
    if (overSocket) {
      return UnixSocketHttp.get(daemon, "/containers/json?all=1", 15000);
    }
    // The source rewrites `tcp://` to `http://` and does nothing else — so a daemon address with
    // no scheme at all reaches its URL parser unchanged and is refused. Supplying a scheme here
    // would be an improvement: it would connect where the source refuses.
    String base = daemon.replace("tcp://", "http://");
    if (!base.startsWith("http://") && !base.startsWith("https://")) {
      throw new java.net.MalformedURLException("Invalid URL");
    }
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/containers/json?all=1")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new java.io.IOException("daemon answered " + response.statusCode());
    }
    return response.body();
  }

  /** Whether a remote browser is answering, and which build it is. */
  public static String remoteBrowserVersion(String browserUrl) throws Exception {
    if (browserUrl.startsWith("ws://") || browserUrl.startsWith("wss://")) {
      // A socket address cannot be asked for its version without opening the socket, and opening
      // it is itself the answer to whether the browser is there.
      java.net.http.WebSocket socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(15))
              .buildAsync(URI.create(browserUrl), new java.net.http.WebSocket.Listener() {})
              .get(15, java.util.concurrent.TimeUnit.SECONDS);
      socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "probe");
      return "connected";
    }
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(browserUrl.replaceAll("/+$", "") + "/json/version"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new java.io.IOException("browser answered " + response.statusCode());
    }
    return response.body();
  }

  /** Which build of the browser a path points at. */
  public static String chromeVersion(String executable) throws Exception {
    String command = executable == null || executable.isBlank() ? "chromium" : executable;
    String output = SimpleChecks.Shell.run(List.of(command, "--version"), 15000);
    if (output.isBlank()) {
      throw new java.io.IOException("no version reported by " + command);
    }
    return output.trim();
  }
}
