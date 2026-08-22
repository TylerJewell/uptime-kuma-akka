package io.akka.uptimekuma.application;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on an ephemeral port, used as the thing a monitor checks and as the thing a
 * notification is delivered to.
 *
 * <p>Real rather than stubbed because the port's probe and its notification transport are both
 * ordinary HTTP clients, and standing in for them would leave the two pieces the integration test
 * exists to exercise untested.
 */
final class FakeEndpointServer implements AutoCloseable {

  private final HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final List<Request> received = new CopyOnWriteArrayList<>();

  record Request(String method, String body) {}

  FakeEndpointServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(
              new Request(
                  exchange.getRequestMethod(),
                  new String(exchange.getRequestBody().readAllBytes())));
          int code = status.get();
          exchange.sendResponseHeaders(code, -1);
          exchange.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  void answerWith(int statusCode) {
    status.set(statusCode);
  }

  List<Request> received() {
    return List.copyOf(received);
  }

  List<String> bodies() {
    return received.stream().map(Request::body).toList();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
