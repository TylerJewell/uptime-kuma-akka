package io.akka.uptimekuma.checks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Driving a remote browser over its debugging protocol.
 *
 * <p>Enough of it to open a page, wait for the network to go quiet, take a picture and read the
 * status the main document came back with. The protocol is a request-and-reply conversation over
 * one socket where every message carries an identifier, so the reply to a command is matched by
 * that identifier rather than by order.
 */
final class Cdp {

  private Cdp() {}

  /**
   * @param responseEndMillis how long the main document took, which is the number a monitor of this
   *     type records as its response time
   */
  record Result(int status, double responseEndMillis, String screenshotBase64) {}

  static Result loadAndScreenshot(
      String browserUrl, String pageUrl, long timeoutMillis, int screenshotDelayMillis)
      throws Exception {

    String debuggerUrl = resolveDebuggerUrl(browserUrl, timeoutMillis);
    AtomicInteger nextId = new AtomicInteger(1);
    Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    CompletableFuture<Map<String, Object>> mainResponse = new CompletableFuture<>();
    StringBuilder partial = new StringBuilder();

    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMillis))
            .buildAsync(
                URI.create(debuggerUrl),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    webSocket.request(1);
                    if (!last) {
                      return null;
                    }
                    String whole = partial.toString();
                    partial.setLength(0);
                    try {
                      Map<String, Object> message =
                          JsonQuery.Json.MAPPER.readValue(whole, Map.class);
                      Object id = message.get("id");
                      if (id != null) {
                        CompletableFuture<Map<String, Object>> waiting =
                            pending.remove(((Number) id).intValue());
                        if (waiting != null) {
                          waiting.complete(message);
                        }
                      } else if ("Network.responseReceived".equals(message.get("method"))) {
                        Map<?, ?> params = (Map<?, ?>) message.get("params");
                        if ("Document".equals(params.get("type")) && !mainResponse.isDone()) {
                          mainResponse.complete((Map<String, Object>) params.get("response"));
                        }
                      }
                    } catch (Exception ignored) {
                      // A frame that will not parse is not a reply to anything, so nothing is
                      // waiting on it.
                    }
                    return null;
                  }
                })
            .get(timeoutMillis, TimeUnit.MILLISECONDS);

    try {
      long startedAt = System.currentTimeMillis();
      call(socket, nextId, pending, "Network.enable", Map.of(), timeoutMillis);
      call(socket, nextId, pending, "Page.enable", Map.of(), timeoutMillis);
      call(socket, nextId, pending, "Page.navigate", Map.of("url", pageUrl), timeoutMillis);

      Map<String, Object> response =
          mainResponse.get(timeoutMillis, TimeUnit.MILLISECONDS);
      double elapsed = System.currentTimeMillis() - startedAt;
      int status = ((Number) response.get("status")).intValue();

      if (screenshotDelayMillis > 0) {
        Thread.sleep(screenshotDelayMillis);
      }
      Map<String, Object> shot =
          call(
              socket,
              nextId,
              pending,
              "Page.captureScreenshot",
              Map.of("format", "png"),
              timeoutMillis);
      Object result = shot.get("result");
      String data =
          result instanceof Map<?, ?> map ? String.valueOf(map.get("data")) : null;
      return new Result(status, elapsed, data);
    } finally {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
  }

  private static Map<String, Object> call(
      WebSocket socket,
      AtomicInteger nextId,
      Map<Integer, CompletableFuture<Map<String, Object>>> pending,
      String method,
      Map<String, Object> params,
      long timeoutMillis)
      throws Exception {
    int id = nextId.getAndIncrement();
    CompletableFuture<Map<String, Object>> waiting = new CompletableFuture<>();
    pending.put(id, waiting);
    socket
        .sendText(
            JsonQuery.Json.write(Map.of("id", id, "method", method, "params", params)), true)
        .get(timeoutMillis, TimeUnit.MILLISECONDS);
    return waiting.get(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Turn the address a monitor was given into the socket a page can be driven over.
   *
   * <p>A remote browser is configured with either the socket address itself or the HTTP address of
   * the debugging endpoint, and the second has to be asked for the first.
   */
  private static String resolveDebuggerUrl(String browserUrl, long timeoutMillis) throws Exception {
    if (browserUrl.startsWith("ws://") || browserUrl.startsWith("wss://")) {
      return browserUrl;
    }
    HttpClient client =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(timeoutMillis)).build();
    HttpResponse<String> version =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(browserUrl.replaceAll("/+$", "") + "/json/version"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Map<String, Object> body = JsonQuery.Json.MAPPER.readValue(version.body(), Map.class);
    Object endpoint = body.get("webSocketDebuggerUrl");
    if (endpoint == null) {
      throw new java.io.IOException("browser at " + browserUrl + " did not report a debugger URL");
    }
    return String.valueOf(endpoint);
  }
}
