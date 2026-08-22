package io.akka.uptimekuma.domain;

/**
 * The two predicates over a status transition, and the gate in front of the second.
 *
 * <p>They are separate on purpose. A transition can be worth recording and not worth telling
 * anybody about, and the three that differ are all about a maintenance window: going into one, and
 * coming back up out of one, are changes in the monitor's state that nobody asked to be paged for.
 * SPEC-001 §3 R10–R13.
 */
public final class TransitionRules {

  private TransitionRules() {}

  /**
   * Whether this beat is a state change worth recording.
   *
   * @param previous the status of the beat before this one, or null on a monitor's first beat
   */
  public static boolean isImportant(Status previous, Status current) {
    if (previous == null) {
      return true;
    }
    return switch (previous) {
      case UP -> current == Status.DOWN || current == Status.MAINTENANCE;
      case DOWN -> current == Status.UP || current == Status.MAINTENANCE;
      case PENDING -> current == Status.DOWN;
      case MAINTENANCE -> current == Status.UP || current == Status.DOWN;
    };
  }

  /**
   * Whether this beat is a candidate for a notification.
   *
   * <p>Entering maintenance from either side, and leaving it upward, are important and silent.
   * Entering it from PENDING is neither.
   */
  public static boolean notifies(Status previous, Status current) {
    if (previous == null) {
      return true;
    }
    return switch (previous) {
      case UP, PENDING -> current == Status.DOWN;
      case DOWN -> current == Status.UP;
      case MAINTENANCE -> current == Status.DOWN;
    };
  }

  /**
   * The gate in front of the fan-out itself: a monitor's very first beat says nothing unless it is
   * already down. A monitor that starts healthy, starts inside a maintenance window, or starts
   * mid-retry is recorded and not announced.
   */
  public static boolean passesFirstBeatGate(boolean isFirstBeat, Status current) {
    return !isFirstBeat || current == Status.DOWN;
  }
}
