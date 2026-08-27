package io.akka.uptimekuma.domain;

/**
 * The four states a heartbeat can be in.
 *
 * <p>The ordinals are part of the wire format, not an implementation detail: the interface, the
 * badge routes, the Prometheus gauge and the status page all read the integer. uptime-kuma declares
 * them in {@code src/util.ts}.
 */
public enum Status {
  DOWN(0),
  UP(1),
  PENDING(2),
  MAINTENANCE(3);

  private final int code;

  Status(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  public static Status of(int code) {
    return switch (code) {
      case 0 -> DOWN;
      case 1 -> UP;
      case 2 -> PENDING;
      case 3 -> MAINTENANCE;
      default -> throw new IllegalArgumentException("Invalid status: " + code);
    };
  }

  /**
   * Swap UP and DOWN, leave the other two alone.
   *
   * <p>PENDING and MAINTENANCE pass through unchanged, which is what makes an upside-down monitor
   * still able to be pending: the flip happens twice around the retry decision and a state that
   * does not flip is unaffected by either application.
   */
  public Status flip() {
    return switch (this) {
      case UP -> DOWN;
      case DOWN -> UP;
      default -> this;
    };
  }

  /**
   * How this status counts towards uptime. MAINTENANCE is handled before this is reached — it
   * counts towards neither — so only the up/down split is expressed here.
   */
  public boolean countsAsUp() {
    return this == UP || this == MAINTENANCE;
  }
}
