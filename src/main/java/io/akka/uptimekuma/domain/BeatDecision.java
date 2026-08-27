package io.akka.uptimekuma.domain;

/**
 * What one beat becomes once the check has run.
 *
 * <p>This is the whole of {@code Monitor.beat()} minus the network: the check hands back an outcome
 * and everything after it — the upside-down flip, the retry counter, whether the beat is important,
 * whether anybody is told, and how long until the next one — is decided here, from the previous
 * beat and the configuration alone. Keeping it separate from the loop is what lets a sequence of
 * beats be replayed without a server.
 */
public final class BeatDecision {

  private BeatDecision() {}

  /**
   * What a check produced.
   *
   * @param ok whether the check succeeded. A check that throws is not ok; a check that returns
   *     having set its own status — the group and manual types — is.
   * @param status the status the check itself asked for. Only read when {@code ok} and the type
   *     allows a custom status; otherwise UP.
   * @param msg the message the check produced, or the error it threw
   * @param ping how long the check took, in milliseconds, or null when the check does not measure
   * @param response the body the check saved, when asked to
   */
  public record CheckOutcome(boolean ok, Status status, String msg, Double ping, String response) {

    public static CheckOutcome up(String msg, Double ping) {
      return new CheckOutcome(true, Status.UP, msg, ping, null);
    }

    public static CheckOutcome up(String msg, Double ping, String response) {
      return new CheckOutcome(true, Status.UP, msg, ping, response);
    }

    public static CheckOutcome custom(Status status, String msg, Double ping) {
      return new CheckOutcome(true, status, msg, ping, null);
    }

    public static CheckOutcome failed(String msg) {
      return new CheckOutcome(false, Status.DOWN, msg, null, null);
    }

    public static CheckOutcome failed(String msg, Double ping, String response) {
      return new CheckOutcome(false, Status.DOWN, msg, ping, response);
    }

    /**
     * A beat that never ran a check because the monitor is inside a maintenance window. Named
     * separately because it is not a check result at all — the source short-circuits before any
     * probe, so a maintenance beat has no ping and cannot fail.
     */
    public static CheckOutcome maintenance() {
      return new CheckOutcome(true, Status.MAINTENANCE, "Monitor under maintenance", null, null);
    }
  }

  /**
   * @param heartbeat the beat to record
   * @param sendNotification whether the notification list should be told about it
   * @param nextIntervalSeconds how long until the next beat is due, before the time this beat
   *     already took is subtracted
   */
  public record Outcome(Heartbeat heartbeat, boolean sendNotification, int nextIntervalSeconds) {

    /** The same outcome with the beat reporting a different gap. See {@link #pushDuration}. */
    public Outcome withDuration(int duration) {
      Heartbeat beat = heartbeat;
      return new Outcome(
          new Heartbeat(
              beat.sequence(), beat.monitorId(), beat.important(), beat.status(), beat.msg(),
              beat.timeEpochMillis(), beat.ping(), duration, beat.downCount(), beat.retries(),
              beat.endTimeEpochMillis(), beat.response()),
          sendNotification,
          nextIntervalSeconds);
    }
  }

  /** Whether a transition is one the source marks {@code important}. */
  public static boolean isImportantBeat(boolean isFirstBeat, Status previous, Status current) {
    if (isFirstBeat) {
      return true;
    }
    return (previous == Status.DOWN && current == Status.MAINTENANCE)
        || (previous == Status.UP && current == Status.MAINTENANCE)
        || (previous == Status.MAINTENANCE && current == Status.DOWN)
        || (previous == Status.MAINTENANCE && current == Status.UP)
        || (previous == Status.UP && current == Status.DOWN)
        || (previous == Status.DOWN && current == Status.UP)
        || (previous == Status.PENDING && current == Status.DOWN);
  }

