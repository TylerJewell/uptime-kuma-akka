package io.akka.uptimekuma.application;

import akka.javasdk.client.ComponentClient;

/** Handing out the next identifier for a kind. */
public final class Ids {

  private Ids() {}

  public static String next(ComponentClient componentClient, String kind) {
    return String.valueOf(nextNumber(componentClient, kind));
  }

  /** The same allocation where the caller wants the number rather than a row identifier. */
  public static long nextNumber(ComponentClient componentClient, String kind) {
    return componentClient.forKeyValueEntity("counter-" + kind).method(CounterEntity::next).invoke();
  }

  /** What this counter has handed out so far, without taking another. */
  public static long current(ComponentClient componentClient, String kind) {
    return componentClient
        .forKeyValueEntity("counter-" + kind)
        .method(CounterEntity::current)
        .invoke();
  }

  /** Make sure a counter never hands out an identifier a row already has. */
  public static void seen(ComponentClient componentClient, String kind, String id) {
    try {
      long numeric = Long.parseLong(id);
      componentClient
          .forKeyValueEntity("counter-" + kind)
          .method(CounterEntity::atLeast)
          .invoke(numeric);
    } catch (NumberFormatException ignored) {
      // An identifier that is not a number cannot collide with one this counter hands out.
    }
  }
}
