package io.akka.uptimekuma.checks;

/**
 * Turning an internationalised host name into the ASCII form a resolver accepts.
 *
 * <p>Needed because the operating system's ping tool refuses a name with non-ASCII characters in
 * it, and a monitor may be given one — the source converts before spawning for the same reason.
 */
final class Punycode {

  private Punycode() {}

  static String encode(String hostname) {
    if (hostname == null) {
      return null;
    }
    try {
      return java.net.IDN.toASCII(hostname);
    } catch (IllegalArgumentException e) {
      return hostname;
    }
  }
}
