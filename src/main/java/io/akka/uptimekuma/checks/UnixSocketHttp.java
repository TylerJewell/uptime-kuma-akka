package io.akka.uptimekuma.checks;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * One HTTP request over a filesystem socket.
 *
 * <p>A container daemon is usually reached this way rather than over a network port, and no HTTP
 * client in the platform will speak to a socket that has a path instead of a host. The protocol
 * over it is ordinary HTTP, so the request is written by hand and the reply is read back the same
 * way.
 */
final class UnixSocketHttp {

  private UnixSocketHttp() {}

  static String get(String socketPath, String path, long timeoutMillis) throws Exception {
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
    try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(address);
      String request =
          "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAccept: */*\r\n\r\n";
      channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));

      ByteArrayOutputStream collected = new ByteArrayOutputStream();
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      long deadline = System.currentTimeMillis() + timeoutMillis;
      while (System.currentTimeMillis() < deadline) {
        buffer.clear();
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
        collected.write(buffer.array(), 0, read);
      }
      String response = collected.toString(StandardCharsets.UTF_8);
      int split = response.indexOf("\r\n\r\n");
      if (split < 0) {
        throw new java.io.IOException("no HTTP response from " + socketPath);
      }
      String head = response.substring(0, split);
      String body = response.substring(split + 4);
      int status = Integer.parseInt(head.split(" ")[1]);
      if (status >= 400) {
        throw new java.io.IOException("daemon answered " + status);
      }
      // A daemon answers with chunked transfer encoding unless it knows the length up front, and
      // the chunk headers are not part of the document.
      if (head.toLowerCase().contains("transfer-encoding: chunked")) {
        return dechunk(body);
      }
      return body;
    }
  }

  private static String dechunk(String body) {
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    while (cursor < body.length()) {
      int lineEnd = body.indexOf("\r\n", cursor);
      if (lineEnd < 0) {
        break;
      }
      int size;
      try {
        size = Integer.parseInt(body.substring(cursor, lineEnd).trim(), 16);
      } catch (NumberFormatException e) {
        break;
      }
      if (size == 0) {
        break;
      }
      int start = lineEnd + 2;
      int end = Math.min(start + size, body.length());
      out.append(body, start, end);
      cursor = end + 2;
    }
    return out.toString();
  }
}
