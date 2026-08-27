package io.akka.uptimekuma.notifications;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Where a composed notification actually goes.
 *
 * <p>Three transports, because the targets need three: an ordinary request, a multipart form, and a
 * mail server. A request whose method says {@code SMTP} is delivered by the mail path and
 * everything else over HTTP.
 */
public final class HttpSender implements Sender {

  private final HttpClient client;
  private final Duration timeout;

  public HttpSender(Duration timeout) {
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
  }

  public HttpSender() {
    this(Duration.ofSeconds(30));
  }

  @Override
  public Response send(Request request) throws Exception {
    if ("SMTP".equals(request.method())) {
      return deliverMail(request);
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(timeout);
    request.headers().forEach(builder::header);

    if (request.formFields() != null) {
      String boundary = "uptime-kuma-" + Long.toHexString(System.nanoTime());
      StringBuilder body = new StringBuilder();
      for (Map.Entry<String, String> field : request.formFields().entrySet()) {
        body.append("--").append(boundary).append("\r\n");
        body.append("Content-Disposition: form-data; name=\"")
            .append(field.getKey())
            .append("\"\r\n\r\n");
        body.append(field.getValue()).append("\r\n");
      }
      body.append("--").append(boundary).append("--\r\n");
      builder.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
      builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    } else if (request.body() == null) {
      builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
    }

    HttpResponse<String> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return new Response(response.statusCode(), null, response.body());
  }

  /**
   * Hand a message to a mail server.
   *
   * <p>The body composed by the target carries the whole envelope, because a mail server is not an
   * HTTP endpoint and there is nowhere else to put it.
   */
  private Response deliverMail(Request request) throws Exception {
    Map<String, Object> mail = Json.MAPPER.readValue(request.body(), Map.class);
    String host = String.valueOf(mail.get("host"));
    int port = (int) Double.parseDouble(String.valueOf(mail.get("port")));
    boolean secure = Boolean.TRUE.equals(mail.get("secure"));
    boolean ignoreStartTls = Boolean.TRUE.equals(mail.get("ignoreTLS"));
    boolean rejectUnauthorized = !Boolean.FALSE.equals(mail.get("rejectUnauthorized"));
    String security = secure ? "secure" : ignoreStartTls ? "nostarttls" : "starttls";

    List<String> recipients = new java.util.ArrayList<>();
    addAddresses(recipients, mail.get("to"));
    addAddresses(recipients, mail.get("cc"));
    addAddresses(recipients, mail.get("bcc"));
    if (recipients.isEmpty()) {
      throw new IOException("no recipient for the mail notification");
    }

    boolean html = mail.containsKey("html");
    String content = String.valueOf(mail.get(html ? "html" : "text"));
    StringBuilder message = new StringBuilder();
    message.append("From: ").append(mail.get("from")).append("\r\n");
    message.append("To: ").append(mail.get("to")).append("\r\n");
    if (mail.get("cc") != null) {
      message.append("Cc: ").append(mail.get("cc")).append("\r\n");
    }
    message.append("Subject: ").append(mail.get("subject")).append("\r\n");
    message.append("MIME-Version: 1.0\r\n");
    message
        .append("Content-Type: ")
        .append(html ? "text/html" : "text/plain")
        .append("; charset=UTF-8\r\n");
    Object extra = mail.get("headers");
    if (extra instanceof Map<?, ?> map) {
      map.forEach((key, value) -> message.append(key).append(": ").append(value).append("\r\n"));
    }
    message.append("\r\n").append(content);

    try (var session =
        io.akka.uptimekuma.checks.SmtpDelivery.open(
            host, port, (int) timeout.toMillis(), security, !rejectUnauthorized)) {
      session.login(
          mail.get("username") == null ? null : String.valueOf(mail.get("username")),
          mail.get("password") == null ? null : String.valueOf(mail.get("password")));
      session.deliver(String.valueOf(mail.get("from")), recipients, message.toString());
    }
    return new Response(250, "OK", "");
  }

  private static void addAddresses(List<String> into, Object value) {
    if (value == null) {
      return;
    }
    for (String address : String.valueOf(value).split(",")) {
      String trimmed = address.trim();
      if (!trimmed.isEmpty()) {
        into.add(trimmed);
      }
    }
  }
}
