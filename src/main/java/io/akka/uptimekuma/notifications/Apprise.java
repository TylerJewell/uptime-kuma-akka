package io.akka.uptimekuma.notifications;

/**
 * Whether the one target that shells out has its command on the path.
 *
 * <p>The settings screen asks before it offers the target, so this is reachable from outside the
 * package that holds the target itself.
 */
public final class Apprise {

  private Apprise() {}

  public static boolean available() {
    return PushProviders.Apprise.available();
  }
}
