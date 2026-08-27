package io.akka.uptimekuma.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The session token.
 *
 * <p>HS256 over two claims and nothing else: the username, and {@code h}, a digest of the stored
 * password hash. There is no expiry — a token is good until the password changes, at which point
 * {@code h} no longer matches and every token issued before it stops verifying. That is the whole
 * of the revocation mechanism and it is reproduced rather than improved, because a token minted
 * here has to be readable by the interface the source ships.
 */
public final class Jwt {

  private Jwt() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

  /** The digest length the source's SHAKE256 call uses, in bytes. */
  public static final int SHAKE256_LENGTH = 16;

  public static String create(String username, String passwordHash, String secret) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("username", username);
    payload.put("h", passwordDigest(passwordHash));
    return sign(payload, secret);
  }

  public static String sign(JsonNode payload, String secret) {
    String header =
        URL.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String body;
    try {
      body = URL.encodeToString(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialise jwt payload", e);
    }
    String signingInput = header + "." + body;
    return signingInput + "." + URL.encodeToString(hmac(signingInput, secret));
  }

  /**
   * Verify and read a token.
   *
   * @return the payload
   * @throws IllegalArgumentException when the token is malformed or the signature does not match
   */
  public static JsonNode verify(String token, String secret) {
    if (token == null) {
      throw new IllegalArgumentException("jwt malformed");
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("jwt malformed");
    }
    byte[] expected = hmac(parts[0] + "." + parts[1], secret);
    byte[] actual;
    try {
      actual = URL_DECODER.decode(parts[2]);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid signature");
    }
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new IllegalArgumentException("invalid signature");
    }
    try {
      return MAPPER.readTree(URL_DECODER.decode(parts[1]));
    } catch (Exception e) {
      throw new IllegalArgumentException("jwt malformed");
    }
  }

  /**
   * The {@code h} claim: a SHAKE256 digest of the password hash, sixteen bytes as hex.
   *
   * <p>SHAKE256 is not in the JDK's provider set, so the digest is computed here from Keccak's
   * sponge. It has to be the same function the source uses, because the claim is compared against
   * what the source would have written for the same user.
   */
  public static String passwordDigest(String passwordHash) {
    return HexFormat.of()
        .formatHex(Shake256.digest(passwordHash.getBytes(StandardCharsets.UTF_8), SHAKE256_LENGTH));
  }

  private static byte[] hmac(String data, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("cannot sign", e);
    }
  }
}
