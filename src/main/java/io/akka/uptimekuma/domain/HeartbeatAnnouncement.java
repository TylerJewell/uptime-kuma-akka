package io.akka.uptimekuma.domain;

import java.util.Optional;

/**
 * One heartbeat, durable and addressable by monitor and position — the shape a GUI client
 * subscribes to. Mirrors {@link Heartbeat} plus the monitor it belongs to, because an
 * announcement is read out of context of any single monitor's entity.
 *
 * <p>{@code pingMillis} is an {@link Optional} rather than a nullable {@code Long} because this
 * record is a view row: a view infers a non-optional {@code long} column from {@code Long}, and
 * every beat that did not complete its check — which is every beat an outage produces — is
 * then refused by the view rather than stored.
 */
public record HeartbeatAnnouncement(
    String monitorId,
    long sequence,
    Status status,
    boolean important,
    boolean notified,
    int retries,
    int downCount,
    String message,
    Optional<Long> pingMillis,
    long atEpochMillis) {}
