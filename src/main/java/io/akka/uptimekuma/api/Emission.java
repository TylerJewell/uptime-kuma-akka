package io.akka.uptimekuma.api;

import java.util.ArrayList;
import java.util.List;

/**
 * One message the server pushes at a client, in the shape the interface's own handlers expect.
 *
 * <p>The source pushes these over a socket at any moment. Here they arrive two ways: the ones that
 * follow from a call the client just made come back with that call's answer, and the ones nobody
 * asked for — a heartbeat, a statistic — arrive on the stream. Both are the same shape, so the
 * interface's handler for each name is reached the same way whichever route it came by.
 *
 * @param name the event name the interface listens for
 * @param args the arguments, in order, exactly as the source emits them
 */
public record Emission(String name, List<Object> args) {

  public static Emission of(String name, Object... args) {
    List<Object> list = new ArrayList<>();
    for (Object arg : args) {
      list.add(arg);
    }
    return new Emission(name, list);
  }
}
