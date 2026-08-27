package io.akka.uptimekuma.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.uptimekuma.application.RecordFields;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.application.UserEntity;
import io.akka.uptimekuma.domain.Passwords;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two things an operator does when they cannot sign in.
 *
 * <p>The source's equivalents open the database file directly with the server stopped, which is
 * what makes them work for somebody who has lost their password. This rebuild has no file to open,
 * so they reach the running service instead and are authorised by a secret the operator has by
 * virtue of running it: {@code UPTIME_KUMA_CLI_SECRET} in the service's own environment. A service
 * started without one refuses both, rather than leaving them open.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/cli")
public class CliEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public CliEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record ResetPassword(String secret, String newPassword, boolean dryRun) {}

  public record Remove2fa(String secret) {}

  @Post("/reset-password")
  public HttpResponse resetPassword(ResetPassword request) {
    HttpResponse refusal = check(request.secret());
    if (refusal != null) {
      return refusal;
    }
    StoredRecord user = Sessions.firstUser(componentClient);
    if (user == null) {
      return answer(false, "user not found, have you installed?", null);
    }
    if (Passwords.tooWeak(request.newPassword())) {
      return answer(false, "Password is too weak, please use a stronger password.", user.str("username"));
    }
    if (request.dryRun()) {
      return answer(true, "Dry run mode, no changes will be made.", user.str("username"));
    }
    componentClient
        .forKeyValueEntity(user.id())
        .method(UserEntity::patch)
        .invoke(RecordFields.of(Map.of("password", Passwords.generate(request.newPassword()))));
    // The signing secret is regenerated too, so every session minted with the old password stops
    // verifying — which is what a reset is for.
    Settings.write(componentClient, Map.of("jwtSecret", ""));
    return answer(true, "Password reset successfully.", user.str("username"));
  }

  @Post("/remove-2fa")
  public HttpResponse remove2fa(Remove2fa request) {
    HttpResponse refusal = check(request.secret());
    if (refusal != null) {
      return refusal;
    }
    StoredRecord user = Sessions.firstUser(componentClient);
    if (user == null) {
      return answer(false, "user not found, have you installed?", null);
    }
    componentClient
        .forKeyValueEntity(user.id())
        .method(UserEntity::patch)
        .invoke(RecordFields.of(Map.of("twofa_status", false, "twofa_secret", null)));
    return answer(true, "2FA has been removed successfully.", user.str("username"));
  }

  private HttpResponse check(String secret) {
    String expected = System.getenv("UPTIME_KUMA_CLI_SECRET");
    if (expected == null || expected.isBlank()) {
      return answer(
          false,
          "This server was started without UPTIME_KUMA_CLI_SECRET, so the recovery commands are "
              + "not available.",
          null);
    }
    // Compared in constant time: this route is reachable over the network, where an ordinary
    // string comparison leaks the length of the matching prefix to anybody who can time it.
    if (secret == null
        || !java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      return answer(false, "Wrong secret.", null);
    }
    return null;
  }

  private static HttpResponse answer(boolean ok, String message, String username) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", ok);
    body.put("msg", message);
    body.put("username", username);
    return HttpResponses.ok(body);
  }
}
