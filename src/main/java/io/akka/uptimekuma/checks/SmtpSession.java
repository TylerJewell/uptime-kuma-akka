package io.akka.uptimekuma.checks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Enough of the mail protocol to greet a server, negotiate encryption and hand over a message.
 *
 * <p>Two things need it: the smtp monitor type, which opens a session and closes it again to prove
 * the server is answering, and the smtp notification target, which actually delivers a message.
 * Both are the same handshake up to the point where one of them stops.
 */
final class SmtpSession implements AutoCloseable {

  private Socket socket;
  private BufferedReader reader;
  private OutputStream writer;
  private final List<String> capabilities = new ArrayList<>();

  private SmtpSession() {}

  /**
   * @param security {@code secure} wraps the connection in TLS from the first byte; {@code
   *     starttls} negotiates it after greeting; {@code nostarttls} leaves the session in the clear
   */
  static SmtpSession open(
      String host, int port, int timeoutMillis, String security, boolean ignoreTls)
      throws IOException {
    SmtpSession session = new SmtpSession();
    try {
      if ("secure".equals(security)) {
        SSLSocketFactory factory =
            Http.sslContext(!ignoreTls, null, null, null).getSocketFactory();
        SSLSocket secure = (SSLSocket) factory.createSocket();
        secure.connect(new InetSocketAddress(host, port), timeoutMillis);
        secure.setSoTimeout(timeoutMillis);
        session.attach(secure);
        secure.startHandshake();
      } else {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMillis);
        plain.setSoTimeout(timeoutMillis);
        session.attach(plain);
      }
      session.expect(220);
      session.ehlo(host);
      if ("starttls".equals(security)) {
        session.command("STARTTLS", 220);
        SSLSocketFactory factory =
            Http.sslContext(!ignoreTls, null, null, null).getSocketFactory();
        SSLSocket upgraded =
            (SSLSocket) factory.createSocket(session.socket, host, port, false);
        upgraded.setSoTimeout(timeoutMillis);
        session.attach(upgraded);
        upgraded.startHandshake();
        session.ehlo(host);
      }
      return session;
    } catch (IOException e) {
      session.closeQuietly();
      throw e;
    }
  }

  private void attach(Socket connected) throws IOException {
    this.socket = connected;
    this.reader =
        new BufferedReader(new InputStreamReader(connected.getInputStream(), StandardCharsets.UTF_8));
    this.writer = connected.getOutputStream();
  }

  private void ehlo(String host) throws IOException {
    write("EHLO " + host);
    capabilities.clear();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.length() < 4) {
        break;
      }
      capabilities.add(line.substring(4).trim());
      if (line.charAt(3) == ' ') {
        break;
      }
    }
    if (capabilities.isEmpty()) {
      throw new IOException("server did not answer EHLO");
    }
  }

  boolean supports(String capability) {
    return capabilities.stream()
        .anyMatch(entry -> entry.toUpperCase().startsWith(capability.toUpperCase()));
  }

  String command(String text, int expected) throws IOException {
    write(text);
    return expect(expected);
  }

  private void write(String text) throws IOException {
    writer.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
    writer.flush();
  }

  /** Read a reply, following the continuation lines a multi-line answer uses. */
  private String expect(int code) throws IOException {
    StringBuilder all = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      all.append(line).append('\n');
      if (line.length() >= 4 && line.charAt(3) == '-') {
        continue;
      }
      break;
    }
    if (line == null) {
      throw new IOException("connection closed while waiting for " + code);
    }
    int actual;
    try {
      actual = Integer.parseInt(line.substring(0, 3));
    } catch (Exception e) {
      throw new IOException("unreadable reply: " + line);
    }
    if (actual != code) {
      throw new IOException(line.trim());
    }
    return all.toString();
  }

  /** Authenticate with the simplest mechanism the server offered. */
  void login(String username, String password) throws IOException {
    if (username == null || username.isEmpty()) {
      return;
    }
    if (supports("AUTH") && capabilities.stream().anyMatch(c -> c.toUpperCase().contains("PLAIN"))) {
      String token =
          java.util.Base64.getEncoder()
              .encodeToString(("\0" + username + "\0" + password).getBytes(StandardCharsets.UTF_8));
      command("AUTH PLAIN " + token, 235);
      return;
    }
    command("AUTH LOGIN", 334);
    command(
        java.util.Base64.getEncoder()
            .encodeToString(username.getBytes(StandardCharsets.UTF_8)),
        334);
    command(
        java.util.Base64.getEncoder()
            .encodeToString((password == null ? "" : password).getBytes(StandardCharsets.UTF_8)),
        235);
  }

  void deliver(String from, List<String> recipients, String message) throws IOException {
    command("MAIL FROM:<" + from + ">", 250);
    for (String recipient : recipients) {
      command("RCPT TO:<" + recipient + ">", 250);
    }
    command("DATA", 354);
    // A line consisting of one full stop ends the message, so any such line in the body has to
    // be doubled or it would truncate what follows.
    String escaped = message.replaceAll("(?m)^\\.", "..");
    write(escaped + "\r\n.");
    expect(250);
  }

  @Override
  public void close() {
    try {
      command("QUIT", 221);
    } catch (Exception ignored) {
      // A server that hangs up rather than answering QUIT has still done everything asked of it.
    }
    closeQuietly();
  }

  private void closeQuietly() {
    try {
      if (socket != null) {
        socket.close();
      }
    } catch (IOException ignored) {
      // Nothing left to do with a socket that will not close.
    }
  }
}
