package io.akka.uptimekuma.checks;

/**
 * The names of the TLS alerts a handshake can be refused with.
 *
 * <p>A port monitor can be configured to expect one, which is how a server that is meant to reject
 * a client without a certificate is checked: the refusal is the healthy answer, and the name it
 * refused with is what distinguishes the healthy refusal from every other one.
 */
final class TlsAlerts {

  private TlsAlerts() {}

  static String name(int number) {
    return switch (number) {
      case 0 -> "close_notify";
      case 10 -> "unexpected_message";
      case 20 -> "bad_record_mac";
      case 21 -> "decryption_failed";
      case 22 -> "record_overflow";
      case 30 -> "decompression_failure";
      case 40 -> "handshake_failure";
      case 41 -> "no_certificate";
      case 42 -> "bad_certificate";
      case 43 -> "unsupported_certificate";
      case 44 -> "certificate_revoked";
      case 45 -> "certificate_expired";
      case 46 -> "certificate_unknown";
      case 47 -> "illegal_parameter";
      case 48 -> "unknown_ca";
      case 49 -> "access_denied";
      case 50 -> "decode_error";
      case 51 -> "decrypt_error";
      case 60 -> "export_restriction";
      case 70 -> "protocol_version";
      case 71 -> "insufficient_security";
      case 80 -> "internal_error";
      case 86 -> "inappropriate_fallback";
      case 90 -> "user_canceled";
      case 100 -> "no_renegotiation";
      case 109 -> "missing_extension";
      case 110 -> "unsupported_extension";
      case 111 -> "certificate_unobtainable";
      case 112 -> "unrecognized_name";
      case 113 -> "bad_certificate_status_response";
      case 114 -> "bad_certificate_hash_value";
      case 115 -> "unknown_psk_identity";
      case 116 -> "certificate_required";
      case 120 -> "no_application_protocol";
      default -> null;
    };
  }

  /**
   * The alert number a failed handshake reported, read out of the exception message.
   *
   * <p>The runtime does not expose the number as a field, so the message is the only place it
   * appears — either as a bare number after the word "alert", or as the name itself.
   */
  static Integer parseNumber(String message) {
    if (message == null) {
      return null;
    }
    java.util.regex.Matcher numeric =
        java.util.regex.Pattern.compile("alert(?:\\s+number)?[:\\s]+(\\d+)").matcher(message);
    if (numeric.find()) {
      return Integer.valueOf(numeric.group(1));
    }
    String lowered = message.toLowerCase(java.util.Locale.ROOT);
    for (int candidate = 0; candidate <= 120; candidate++) {
      String name = name(candidate);
      if (name != null && lowered.contains(name)) {
        return candidate;
      }
    }
    return null;
  }
}
