package io.akka.uptimekuma.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.mindrot.jbcrypt.BCrypt;

/**
 * How a password is stored and checked.
 *
 * <p>New hashes are bcrypt at cost ten. Hashes beginning {@code sha1} are the shape a 1.x install
 * left behind; they still verify, and a successful verification against one is a signal to rewrite
 * it as bcrypt. Dropping the legacy shape would lock out every account carried over from a 1.x
 * database, which is the reason the source still carries it.
 */
public final class Passwords {

  private Passwords() {}

  public static final int COST = 10;

  public static String generate(String password) {
    return BCrypt.hashpw(password, BCrypt.gensalt(COST));
  }

  public static boolean verify(String password, String hash) {
    if (password == null || hash == null) {
      return false;
    }
    if (hash.startsWith("sha1")) {
      return verifyLegacySha1(password, hash);
    }
    try {
      return BCrypt.checkpw(password, hash);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public static boolean needRehash(String hash) {
    return hash != null && hash.startsWith("sha1");
  }

  /**
   * The node {@code password-hash} module's format: {@code algorithm$salt$digest}, where the digest
   * is a hex SHA-1 of salt concatenated with the password.
   */
  private static boolean verifyLegacySha1(String password, String hash) {
    String[] parts = hash.split("\\$", 3);
    if (parts.length != 3) {
      return false;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] computed = digest.digest((parts[1] + password).getBytes(StandardCharsets.UTF_8));
      return MessageDigest.isEqual(
          HexFormat.of().formatHex(computed).getBytes(StandardCharsets.UTF_8),
          parts[2].getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      return false;
    }
  }

  /**
   * How weak a password is, on the same five-step scale the interface shows.
   *
   * <p>This is `check-password-strength`'s own algorithm rather than an approximation of it,
   * because the only thing the server does with the reading is refuse `Too weak` and a rule about
   * which passwords are refused is not a rule anybody wants nearly right. Two numbers decide it:
   * how many of the four character classes the password contains, and how long it is. The steps
   * are the library's defaults — `Weak` needs two classes and six characters, `Medium` four and
   * eight, `Strong` four and ten — and the highest step both numbers reach is the answer.
   *
   * <p>The class that catches people out is `symbol`: it is the library's own list of punctuation,
   * and a space is not on it. So "a different long password" contains one class, not two, and the
   * source refuses it. Found by running the two side by side; a hand-written scale had scored it
   * on its length and accepted it.
   */
  public static String strength(String password) {
    String value = password == null ? "" : password;
    int classes = 0;
    if (value.matches(".*[a-z].*")) {
      classes++;
    }
    if (value.matches(".*[A-Z].*")) {
      classes++;
    }
    if (value.matches(".*[0-9].*")) {
      classes++;
    }
    if (containsSymbol(value)) {
      classes++;
    }
    if (classes >= 4 && value.length() >= 10) {
      return "Strong";
    }
    if (classes >= 4 && value.length() >= 8) {
      return "Medium";
    }
    if (classes >= 2 && value.length() >= 6) {
      return "Weak";
    }
    return "Too weak";
  }

  /** The library's own punctuation list, which is what makes a space not count. */
  private static final String SYMBOLS = "!" + '"' + "#$%&'()*+,-./:;<=>?@[" + "\\" + "]^_`{|}~";

  private static boolean containsSymbol(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (SYMBOLS.indexOf(value.charAt(i)) >= 0) {
        return true;
      }
    }
    return false;
  }

  public static boolean tooWeak(String password) {
    return "Too weak".equals(strength(password));
  }
}