  /**
   * Whether a transition is one anybody is told about.
   *
   * <p>Three of the important transitions are deliberately silent: going into maintenance from
   * either direction, and coming out of maintenance into UP. Coming out into DOWN is not silent,
   * because that is news.
   */
  public static boolean isImportantForNotification(
      boolean isFirstBeat, Status previous, Status current) {
    if (isFirstBeat) {
      return true;
    }
    return (previous == Status.MAINTENANCE && current == Status.DOWN)
        || (previous == Status.UP && current == Status.DOWN)
        || (previous == Status.DOWN && current == Status.UP)
        || (previous == Status.PENDING && current == Status.DOWN);
  }

  /**
   * Turn a check outcome into the beat that gets recorded.
   *
   * @param config the monitor
   * @param previous the last beat this monitor recorded, or null if it has never beaten
   * @param outcome what the check produced
   * @param nowEpochMillis the instant the beat is stamped with
   */
  public static Outcome decide(
      MonitorConfig config, Heartbeat previous, CheckOutcome outcome, long nowEpochMillis) {
    long sequence = previous == null ? 1 : previous.sequence() + 1;
    return decide(config, previous, outcome, nowEpochMillis, sequence);
  }

  /**
   * The same decision, told which number the beat is to carry.
   *
   * <p>The number cannot always be read off the previous beat. A monitor whose beats have been
   * cleared has no previous beat and is not on its first one, and each beat is published under a
   * key made from this number — so starting again at one puts the next beat on a key that the
   * clear has just deleted, which a key-value entity refuses for good. The monitor keeps its own
   * count of every beat it has taken and passes it here.
   */
  public static Outcome decide(
      MonitorConfig config,
      Heartbeat previous,
      CheckOutcome outcome,
      long nowEpochMillis,
      long sequence) {

    boolean isFirstBeat = previous == null;
    Status previousStatus = isFirstBeat ? null : previous.statusEnum();
    int retries = isFirstBeat ? 0 : previous.retries();
    int downCount = isFirstBeat ? 0 : previous.downCount();

    Status status;
    String msg;
    Double ping = outcome.ping();
    String response = outcome.response();

    if (outcome.status() == Status.MAINTENANCE) {
      // The maintenance branch runs before any check and before the upside-down flip is undone,
      // so nothing below can turn it into anything else.
      status = Status.MAINTENANCE;
      msg = outcome.msg();
      retries = 0;
    } else if (outcome.ok()) {
      Status reported = outcome.status();
      if (config.upsideDown()) {
        reported = reported.flip();
      }
      if (reported == Status.DOWN) {
        // An upside-down monitor whose check succeeded is down, and the source reaches that by
        // raising rather than by assignment — so the retry counter moves exactly as it would for
        // a check that threw, and a monitor with retries left goes pending first.
        Failure failure = applyFailure(config, retries, false);
        status = failure.status();
        retries = failure.retries();
        msg = "Flip UP to DOWN";
      } else {
        status = reported;
        msg = outcome.msg();
        retries = 0;
      }
    } else {
      msg = outcome.msg();
      if (config.upsideDown()) {
        // An upside-down monitor is seeded UP before the check runs, and a check that threw never
        // reaches the flip that would take it back down — so a failed check on an upside-down
        // monitor is an up beat, and an up beat does not retry. This branch is first in the
        // source's chain, which is why it beats the retry logic rather than being corrected by it.
        status = Status.UP;
        retries = 0;
      } else {
        boolean straightToDown =
            "json-query".equals(config.type())
                && config.retryOnlyOnStatusCodeFailure()
                && msg != null
                && msg.contains("JSON query does not pass");
        Failure failure = applyFailure(config, retries, straightToDown);
        status = failure.status();
        retries = failure.retries();
      }
    }

    boolean important = isImportantBeat(isFirstBeat, previousStatus, status);
    boolean notify = false;
    if (important) {
      downCount = 0;
      notify = isImportantForNotification(isFirstBeat, previousStatus, status);
      // The very first beat of a monitor only raises an alert if it is bad news. A monitor that
      // comes up for the first time is not an outage anybody needs telling about.
      if (isFirstBeat && status != Status.DOWN) {
        notify = false;
      }
    } else if (status == Status.DOWN && config.resendInterval() > 0) {
      downCount++;
      if (downCount >= config.resendInterval()) {
        notify = true;
        downCount = 0;
      }
    }

    int nextInterval = config.interval() <= 0 ? 1 : config.interval();
    if (status == Status.PENDING && config.retryInterval() > 0) {
      nextInterval = config.retryInterval();
    }

    Heartbeat beat =
        new Heartbeat(
            sequence,
            config.id(),
            important,
            status.code(),
            msg,
            nowEpochMillis,
            ping,
            durationFor(config, previous, nowEpochMillis),
            downCount,
            retries,
            nowEpochMillis,
            response);

    return new Outcome(beat, notify, nextInterval);
  }

