package io.akka.uptimekuma.domain;

/**
 * What one beat does, as a function of what the monitor has already seen.
 *
 * <p>Everything in this class is pure. The check, the store, the clock and the notification
 * transport are all outside it, which is what makes a nine-beat outage something a unit test can
 * assert on rather than something that has to be waited for.
 */
public final class BeatDecision {

  private BeatDecision() {}

  /** What the check itself came back with. */
  public record CheckOutcome(
      boolean underMaintenance, boolean ok, String message, Long pingMillis) {

    public static CheckOutcome up(String message, long pingMillis) {
      return new CheckOutcome(false, true, message, pingMillis);
    }

    public static CheckOutcome failed(String message) {
      return new CheckOutcome(false, false, message, null);
    }

    public static CheckOutcome maintenance() {
      return new CheckOutcome(true, false, "Monitor under maintenance", null);
    }
  }

  /** The beat, and what the caller has to do about it. */
  public record Outcome(Heartbeat heartbeat, boolean send) {}

  /**
   * Derive this beat's heartbeat from the previous one.
   *
   * @param previous the monitor's last heartbeat, or null if it has never beaten
   */
  public static Outcome decide(
      MonitorConfig config, Heartbeat previous, CheckOutcome check, long atEpochMillis) {

    boolean isFirstBeat = previous == null;
    Status previousStatus = isFirstBeat ? null : previous.status();
    int retries = isFirstBeat ? 0 : previous.retries();
    int downCount = isFirstBeat ? 0 : previous.downCount();
    long sequence = isFirstBeat ? 1 : previous.sequence() + 1;

    Status status;
    if (check.underMaintenance()) {
      status = Status.MAINTENANCE;
      // A maintenance beat clears the retry counter, exactly as a successful check does. In
      // the source, maintenance short-circuits ahead of the check and the beat leaves the
      // try block normally, which is the same path a successful check leaves by — so a
      // monitor mid-retry whose window opens comes out of it with a fresh allowance.
      retries = 0;
    } else if (check.ok()) {
      status = Status.UP;
      retries = 0;
    } else if (config.maxRetries() > 0 && retries < config.maxRetries()) {
      retries++;
      status = Status.PENDING;
    } else {
      // Past the allowance the counter keeps climbing; the source reports it in its
      // failing log line and it is what a restart mid-outage resumes from.
      retries++;
      status = Status.DOWN;
    }

    boolean important = TransitionRules.isImportant(previousStatus, status);
    boolean send;
    if (important) {
      downCount = 0;
      send =
          TransitionRules.notifies(previousStatus, status)
              && TransitionRules.passesFirstBeatGate(isFirstBeat, status);
    } else if (status == Status.DOWN && config.resendIntervalSeconds() > 0) {
      downCount++;
      send = downCount >= config.resendIntervalSeconds();
      if (send) {
        downCount = 0;
      }
    } else {
      send = false;
    }

    var heartbeat =
        new Heartbeat(
            sequence,
            status,
            important,
            send,
            retries,
            downCount,
            check.message(),
            check.pingMillis(),
            atEpochMillis);
    return new Outcome(heartbeat, send);
  }

  /**
   * How long until the next beat.
   *
   * <p>The retry interval is substituted only for a PENDING beat, and only when it is set — a
   * monitor that is already DOWN goes back to its ordinary cadence. Whatever the beat itself cost
   * is taken off, so the cadence is the gap between beats rather than the gap between the end of
   * one and the start of the next; a beat that overran leaves one millisecond rather than a
   * negative delay.
   */
  public static long nextDelayMillis(MonitorConfig config, Status status, long beatDurationMillis) {
    int seconds = config.intervalSeconds();
    if (status == Status.PENDING && config.retryIntervalSeconds() > 0) {
      seconds = config.retryIntervalSeconds();
    }
    return Math.max(1L, seconds * 1000L - beatDurationMillis);
  }
}
