package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

/**
 * One unary call to a service, judged by a word in the reply.
 *
 * <p>The schema arrives as text on the monitor rather than compiled in, so it is parsed at beat
 * time and the caller's JSON body is encoded against it. The reply is decoded the same way and the
 * keyword is searched for in the result.
 *
 * <p>The framing is the protocol's own: one byte saying the message is not compressed, four bytes
 * of length, then the message. The status a call ends with is carried in trailers, which the
 * platform's HTTP client does not expose — so a call that returns no message at all is reported as
 * a failure carrying whatever the server put in a header, rather than the numeric status. Declared
 * in the README.
 */
public final class GrpcCheck implements Check {

  @Override
  public String type() {
    return "grpc-keyword";
  }

  @Override
  public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
    if (config.grpcProtobuf() == null || config.grpcProtobuf().isBlank()) {
      throw new CheckFailed("No protobuf definition supplied");
    }
    Proto proto = Proto.parse(config.grpcProtobuf());
    String requestType =
        Proto.requestType(config.grpcProtobuf(), config.grpcServiceName(), config.grpcMethod());
    String replyType =
        Proto.replyType(config.grpcProtobuf(), config.grpcServiceName(), config.grpcMethod());
    if (requestType == null || replyType == null) {
      throw new CheckFailed(
          "The protobuf definition has no method " + config.grpcMethod() + " to call");
    }

    Map<String, Object> body;
    try {
      String raw = config.grpcBody() == null || config.grpcBody().isBlank() ? "{}" : config.grpcBody();
      body = JsonQuery.Json.MAPPER.readValue(raw, Map.class);
    } catch (Exception e) {
      throw new CheckFailed("The gRPC body is not valid JSON. " + e.getMessage());
    }

    byte[] message = proto.encode(requestType, body);
    ByteBuffer framed = ByteBuffer.allocate(5 + message.length);
    framed.put((byte) 0);
    framed.putInt(message.length);
    framed.put(message);

    String base = config.grpcUrl();
    if (!base.startsWith("http")) {
      base = (config.grpcEnableTls() ? "https://" : "http://") + base;
    }
    // The path is the fully qualified service and the method, which is how the protocol addresses
    // a call.
    String url =
        base.replaceAll("/+$", "")
            + "/"
            + config.grpcServiceName()
            + "/"
            + config.grpcMethod();

    long startedAt = System.nanoTime();
    HttpResponse<byte[]> response;
    try {
      HttpClient client =
          Http.client(
              !config.ignoreTls(),
              null,
              null,
              0,
              Duration.ofMillis((long) (config.effectiveTimeout() * 1000)),
              null,
              null,
              null);
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url))
              .version(HttpClient.Version.HTTP_2)
              .header("content-type", "application/grpc+proto")
              .header("te", "trailers")
              .timeout(Duration.ofMillis((long) ((config.effectiveTimeout() + 10) * 1000)))
              .POST(HttpRequest.BodyPublishers.ofByteArray(framed.array()));
      if (config.grpcMetadata() != null && !config.grpcMetadata().isBlank()) {
        try {
          Map<String, Object> metadata =
              JsonQuery.Json.MAPPER.readValue(config.grpcMetadata(), Map.class);
          metadata.forEach((key, value) -> request.header(key, String.valueOf(value)));
        } catch (Exception ignored) {
          // Metadata that will not parse is skipped rather than failing the call.
        }
      }
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (Exception e) {
      throw new CheckFailed(DatabaseChecks.rootMessage(e));
    }
    double ping = (System.nanoTime() - startedAt) / 1_000_000.0;

    byte[] payload = response.body();
    if (payload == null || payload.length < 5) {
      String status =
          response.headers().firstValue("grpc-status").orElse(null);
      String reason = response.headers().firstValue("grpc-message").orElse(null);
      // Status one is the caller cancelling, which the source treats as a healthy answer.
      if ("1".equals(status)) {
        return CheckOutcome.up("", ping);
      }
      throw new CheckFailed(
          reason != null ? reason : "The call returned no message", ping, null);
    }

    ByteBuffer reply = ByteBuffer.wrap(payload);
    reply.get();
    int length = reply.getInt();
    byte[] messageBytes = new byte[Math.min(length, reply.remaining())];
    reply.get(messageBytes);
    Map<String, Object> decoded = proto.decode(replyType, messageBytes);
    String rendered = JsonQuery.Json.write(decoded);

    boolean found = config.keyword() != null && rendered.contains(config.keyword());
    if (found == !config.invertKeyword()) {
      return CheckOutcome.up(
          rendered + ", keyword [" + config.keyword() + "] " + (found ? "is" : "not") + " found",
          ping);
    }
    String truncated = rendered.length() > 50 ? rendered.substring(0, 47) + "..." : rendered;
    throw new CheckFailed(
        truncated
            + ", but keyword ["
            + config.keyword()
            + "] is "
            + (found ? "present" : "not")
            + " in [" + truncated + "]",
        ping,
        null);
  }
}
