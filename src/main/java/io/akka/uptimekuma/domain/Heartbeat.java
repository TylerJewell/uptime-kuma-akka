package io.akka.uptimekuma.domain;

/**
 * One beat's result.
 *
 * @param retries consecutive failed checks. Keeps climbing after the monitor is already DOWN — it
 *     is not capped at the monitor's allowance, and the source's own log line reads the same
 * @param downCount unimportant DOWN beats since the last notification went out
 * @param important whether this beat is a state change worth recording
 * @param notified whether this beat's transition reached the fan-out
 */
public record Heartbeat(
    long sequence,
    Status status,
    boolean important,
    boolean notified,
    int retries,
    int downCount,
    String message,
    Long pingMillis,
    long atEpochMillis) {}
