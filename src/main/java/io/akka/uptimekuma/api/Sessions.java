package io.akka.uptimekuma.api;

import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.application.UserEntity;
import io.akka.uptimekuma.application.Ids;
import io.akka.uptimekuma.application.UserListView;
import io.akka.uptimekuma.domain.Jwt;
import io.akka.uptimekuma.domain.Passwords;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Who a caller is.
 *
 * <p>A token carries a username and a digest of the stored password hash, and nothing else — no
 * expiry. Changing a password changes the digest, which is the whole of how a token is revoked, and
 * it is reproduced rather than improved because a token minted here is read by the interface the
 * source ships.
 */
public final class Sessions {

  private Sessions() {}

  /** The identifier the signing secret is stored under. */
  private static final String SECRET_KEY = "jwtSecret";

  public record Signed(String username, String userId) {}

  /** The secret this server signs with, generated on first use and kept from then on. */
  public static synchronized String secret(ComponentClient componentClient) {
    Map<String, Object> settings = Settings.read(componentClient);
    Object existing = settings.get(SECRET_KEY);
    if (existing != null && !String.valueOf(existing).isBlank()) {
      return String.valueOf(existing);
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String generated = Passwords.generate(Base64.getEncoder().encodeToString(random));
    Settings.write(componentClient, Map.of(SECRET_KEY, generated));
    return generated;
  }

  public static String create(ComponentClient componentClient, String username, String passwordHash) {
    return Jwt.create(username, passwordHash, secret(componentClient));
  }

  /**
   * Read a token and check it still stands.
   *
   * @return the account it names, or null when the token is unreadable, the account is gone or
   *     inactive, or the password has changed since it was minted
   */
  public static Signed verify(ComponentClient componentClient, String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    JsonNode payload;
    try {
      payload = Jwt.verify(token, secret(componentClient));
    } catch (Exception e) {
      return null;
    }
    String username = payload.path("username").asText(null);
    if (username == null) {
      return null;
    }
    StoredRecord user = byUsername(componentClient, username);
    if (user == null || !user.flag("active")) {
      return null;
    }
    String stored = user.str("password");
    if (stored == null || !Jwt.passwordDigest(stored).equals(payload.path("h").asText())) {
      return null;
    }
    return new Signed(username, user.id());
  }

  /**
   * The account with this name.
   *
   * <p>An account is stored under its own username, so this is one read rather than a scan of a
   * list. It also removes a race: a list is built from the write and lags it, so an account looked
   * up immediately after it was created would not be found.
   */
  public static StoredRecord byUsername(ComponentClient componentClient, String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    StoredRecord record =
        componentClient.forKeyValueEntity(username.trim()).method(UserEntity::get).invoke();
    return record.exists() ? record : null;
  }

  /** Whether anybody has an account yet, which is what the first-run screen asks. */
  /**
   * Whether this server still has no account.
   *
   * <p>Read from a counter rather than from the account list, because the list is a read side and a
   * read side lags the write that fed it. Asked immediately after the first account is made, the
   * list still answers "empty" — so setup would accept a second caller, and the whole point of the
   * question is that it is asked at the moment an account is being created. R101.
   */
  public static boolean needsSetup(ComponentClient componentClient) {
    return Ids.current(componentClient, "user") == 0;
  }

  /** The first account, which is the one the command-line tools operate on. */
  public static StoredRecord firstUser(ComponentClient componentClient) {
    var rows = componentClient.forView().method(UserListView::all).invoke();
    if (rows.entries().isEmpty()) {
      return null;
    }
    return componentClient
        .forKeyValueEntity(rows.entries().get(0).id())
        .method(UserEntity::get)
        .invoke();
  }
}
