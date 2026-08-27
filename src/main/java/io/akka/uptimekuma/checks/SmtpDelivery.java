package io.akka.uptimekuma.checks;

import java.io.IOException;
import java.util.List;

/**
 * The mail session, reached from outside this package.
 *
 * <p>{@link SmtpSession} is where the protocol lives and it is package-private because the smtp
 * monitor type is its main caller. The smtp notification target needs the same session from
 * another package, so this is the one door it comes through.
 */
public final class SmtpDelivery implements AutoCloseable {

  private final SmtpSession session;

  private SmtpDelivery(SmtpSession session) {
    this.session = session;
  }

  public static SmtpDelivery open(
      String host, int port, int timeoutMillis, String security, boolean ignoreTls)
      throws IOException {
    return new SmtpDelivery(SmtpSession.open(host, port, timeoutMillis, security, ignoreTls));
  }

  public void login(String username, String password) throws IOException {
    session.login(username, password);
  }

  public void deliver(String from, List<String> recipients, String message) throws IOException {
    session.deliver(from, recipients, message);
  }

  @Override
  public void close() {
    session.close();
  }
}
