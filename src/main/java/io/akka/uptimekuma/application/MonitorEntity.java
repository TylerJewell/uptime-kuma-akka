package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.uptimekuma.domain.BeatDecision;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * A monitor's durable state: what it is configured to check, whether it is running, and the
 * heartbeats it has produced.
 *
 * <p>The retry counter and the re-send counter live here rather than in the loop that beats,
 * because a monitor that restarts mid-outage has to carry both across the restart — uptime-kuma
 * reads its last heartbeat back out of the database for exactly this reason.
 */
@Component(id = "monitor")
public class MonitorEntity extends EventSourcedEntity<MonitorEntity.State, MonitorEntity.Event> {

  /** How many heartbeats one monitor keeps. Older ones fall off the front. */
  static final int HISTORY_LIMIT = 200;

  public record State(
      MonitorConfig config,
      boolean active,
      boolean underMaintenance,
      List<Heartbeat> history,
      boolean created) {

    public Heartbeat last() {
      return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public long nextSequence() {
      var last = last();
      return last == null ? 1 : last.sequence() + 1;
    }

    State withConfig(MonitorConfig replacement) {
      return new State(replacement, active, underMaintenance, history, created);
    }

    State withActive(boolean running) {
      return new State(config, running, underMaintenance, history, created);
    }

    State withMaintenance(boolean under) {
      return new State(config, active, under, history, created);
    }

    State withHeartbeat(Heartbeat heartbeat) {
      var next = new ArrayList<>(history);
      next.add(heartbeat);
      if (next.size() > HISTORY_LIMIT) {
        next.subList(0, next.size() - HISTORY_LIMIT).clear();
      }
      return new State(config, active, underMaintenance, List.copyOf(next), created);
    }
  }

  public sealed interface Event {
    @TypeName("created")
    record Created(MonitorConfig config) implements Event {}

    @TypeName("reconfigured")
    record Reconfigured(MonitorConfig config) implements Event {}

    @TypeName("started")
    record Started() implements Event {}

    @TypeName("stopped")
    record Stopped() implements Event {}

    @TypeName("maintenance-set")
    record MaintenanceSet(boolean under) implements Event {}

    @TypeName("beat-recorded")
    record BeatRecorded(Heartbeat heartbeat) implements Event {}
  }

  /**
   * A beat asking to be recorded.
   *
   * @param expectedSequence the sequence number this beat believes it is writing. A timer is
   *     delivered at least once, so a beat can arrive twice; the second one carries a sequence the
   *     monitor has already recorded and is answered with what was recorded rather than appended.
   *     uptime-kuma's loop cannot overlap and so has no rule here — SPEC-001 §4 OD2
   */
  public record RecordBeat(
      BeatDecision.CheckOutcome check, long expectedSequence, long atEpochMillis) {}

  /**
   * @param duplicate whether this beat had already been recorded, in which case nothing was
   *     appended and {@code heartbeat} is what was recorded the first time
   */
  public record BeatResult(Heartbeat heartbeat, boolean send, boolean duplicate, boolean active) {}

  @Override
  public State emptyState() {
    return new State(null, false, false, List.of(), false);
  }

  public Effect<String> create(MonitorConfig config) {
    var refusal = config.validate();
    if (refusal.isPresent()) {
      return effects().error(refusal.get());
    }
    if (currentState().created()) {
      return effects().persist(new Event.Reconfigured(config)).thenReply(s -> "reconfigured");
    }
    return effects().persist(new Event.Created(config)).thenReply(s -> "created");
  }

  public Effect<String> start() {
    if (!currentState().created()) {
      return effects().error("no such monitor");
    }
    if (currentState().active()) {
      return effects().reply("already running");
    }
    return effects().persist(new Event.Started()).thenReply(s -> "started");
  }

  public Effect<String> stop() {
    if (!currentState().active()) {
      return effects().reply("already stopped");
    }
    return effects().persist(new Event.Stopped()).thenReply(s -> "stopped");
  }

  /**
   * Maintenance is an input to the port, not something it computes: uptime-kuma derives it from
   * window schedules attached to the monitor and to its parents, and those are out of scope.
   */
  public Effect<String> setMaintenance(Boolean under) {
    if (!currentState().created()) {
      return effects().error("no such monitor");
    }
    return effects()
        .persist(new Event.MaintenanceSet(under))
        .thenReply(s -> under ? "under maintenance" : "out of maintenance");
  }

  public Effect<BeatResult> recordBeat(RecordBeat command) {
    var state = currentState();
    if (!state.created()) {
      return effects().error("no such monitor");
    }
    if (command.expectedSequence() < state.nextSequence()) {
      var recorded =
          state.history().stream()
              .filter(h -> h.sequence() == command.expectedSequence())
              .findFirst()
              .orElse(state.last());
      return effects().reply(new BeatResult(recorded, false, true, state.active()));
    }

    var outcome =
        BeatDecision.decide(state.config(), state.last(), command.check(), command.atEpochMillis());
    return effects()
        .persist(new Event.BeatRecorded(outcome.heartbeat()))
        .thenReply(
            s -> new BeatResult(outcome.heartbeat(), outcome.send(), false, s.active()));
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  /**
   * What one beat needs to know before it checks anything.
   *
   * <p>Separate from {@link #get()} because a beat needs four fields and {@link State} carries the
   * monitor's whole retained history — up to {@link #HISTORY_LIMIT} heartbeats, serialised across
   * the wire once per beat of every monitor, to read a URL and two flags.
   */
  public record BeatContext(
      MonitorConfig config, boolean created, boolean active, boolean underMaintenance, long nextSequence) {}

  public ReadOnlyEffect<BeatContext> beatContext() {
    var state = currentState();
    return effects()
        .reply(
            new BeatContext(
                state.config(),
                state.created(),
                state.active(),
                state.underMaintenance(),
                state.nextSequence()));
  }

  @Override
  public State applyEvent(Event event) {
    var state = currentState();
    return switch (event) {
      case Event.Created e -> new State(e.config(), false, false, List.of(), true);
      case Event.Reconfigured e -> state.withConfig(e.config());
      case Event.Started ignored -> state.withActive(true);
      case Event.Stopped ignored -> state.withActive(false);
      case Event.MaintenanceSet e -> state.withMaintenance(e.under());
      case Event.BeatRecorded e -> state.withHeartbeat(e.heartbeat());
    };
  }
}
