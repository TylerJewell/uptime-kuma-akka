package io.akka.uptimekuma.application;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The one check this port carries: a GET whose acceptance is a 2xx status.
 *
 * <p>Everything a check can raise is turned into a failed outcome rather than allowed out. A beat
 * that throws is retried by the runtime on a backoff of its own (SPEC-001 §4 OD1), and a target
 * that is simply refusing connections is not a fault in the beat — it is the answer.
 */
public final class HttpProbe {

  // One client for the process. A timed action is constructed per delivery, so a client
  // built in its constructor would be a fresh connection pool and selector thread on
  // every beat of every monitor, none of them closed.
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private HttpProbe() {}

  public static CheckOutcome check(MonitorConfig config) {
    long started = System.nanoTime();
    try {
      var request =
          HttpRequest.newBuilder(URI.create(config.url()))
              .timeout(Duration.ofSeconds(Math.max(1, config.intervalSeconds() * 4 / 5)))
              .GET()
              .build();
      HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
      long pingMillis = (System.nanoTime() - started) / 1_000_000;
      int status = response.statusCode();
      if (status >= 200 && status <= 299) {
        return CheckOutcome.up(status + " - OK", pingMillis);
      }
      return CheckOutcome.failed("Request failed with status code " + status);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CheckOutcome.failed("interrupted");
    } catch (Exception e) {
      return CheckOutcome.failed(e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }
}
