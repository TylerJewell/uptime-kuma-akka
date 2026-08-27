package io.akka.uptimekuma.application;

/**
 * The version this rebuild reports.
 *
 * <p>It is the version of uptime-kuma the shipped interface is a copy of, not a version of its own.
 * The interface parses it as a release number and compares it against its own build's; anything
 * else raises a mismatch banner about a comparison that has no meaning here.
 */
public final class Versions {

  private Versions() {}

  public static final String APP_VERSION = "2.5.3";
}
