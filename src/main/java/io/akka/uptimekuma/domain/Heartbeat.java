package io.akka.uptimekuma.domain;

/**
 * One beat of one monitor.
 *
 * <p>Field for field the source's {@code heartbeat} row, with two additions. {@code sequence} is
 * this port's own: a monitor's beats are numbered so a client that lost its connection can say
 * where to resume from, which an auto-incrementing row id shared across every monitor cannot
 * answer. {@code endTime} is the source's {@code end_time}, the instant the uptime calculator
 * folded this beat into its buckets.
 *
 * @param downCount how many consecutive down beats have gone by without a re-send. Reset by an
 *     important beat and by the re-send itself.
 * @param retries how many attempts this monitor has made since it was last up. Carried on the beat
 *     because a monitor that restarts reads it back off its last beat.
 * @param response the body the check saved, when the monitor asked for one. Null otherwise.
 */
public record Heartbeat(
    long sequence,
    String monitorId,
    boolean important,
    int status,
    String msg,
    long timeEpochMillis,
    Double ping,
    int duration,
    int downCount,
    int retries,
    Long endTimeEpochMillis,
    String response) {

  public Status statusEnum() {
    return Status.of(status);
  }

  /** The same beat with nothing to say about it — what clearing a monitor's events leaves. R75. */
  public Heartbeat cleared() {
    return new Heartbeat(
        sequence, monitorId, false, status, "", timeEpochMillis, ping, duration, downCount,
        retries, endTimeEpochMillis, response);
  }

  public static Heartbeat initial(String monitorId) {
    return new Heartbeat(0, monitorId, false, Status.DOWN.code(), "", 0L, null, 0, 0, 0, null, null);
  }
}
