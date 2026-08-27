package io.akka.uptimekuma.checks;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.uptimekuma.domain.AcceptedStatusCodes;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The three checks that fetch a URL: {@code http}, {@code keyword} and {@code json-query}.
 *
 * <p>One request serves all three — they differ only in what they do with the body afterwards, and
 * the source writes them as one branch for the same reason. The response time recorded is measured
 * around the request and nothing else, which is why the header assembly above it is done before the
 * clock starts.
 */
public final class HttpFamilyCheck implements Check {

  private final String type;

  public HttpFamilyCheck(String type) {
    this.type = type;
  }

  @Override
  public String type() {
    return type;
  }

  @Override
  public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
    double timeoutSeconds = config.effectiveTimeout();

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", Http.ACCEPT);

    String body = null;
    String contentType = null;
    if (config.body() != null && !config.body().isBlank()) {
      String encoding = config.httpBodyEncoding() == null ? "json" : config.httpBodyEncoding();
      switch (encoding) {
        case "json" -> {
          try {
            Http.MAPPER.readTree(config.body());
          } catch (Exception e) {
            throw new CheckFailed("Your JSON body is invalid. " + e.getMessage());
          }
          body = config.body();
          contentType = "application/json";
        }
        case "form" -> {
          body = config.body();
          contentType = "application/x-www-form-urlencoded";
        }
        case "xml" -> {
          body = config.body();
          contentType = "text/xml; charset=utf-8";
        }
        default -> {
          body = config.body();
        }
      }
    }
    if (contentType != null) {
      headers.put("Content-Type", contentType);
    }

    HttpClient client =
        Http.client(
            !config.ignoreTls(),
            config.ipFamily(),
            context.proxy(),
            config.maxredirects(),
            Duration.ofMillis((long) (timeoutSeconds * 1000)),
            "mtls".equals(config.authMethod()) ? config.tlsCert() : null,
            "mtls".equals(config.authMethod()) ? config.tlsKey() : null,
            "mtls".equals(config.authMethod()) ? config.tlsCa() : null);

    if ("basic".equals(config.authMethod())) {
      headers.put("Authorization", Http.basicAuth(config.basic_auth_user(), config.basic_auth_pass()));
    } else if ("bearer".equals(config.authMethod())) {
      headers.put("Authorization", "Bearer " + config.bearer_token());
    } else if ("oauth2-cc".equals(config.authMethod())) {
      try {
        JsonNode token =
            Http.oauthClientCredentials(
                client,
                config.oauth_token_url(),
                config.oauth_client_id(),
                config.oauth_client_secret(),
                config.oauth_scopes(),
                config.oauth_audience(),
                config.oauth_auth_method());
        headers.put(
            "Authorization",
            token.path("token_type").asText("Bearer") + " " + token.path("access_token").asText());
      } catch (Exception e) {
        throw new CheckFailed("The oauth config is invalid. " + e.getMessage());
      }
    }

    if (config.headers() != null && !config.headers().isBlank()) {
      try {
        JsonNode extra = Http.MAPPER.readTree(config.headers());
        Iterator<Map.Entry<String, JsonNode>> fields = extra.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          headers.put(field.getKey(), field.getValue().asText());
        }
      } catch (Exception e) {
        throw new CheckFailed("Your headers are invalid. " + e.getMessage());
      }
    }

    String url = config.url();
    if (config.cacheBust()) {
      // A fresh value each beat, so an intermediate cache cannot answer the check with what it
      // answered last time. The source builds it from a random float's fractional digits.
      url =
          Http.withParam(
              url,
              "uptime_kuma_cachebuster",
              Double.toString(ThreadLocalRandom.current().nextDouble()).substring(2));
    }

    HttpRequest.Builder request;
    try {
      request = HttpRequest.newBuilder(URI.create(url));
    } catch (Exception e) {
      throw new CheckFailed("Invalid URL: " + config.url());
    }
    headers.forEach(request::header);
    String method = config.method() == null ? "GET" : config.method().toUpperCase();
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    // Ten seconds beyond the configured timeout, which is the source's second, harder deadline:
    // the first one bounds the wait for a response and this one bounds the whole exchange.
    request.timeout(Duration.ofMillis((long) ((timeoutSeconds + 10) * 1000)));

    long startedAt = System.currentTimeMillis();
    HttpResponse<String> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new CheckFailed("timeout by AbortSignal (" + trimDouble(timeoutSeconds) + "s)");
    } catch (Exception e) {
      throw new CheckFailed(TransportErrors.message(e, url, client));
    }
    double ping = System.currentTimeMillis() - startedAt;

    String statusLine = response.statusCode() + " - " + StatusText.of(response.statusCode());
    if (!AcceptedStatusCodes.matches(response.statusCode(), config.accepted_statuscodes())) {
      throw new CheckFailed(
          "Request failed with status code " + response.statusCode(),
          ping,
          config.saveErrorResponse() ? truncate(response.body(), config.responseMaxLength()) : null);
    }

    String saved =
        config.saveResponse() && config.saveErrorResponse()
            ? truncate(response.body(), config.responseMaxLength())
            : null;

    return switch (type) {
      case "http" -> CheckOutcome.up(statusLine, ping, saved);
      case "keyword" -> keyword(config, statusLine, response.body(), ping, saved);
      case "json-query" -> jsonQuery(config, response.body(), ping, saved);
      default -> throw new CheckFailed("Unknown Monitor Type");
    };
  }

  private CheckOutcome keyword(
      MonitorConfig config, String statusLine, String data, double ping, String saved)
      throws CheckFailed {
    String haystack = data == null ? "" : data;
    boolean found = config.keyword() != null && haystack.contains(config.keyword());
    if (found == !config.invertKeyword()) {
      return CheckOutcome.up(
          statusLine + ", keyword " + (found ? "is" : "not") + " found", ping, saved);
    }
    String flattened = haystack.replaceAll("<[^>]*>?|[\\n\\r]|\\s+", " ").trim();
    if (flattened.length() > 50) {
      flattened = flattened.substring(0, 47) + "...";
    }
    throw new CheckFailed(
        statusLine
            + ", but keyword is "
            + (found ? "present" : "not")
            + " in ["
            + flattened
            + "]",
        ping,
        saved);
  }

  private CheckOutcome jsonQuery(MonitorConfig config, String data, double ping, String saved)
      throws CheckFailed {
    JsonQuery.Result result =
        JsonQuery.evaluate(data, config.jsonPath(), config.jsonPathOperator(), config.expectedValue());
    if (result.status()) {
      return CheckOutcome.up(
          "JSON query passes (comparing "
              + result.response()
              + " "
              + config.jsonPathOperator()
              + " "
              + config.expectedValue()
              + ")",
          ping,
          saved);
    }
    throw new CheckFailed(
        "JSON query does not pass (comparing "
            + result.response()
            + " "
            + config.jsonPathOperator()
            + " "
            + config.expectedValue()
            + ")",
        ping,
        saved);
  }

  static String truncate(String body, int maxLength) {
    if (body == null) {
      return null;
    }
    if (body.length() <= maxLength) {
      return body;
    }
    return body.substring(0, maxLength) + "... (truncated)";
  }

  /** A transport failure's message, written in the source's vocabulary. See {@link TransportErrors}. */
  static String rootMessage(Throwable error) {
    return TransportErrors.message(error, null);
  }

  /** Print a whole-number timeout without a trailing decimal, the way the source's does. */
  static String trimDouble(double value) {
    return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
  }
}
