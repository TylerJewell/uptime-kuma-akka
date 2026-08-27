package io.akka.uptimekuma.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Passwords, tokens, second factors, and the digest the token's revocation claim is built on. */
class SecurityTest {

  @Test
  void aPasswordVerifiesAgainstItsOwnHashAndNothingElse() {
    String hash = Passwords.generate("correct horse battery staple");
    assertTrue(Passwords.verify("correct horse battery staple", hash));
    assertFalse(Passwords.verify("Correct horse battery staple", hash));
    assertFalse(Passwords.verify("", hash));
  }

  @Test
  void twoHashesOfOnePasswordDiffer() {
    // A salt per hash, so two accounts with the same password are not recognisable as such.
    assertNotEquals(Passwords.generate("hunter22"), Passwords.generate("hunter22"));
  }

  @Test
  void aHashOfTheOlderShapeStillVerifiesAndAsksToBeReplaced() throws Exception {
    // The shape a 1.x install left behind: algorithm, salt and a hex digest of salt plus password.
    String salt = "abc123";
    String password = "hunter22";
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
    String hex =
        HexFormat.of()
            .formatHex(digest.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
    String legacy = "sha1$" + salt + "$" + hex;
    assertTrue(Passwords.verify(password, legacy));
    assertFalse(Passwords.verify("wrong", legacy));
    assertTrue(Passwords.needRehash(legacy));
    assertFalse(Passwords.needRehash(Passwords.generate(password)));
  }

  /**
   * The five-step scale, on twelve passwords read off the original's own library. R61.
   *
   * <p>The two entries worth reading twice are the long all-lowercase ones. Length alone does not
   * move the reading at all: a passphrase of four words in one character class is `Too weak` and is
   * refused, while a shorter password mixing four classes is `Strong`. A hand-written scale that
   * scored length accepted the first, which is the opposite answer to the one the source gives.
   */
  @Test
  void theStrengthScaleIsTheOnesTheSourcesOwnLibraryGives() {
    assertEquals("Too weak", Passwords.strength(""));
    assertEquals("Too weak", Passwords.strength("abc"));
    assertEquals("Too weak", Passwords.strength("abcdef"));
    assertEquals("Too weak", Passwords.strength("abcdefghijkl"));
    assertEquals("Too weak", Passwords.strength("a different long password"));
    assertEquals("Too weak", Passwords.strength("correct horse battery staple"));
    assertEquals("Too weak", Passwords.strength("aB3!"));
    assertEquals("Weak", Passwords.strength("ABCdefghijkl"));
    assertEquals("Weak", Passwords.strength("kuma-port-2026"));
    assertEquals("Medium", Passwords.strength("Passw0rd!"));
    assertEquals("Medium", Passwords.strength("aB3!efgh"));
    assertEquals("Strong", Passwords.strength("Passw0rd!x"));
    assertEquals("Strong", Passwords.strength("aB3!efghij"));
    assertEquals("Strong", Passwords.strength("Tr0ub4dor&3!"));
  }

  /** A space is not one of the library's symbols, which is what makes a passphrase weak. */
  @Test
  void aSpaceDoesNotCountAsASymbolAndPunctuationDoes() {
    assertEquals("Too weak", Passwords.strength("aaaaaa bbbbbb"));
    assertEquals("Weak", Passwords.strength("aaaaaa-bbbbbb"));
  }

  @Test
  void aWeakPasswordIsTheOnlyReadingTheServerActsOn() {
    assertTrue(Passwords.tooWeak("abc"));
    assertTrue(Passwords.tooWeak(""));
    assertTrue(Passwords.tooWeak("correct horse battery staple"));
    assertFalse(Passwords.tooWeak("Tr0ub4dor&3!"));
  }

  @Test
  void aTokenCarriesTheUsernameAndADigestOfThePasswordHash() {
    String hash = Passwords.generate("hunter22");
    String token = Jwt.create("admin", hash, "secret");
    JsonNode payload = Jwt.verify(token, "secret");
    assertEquals("admin", payload.path("username").asText());
    assertEquals(Jwt.passwordDigest(hash), payload.path("h").asText());
    // No expiry: the digest is the whole of the revocation mechanism.
    assertTrue(payload.path("exp").isMissingNode());
  }

  @Test
  void aTokenSignedWithAnotherSecretDoesNotVerify() {
    String token = Jwt.create("admin", Passwords.generate("hunter22"), "secret");
    assertThrows(IllegalArgumentException.class, () -> Jwt.verify(token, "another"));
  }

  @Test
  void aTamperedTokenDoesNotVerify() {
    String token = Jwt.create("admin", Passwords.generate("hunter22"), "secret");
    String[] parts = token.split("\\.");
    String forged =
        parts[0]
            + "."
            + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"username\":\"root\",\"h\":\"x\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[2];
    assertThrows(IllegalArgumentException.class, () -> Jwt.verify(forged, "secret"));
  }

  @Test
  void changingThePasswordChangesTheDigestTheTokenCarries() {
    String before = Passwords.generate("hunter22");
    String after = Passwords.generate("hunter23");
    assertNotEquals(Jwt.passwordDigest(before), Jwt.passwordDigest(after));
  }

  @Test
  void theDigestIsShake256TruncatedToSixteenBytes() {
    // The published value for the empty input, which fixes both the function and the length.
    assertEquals(32, Jwt.passwordDigest("").length());
    assertEquals("46b9dd2b0ba88d13233b3feb743eeb24", Jwt.passwordDigest(""));
  }

  @Test
  void aSecondFactorCodeVerifiesInsideItsOwnStep() {
    String secret = Totp.generateSecret();
    long now = 1_700_000_000_000L;
    String code = Totp.code(secret, now / 1000 / Totp.STEP_SECONDS);
    assertTrue(Totp.verify(code, secret, now));
    assertFalse(Totp.verify("000000".equals(code) ? "111111" : "000000", secret, now));
  }

  @Test
  void aCodeFromTheStepEitherSideIsStillAccepted() {
    String secret = Totp.generateSecret();
    long now = 1_700_000_000_000L;
    long step = Totp.STEP_SECONDS * 1000L;
    // A clock a few seconds out is common enough that refusing it would lock people out.
    assertTrue(Totp.verify(Totp.code(secret, (now - step) / 1000 / Totp.STEP_SECONDS), secret, now));
    assertTrue(Totp.verify(Totp.code(secret, (now + step) / 1000 / Totp.STEP_SECONDS), secret, now));
    assertFalse(
        Totp.verify(Totp.code(secret, (now - 3 * step) / 1000 / Totp.STEP_SECONDS), secret, now));
  }

  @Test
  void aCodeIsSixDigits() {
    String secret = Totp.generateSecret();
    String code = Totp.code(secret, 1);
    assertEquals(6, code.length());
    assertTrue(code.matches("^[0-9]{6}$"));
  }

  @Test
  void theAuthenticatorUriNamesTheAccountAndStripsThePadding() {
    String uri = Totp.uri("admin", "ABCDEFGH====");
    assertEquals("otpauth://totp/Uptime%20Kuma:admin?secret=ABCDEFGH", uri);
  }

  @Test
  void aGeneratedIdentifierIsTheLengthAskedForAndNotRepeated() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      String id = Totp.nanoid(40);
      assertEquals(40, id.length());
      seen.add(id);
    }
    assertEquals(50, seen.size());
  }
}
