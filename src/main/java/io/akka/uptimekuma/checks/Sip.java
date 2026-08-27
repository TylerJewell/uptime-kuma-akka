package io.akka.uptimekuma.checks;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One SIP OPTIONS request, over UDP.
 *
 * <p>OPTIONS is the protocol's own "are you there": a server that is running answers it with the
 * list of methods it supports, and the only thing the check reads is whether the answer line says
 * two hundred.
 */
final class Sip {

  private Sip() {}

  static String options(String host, int port, int timeoutMillis) throws Exception {
    InetAddress address = InetAddress.getByName(host);
    // The branch identifies this transaction; the magic cookie at the front is what marks it as
    // following the current standard rather than the one before it.
    String branch = "z9hG4bK" + Long.toHexString(ThreadLocalRandom.current().nextLong());
    String callId = Long.toHexString(ThreadLocalRandom.current().nextLong()) + "@uptime-kuma";
    String tag = Long.toHexString(ThreadLocalRandom.current().nextLong());

    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(timeoutMillis);
      String localAddress = socket.getLocalAddress().getHostAddress();
      int localPort = socket.getLocalPort();
      String request =
          "OPTIONS sip:"
              + host
              + ":"
              + port
              + " SIP/2.0\r\n"
              + "Via: SIP/2.0/UDP "
              + localAddress
              + ":"
              + localPort
              + ";branch="
              + branch
              + ";rport\r\n"
              + "Max-Forwards: 70\r\n"
              + "From: <sip:uptime-kuma@"
              + localAddress
              + ">;tag="
              + tag
              + "\r\n"
              + "To: <sip:"
              + host
              + ":"
              + port
              + ">\r\n"
              + "Call-ID: "
              + callId
              + "\r\n"
              + "CSeq: 1 OPTIONS\r\n"
              + "Contact: <sip:uptime-kuma@"
              + localAddress
              + ":"
              + localPort
              + ">\r\n"
              + "Accept: application/sdp\r\n"
              + "Content-Length: 0\r\n\r\n";

      byte[] payload = request.getBytes(StandardCharsets.UTF_8);
      socket.send(new DatagramPacket(payload, payload.length, address, port));

      byte[] buffer = new byte[4096];
      DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
      socket.receive(reply);
      return new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
    }
  }
}
