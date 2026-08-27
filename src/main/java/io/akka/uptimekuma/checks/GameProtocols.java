package io.akka.uptimekuma.checks;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The query protocols a game server may answer on, and which title uses which.
 *
 * <p>The table of titles is the one the source's own query library ships, copied out of it, so the
 * keys a monitor was configured with mean the same thing here. Six protocol families are
 * implemented, covering most of the table; a title on one of the others is refused by name rather
 * than reported down, and the count is in the README.
 */
public final class GameProtocols {

  private GameProtocols() {}

  /** The protocol families this port speaks. */
  static final List<String> IMPLEMENTED =
      List.of("valve", "goldsrc", "minecraft", "quake1", "quake2", "quake3", "gamespy1", "gamespy2");

  private static Map<String, Game> games;
  private static List<Map<String, Object>> catalogue;

  /** Every title, in the order and shape the source's own list serves them. */
  public static synchronized List<Map<String, Object>> catalogue() {
    if (catalogue == null) {
      try (var in = GameProtocols.class.getResourceAsStream("/gamedig-games.json")) {
        catalogue = List.copyOf(JsonQuery.Json.MAPPER.readValue(in, List.class));
      } catch (Exception e) {
        throw new IllegalStateException("cannot read the game table", e);
      }
    }
    return catalogue;
  }

  record Game(String key, String pretty, String protocol, Integer port) {}

  static synchronized Map<String, Game> games() {
    if (games == null) {
      Map<String, Game> loaded = new LinkedHashMap<>();
      try (var in = GameProtocols.class.getResourceAsStream("/gamedig-games.json")) {
        List<?> rows = JsonQuery.Json.MAPPER.readValue(in, List.class);
        for (Object row : rows) {
          if (row instanceof Map<?, ?> entry) {
            Object port = entry.get("port");
            loaded.put(
                String.valueOf(entry.get("key")),
                new Game(
                    String.valueOf(entry.get("key")),
                    String.valueOf(entry.get("pretty")),
                    entry.get("protocol") == null ? null : String.valueOf(entry.get("protocol")),
                    port == null ? null : (int) Double.parseDouble(String.valueOf(port))));
          }
        }
      } catch (Exception e) {
        throw new IllegalStateException("cannot read the game table", e);
      }
      games = Map.copyOf(loaded);
    }
    return games;
  }

  /** Which protocol a title's key selects, or null when the table does not know the key. */
  static String protocolFor(String game) {
    Game entry = games().get(game == null ? "" : game.toLowerCase(Locale.ROOT));
    return entry == null ? null : entry.protocol();
  }

  static Integer defaultPortFor(String game) {
    Game entry = games().get(game == null ? "" : game.toLowerCase(Locale.ROOT));
    return entry == null ? null : entry.port();
  }

  /** Ask a server, in whichever protocol its title uses, and hand back the name it reports. */
  static String query(String game, String host, int port, int timeoutMillis) throws Exception {
    String protocol = protocolFor(game);
    if (protocol == null) {
      throw new java.io.IOException("The game '" + game + "' is not one this port knows");
    }
    return switch (protocol) {
      case "valve", "goldsrc" -> valve(host, port, timeoutMillis);
      case "minecraft" -> minecraft(host, port, timeoutMillis);
      case "quake1", "quake2", "quake3" -> quake(host, port, timeoutMillis);
      case "gamespy1" -> gamespy1(host, port, timeoutMillis);
      case "gamespy2" -> gamespy2(host, port, timeoutMillis);
      default ->
          throw new java.io.IOException(
              "The game '"
                  + game
                  + "' uses the "
                  + protocol
                  + " query protocol, which this port does not implement");
    };
  }

