package io.akka.uptimekuma.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One open stream, read the way a browser reads it — a frame at a time, with the connection left
 * open. Mirrors {@code pocketbase-akka}'s helper of the same name: the TestKit's own HTTP client
 * reads a whole response before returning it, which a stream that never ends does not give it.
 */
final class SseSession implements AutoCloseable {

  record Frame(String id, String data) {}

  private final HttpClient client = HttpClient.newHttpClient();
  private final List<Frame> frames = new CopyOnWriteArrayList<>();
  private final Thread reader;
  private volatile HttpResponse<java.io.InputStream> response;
  private volatile boolean stopped;

  SseSession(int port, String path) {
    this(port, path, null);
  }

  SseSession(int port, String path, String lastEventId) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .GET();
    if (lastEventId != null) {
      builder.header("Last-Event-ID", lastEventId);
    }
    var request = builder.build();

    reader =
        new Thread(
            () -> {
              try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var in =
                    new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                  var current = new ArrayList<String>();
                  String line;
                  while (!stopped && (line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                      if (!current.isEmpty()) {
                        frames.add(parse(current));
                        current = new ArrayList<>();
                      }
                    } else {
                      current.add(line);
                    }
                  }
                }
              } catch (Exception ignored) {
                // the stream ends when the test closes it, and that is not a failure
              }
            });
    reader.setDaemon(true);
    reader.start();
  }

  private static Frame parse(List<String> lines) {
    String id = null;
    var data = new StringBuilder();
    for (var line : lines) {
      if (line.startsWith("id:")) {
        id = line.substring(3).trim();
      } else if (line.startsWith("data:")) {
        data.append(line.substring(5).trim());
      }
    }
    return new Frame(id, data.toString());
  }

  List<Frame> awaitFrames(int n, Duration timeout) {
    var deadline = System.nanoTime() + timeout.toNanos();
    while (frames.size() < n && System.nanoTime() < deadline) {
      sleep(20);
    }
    return List.copyOf(frames);
  }

  @Override
  public void close() {
    stopped = true;
    var current = response;
    if (current != null) {
      try {
        current.body().close();
      } catch (Exception ignored) {
        // already gone
      }
    }
    reader.interrupt();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