  private record Failure(Status status, int retries) {}

  /**
   * The retry counter, and whether this failure is pending or down.
   *
   * <p>Once the counter has reached the configured maximum the status stays DOWN and the counter
   * keeps climbing — it is not clamped, and a monitor that has been down for an hour carries a
   * retry count in the hundreds. That number is read back off the last beat when a monitor
   * restarts, so clamping it would change what a restart resumes with.
   */
  private static Failure applyFailure(MonitorConfig config, int retries, boolean straightToDown) {
    if (straightToDown) {
      return new Failure(Status.DOWN, 0);
    }
    if (config.maxretries() > 0 && retries < config.maxretries()) {
      return new Failure(Status.PENDING, retries + 1);
    }
    return new Failure(Status.DOWN, retries + 1);
  }

  /**
   * The gap this beat records against the one before it.
   *
   * <p>Only the push type fills this in — every other type leaves the column at its default of
   * zero, because the source only ever assigns it on the push path.
   */
  private static int durationFor(MonitorConfig config, Heartbeat previous, long nowEpochMillis) {
    if (!"push".equals(config.type())) {
      return 0;
    }
    if (previous == null) {
      // Nothing has been heard from and nothing has been recorded, so the gap the beat reports is
      // the whole window it was waiting through. Reached only from the monitor's own timer: a beat
      // that arrives by push is recorded through the route below, which leaves the gap at zero
      // when there is nothing to measure against.
      return config.interval();
    }
    return (int) Math.round((nowEpochMillis - previous.timeEpochMillis()) / 1000.0);
  }

  /**
   * The gap a beat that arrived by push records.
   *
   * <p>Zero when it is the monitor's first, where the timer path reports the whole interval — the
   * source computes this on its own route and only when there is a previous beat to subtract.
   */
  public static int pushDuration(Heartbeat previous, long nowEpochMillis) {
    return previous == null
        ? 0
        : (int) Math.round((nowEpochMillis - previous.timeEpochMillis()) / 1000.0);
  }

  /**
   * How long to wait before the next beat.
   *
   * <p>Measured from the instant this beat started rather than from the instant it finished, so a
   * check that took four seconds on a twenty-second monitor waits sixteen and the cadence does not
   * drift. Never less than one millisecond: a check that overran its own interval beats again
   * straight away rather than being scheduled in the past.
   */
  public static long nextDelayMillis(int nextIntervalSeconds, long elapsedMillis) {
    return Math.max(1, nextIntervalSeconds * 1000L - elapsedMillis);
  }

  /**
   * The message the notification list is sent.
   *
   * <p>The emoji and the bracket layout are the source's own and are what a reader recognises, so
   * they are reproduced exactly rather than tidied.
   */
  public static String notificationText(String monitorName, Heartbeat beat) {
    String text = beat.statusEnum() == Status.UP ? "✅ Up" : "🔴 Down";
    return "[" + monitorName + "] [" + text + "] " + beat.msg();
  }
}