  /**
   * The A2S_INFO exchange.
   *
   * <p>A server answers a challenge before it answers the question, so the request is sent twice:
   * once to collect the challenge and once carrying it.
   */
  static String valve(String host, int port, int timeoutMillis) throws Exception {
    byte[] request = buildInfoRequest(new byte[0]);
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(timeoutMillis);
      InetAddress address = InetAddress.getByName(host);
      socket.send(new DatagramPacket(request, request.length, address, port));
      byte[] buffer = new byte[4096];
      DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
      socket.receive(reply);

      ByteBuffer answer =
          ByteBuffer.wrap(buffer, 0, reply.getLength()).order(ByteOrder.LITTLE_ENDIAN);
      answer.position(4);
      byte header = answer.get();
      if (header == 0x41) {
        byte[] challenge = new byte[4];
        answer.get(challenge);
        byte[] second = buildInfoRequest(challenge);
        socket.send(new DatagramPacket(second, second.length, address, port));
        reply = new DatagramPacket(buffer, buffer.length);
        socket.receive(reply);
        answer = ByteBuffer.wrap(buffer, 0, reply.getLength()).order(ByteOrder.LITTLE_ENDIAN);
        answer.position(4);
        header = answer.get();
      }
      if (header != 0x49) {
        throw new java.io.IOException("unexpected reply header from " + host + ":" + port);
      }
      answer.get();
      return readCString(answer);
    }
  }

  private static byte[] buildInfoRequest(byte[] challenge) {
    byte[] question = "Source Engine Query\0".getBytes(StandardCharsets.US_ASCII);
    ByteBuffer buffer = ByteBuffer.allocate(5 + question.length + challenge.length);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(-1);
    buffer.put((byte) 0x54);
    buffer.put(question);
    buffer.put(challenge);
    return buffer.array();
  }

  private static String readCString(ByteBuffer buffer) {
    StringBuilder out = new StringBuilder();
    while (buffer.hasRemaining()) {
      byte b = buffer.get();
      if (b == 0) {
        break;
      }
      out.append((char) (b & 0xff));
    }
    return new String(out.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
  }

  /**
   * The server-list ping, a small handshake over the game's own connection protocol that answers
   * with the description a client shows in its server list.
   */
  static String minecraft(String host, int port, int timeoutMillis) throws Exception {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      DataInputStream in = new DataInputStream(socket.getInputStream());

      java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
      handshake.write(0x00);
      writeVarInt(handshake, 763);
      byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
      writeVarInt(handshake, hostBytes.length);
      handshake.write(hostBytes);
      handshake.write((port >> 8) & 0xff);
      handshake.write(port & 0xff);
      // One asks for the status rather than to join.
      writeVarInt(handshake, 1);
      writePacket(out, handshake.toByteArray());
      writePacket(out, new byte[] {0x00});
      out.flush();

      readVarInt(in);
      int packetId = readVarInt(in);
      if (packetId != 0) {
        throw new java.io.IOException("unexpected packet from " + host + ":" + port);
      }
      int length = readVarInt(in);
      byte[] json = new byte[length];
      in.readFully(json);
      Map<String, Object> status =
          JsonQuery.Json.MAPPER.readValue(new String(json, StandardCharsets.UTF_8), Map.class);
      Object description = status.get("description");
      if (description instanceof Map<?, ?> map && map.get("text") != null) {
        return String.valueOf(map.get("text"));
      }
      return String.valueOf(description);
    }
  }

  private static void writePacket(DataOutputStream out, byte[] payload) throws Exception {
    java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
    writeVarInt(framed, payload.length);
    framed.write(payload);
    out.write(framed.toByteArray());
  }

  private static void writeVarInt(java.io.OutputStream out, int value) throws Exception {
    int remaining = value;
    while (true) {
      if ((remaining & ~0x7F) == 0) {
        out.write(remaining);
        return;
      }
      out.write((remaining & 0x7F) | 0x80);
      remaining >>>= 7;
    }
  }

  private static int readVarInt(DataInputStream in) throws Exception {
    int value = 0;
    int position = 0;
    while (true) {
      int current = in.readByte();
      value |= (current & 0x7F) << position;
      if ((current & 0x80) == 0) {
        return value;
      }
      position += 7;
      if (position >= 32) {
        throw new java.io.IOException("VarInt is too big");
      }
    }
  }

  /** The getstatus exchange, which answers with one backslash-separated run of settings. */
  static String quake(String host, int port, int timeoutMillis) throws Exception {
    String text = udpText(host, port, timeoutMillis, "ÿÿÿÿgetstatus\n");
    int settingsStart = text.indexOf('\\');
    if (settingsStart < 0) {
      throw new java.io.IOException("no settings in reply from " + host + ":" + port);
    }
    Map<String, String> settings = backslashPairs(text.substring(settingsStart + 1));
    for (String key : List.of("sv_hostname", "hostname", "Server Name")) {
      if (settings.containsKey(key)) {
        return settings.get(key);
      }
    }
    throw new java.io.IOException("server did not report a name");
  }

  /** The older status query, which answers with the same backslash-separated run. */
  static String gamespy1(String host, int port, int timeoutMillis) throws Exception {
    String text = udpText(host, port, timeoutMillis, "\\status\\");
    Map<String, String> settings = backslashPairs(text.startsWith("\\") ? text.substring(1) : text);
    for (String key : List.of("hostname", "sv_hostname", "servername")) {
      if (settings.containsKey(key)) {
        return settings.get(key);
      }
    }
    throw new java.io.IOException("server did not report a name");
  }

  /**
   * The newer status query, which answers with null-terminated pairs rather than a run.
   *
   * <p>The request asks for the server's own settings and neither its players nor its teams, which
   * is what the three trailing flags say.
   */
  static String gamespy2(String host, int port, int timeoutMillis) throws Exception {
    byte[] request = {
      (byte) 0xFE, (byte) 0xFD, 0x00, 0x04, 0x05, 0x06, 0x07, (byte) 0xFF, 0x00, 0x00
    };
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(timeoutMillis);
      socket.send(
          new DatagramPacket(request, request.length, InetAddress.getByName(host), port));
      byte[] buffer = new byte[8192];
      DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
      socket.receive(reply);
      // Five bytes of header, then key and value alternating, each ending with a zero byte.
      ByteBuffer answer = ByteBuffer.wrap(buffer, 5, reply.getLength() - 5);
      String key = readNullTerminated(answer);
      while (key != null && !key.isEmpty()) {
        String value = readNullTerminated(answer);
        if ("hostname".equals(key)) {
          return value;
        }
        key = readNullTerminated(answer);
      }
    }
    throw new java.io.IOException("server did not report a name");
  }

  private static String readNullTerminated(ByteBuffer buffer) {
    if (!buffer.hasRemaining()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    while (buffer.hasRemaining()) {
      byte b = buffer.get();
      if (b == 0) {
        break;
      }
      out.append((char) (b & 0xff));
    }
    return out.toString();
  }

  private static String udpText(String host, int port, int timeoutMillis, String request)
      throws Exception {
    byte[] payload = request.getBytes(StandardCharsets.ISO_8859_1);
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(timeoutMillis);
      socket.send(
          new DatagramPacket(payload, payload.length, InetAddress.getByName(host), port));
      byte[] buffer = new byte[8192];
      DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
      socket.receive(reply);
      return new String(buffer, 0, reply.getLength(), StandardCharsets.ISO_8859_1);
    }
  }

  private static Map<String, String> backslashPairs(String text) {
    Map<String, String> settings = new LinkedHashMap<>();
    String[] parts = text.split("\\\\");
    for (int i = 0; i + 1 < parts.length; i += 2) {
      settings.put(parts[i], parts[i + 1]);
    }
    return settings;
  }
}
