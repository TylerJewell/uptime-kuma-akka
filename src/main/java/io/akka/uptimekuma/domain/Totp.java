package io.akka.uptimekuma.domain;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, for the second factor.
 *
 * <p>Six digits over thirty-second steps with a window of one, which is the source's configuration
 * — a code from the previous or the next step is accepted, so a clock a few seconds out still
 * works. The secret is base32 with its padding stripped, because that is what an authenticator app
 * reads out of the {@code otpauth://} URI.
 */
public final class Totp {

  private Totp() {}

  public static final int DIGITS = 6;
  public static final int STEP_SECONDS = 30;
  public static final int WINDOW = 1;

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  public static String generateSecret() {
    byte[] raw = new byte[20];
    new SecureRandom().nextBytes(raw);
    return base32Encode(raw);
  }

  /** The URI an authenticator app is pointed at. The label is the source's own. */
  public static String uri(String username, String secret) {
    return "otpauth://totp/Uptime%20Kuma:"
        + username
        + "?secret="
        + secret.replace("=", "");
  }

  public static boolean verify(String token, String secret, long nowEpochMillis) {
    if (token == null || secret == null || token.length() != DIGITS) {
      return false;
    }
    long counter = nowEpochMillis / 1000L / STEP_SECONDS;
    for (int drift = -WINDOW; drift <= WINDOW; drift++) {
      if (token.equals(code(secret, counter + drift))) {
        return true;
      }
    }
    return false;
  }

  public static String code(String secret, long counter) {
    byte[] key = base32Decode(secret);
    byte[] message = new byte[8];
    for (int i = 7; i >= 0; i--) {
      message[i] = (byte) (counter & 0xff);
      counter >>>= 8;
    }
    byte[] digest;
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      digest = mac.doFinal(message);
    } catch (Exception e) {
      throw new IllegalStateException("cannot compute totp", e);
    }
    int offset = digest[digest.length - 1] & 0x0f;
    int binary =
        ((digest[offset] & 0x7f) << 24)
            | ((digest[offset + 1] & 0xff) << 16)
            | ((digest[offset + 2] & 0xff) << 8)
            | (digest[offset + 3] & 0xff);
    return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
  }

  static String base32Encode(byte[] data) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
    }
    return out.toString();
  }

  static byte[] base32Decode(String encoded) {
    String cleaned = encoded.replace("=", "").replace(" ", "").toUpperCase();
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : cleaned.toCharArray()) {
      int index = BASE32.indexOf(c);
      if (index < 0) {
        continue;
      }
      buffer = (buffer << 5) | index;
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  /** A random string of the length the source's nanoid calls ask for, from its own alphabet. */
  public static String nanoid(int length) {
    String alphabet = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict";
    SecureRandom random = new SecureRandom();
    StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return out.toString();
  }

  /** Present so a secret can be checked for being readable base32 before it is stored. */
  public static boolean isReadableSecret(String secret) {
    return secret != null
        && !secret.isBlank()
        && base32Decode(secret).length > 0
        && new String(secret.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII)
            .equals(secret);
  }
}
