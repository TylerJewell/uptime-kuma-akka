package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.uptimekuma.domain.BeatDecision;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import io.akka.uptimekuma.domain.UptimeCalculator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A monitor's durable state: what it checks, whether it is running, what it has produced.
 *
 * <p>The retry counter and the re-send counter live on the last heartbeat rather than beside it,
 * because a monitor that restarts mid-outage has to carry both across the restart and the source
 * reads them back off the last row for exactly that reason.
 *
 * <p>The uptime buckets are held here too. They could be their own entity, but every beat writes a
 * heartbeat and updates the buckets in the same breath, and splitting them would make that pair of
 * writes something a reader could catch half-done.
 */
@Component(id = "monitor")
public class MonitorEntity extends EventSourcedEntity<MonitorEntity.State, MonitorEntity.Event> {

  /** How many heartbeats one monitor keeps. The interface draws a hundred and fifty. */
  static final int HISTORY_LIMIT = 500;

  /**
   * @param importantHistory the beats that marked a change, kept separately because they are what
   *     the events table pages through and they survive far longer than the rolling window
   * @param certNotifiedDays the day thresholds a certificate warning has already been sent for, so
   *     the same warning is not sent again every beat
   */
  public record State(
      MonitorConfig config,
      boolean created,
      boolean active,
      boolean underMaintenance,
      List<Heartbeat> history,
      List<Heartbeat> importantHistory,
      UptimeCalculator stats,
      Map<String, Object> tlsInfo,
      Map<String, Object> domainInfo,
      Set<Integer> certNotifiedDays,
      long beatsEverTaken) {

    public Heartbeat last() {
      return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    /**
     * The number the next beat will carry.
     *
     * <p>Counted rather than read off the last beat, because the history is a bounded window and is
     * emptied outright when a caller clears it — so a number derived from it starts again at one,
     * and the beat that follows a clear lands on a number a beat already had. The source's own
     * heartbeat identifiers come from a table's autoincrement and never restart either.
     */
    public long nextSequence() {
      return beatsEverTaken + 1;
    }

    State withHeartbeat(Heartbeat heartbeat) {
      List<Heartbeat> next = new ArrayList<>(history);
      next.add(heartbeat);
      if (next.size() > HISTORY_LIMIT) {
        next.subList(0, next.size() - HISTORY_LIMIT).clear();
      }
      List<Heartbeat> important = importantHistory;
      if (heartbeat.important()) {
        important = new ArrayList<>(importantHistory);
        important.add(heartbeat);
        if (important.size() > HISTORY_LIMIT) {
          important.subList(0, important.size() - HISTORY_LIMIT).clear();
        }
      }
      Status status = heartbeat.statusEnum();
      UptimeCalculator updated =
          stats.update(status, heartbeat.ping(), heartbeat.timeEpochMillis());
      return new State(
          config,
          created,
          active,
          underMaintenance,
          List.copyOf(next),
          List.copyOf(important),
          updated,
          tlsInfo,
          domainInfo,
          certNotifiedDays,
          Math.max(beatsEverTaken, heartbeat.sequence()));
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

    @TypeName("events-cleared")
    record EventsCleared() implements Event {}

    @TypeName("heartbeats-cleared")
    record HeartbeatsCleared() implements Event {}

    @TypeName("tls-info")
    record TlsInfoRecorded(Map<String, Object> info, boolean fingerprintChanged) implements Event {}

    @TypeName("domain-info")
    record DomainInfoRecorded(Map<String, Object> info) implements Event {}

    @TypeName("cert-notified")
    record CertNotified(int days) implements Event {}

    @TypeName("deleted")
    record Deleted() implements Event {}
  }

  /**
   * A beat asking to be recorded.
   *
   * @param expectedSequence the sequence number this beat believes it is writing. A timer is
   *     delivered at least once, so a beat can arrive twice; the second one carries a sequence the
   *     monitor has already recorded and is answered with what was recorded rather than appended.
   */
  /**
   * @param arrivedByPush whether this beat came in through the push route rather than from the
   *     monitor's own timer. The two differ in one field: the gap a first beat reports. R13.
   */
  public record RecordBeat(
      BeatDecision.CheckOutcome check,
      long expectedSequence,
      long atEpochMillis,
      boolean arrivedByPush) {}

  /**
   * @param duplicate whether this beat had already been recorded, in which case nothing was
   *     appended and {@code heartbeat} is what was recorded the first time
   */
  public record BeatResult(
      Heartbeat heartbeat,
      boolean sendNotification,
      boolean duplicate,
      boolean active,
      int nextIntervalSeconds) {}

  /**
   * What one beat needs before it checks anything.
   *
   * <p>Separate from the whole state because a beat needs a handful of fields and the state carries
   * up to five hundred heartbeats and a year of buckets, serialised across the wire once per beat
   * of every monitor.
   */
  public record BeatContext(
      MonitorConfig config,
      boolean created,
      boolean active,
      boolean underMaintenance,
      long nextSequence,
      Heartbeat previousBeat) {}

  @Override
  public State emptyState() {
    return new State(
        null,
        false,
        false,
        false,
        List.of(),
        List.of(),
        UptimeCalculator.empty(),
        Map.of(),
        Map.of(),
        Set.of(),
        0);
  }

  public Effect<String> create(MonitorConfig config) {
    String refusal = config.validate();
    if (refusal != null) {
      return effects().error(refusal);
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

  public Effect<String> setMaintenance(Boolean under) {
    if (!currentState().created()) {
      return effects().error("no such monitor");
    }
    if (currentState().underMaintenance() == under) {
      return effects().reply("unchanged");
    }
    return effects()
        .persist(new Event.MaintenanceSet(under))
        .thenReply(s -> under ? "under maintenance" : "out of maintenance");
  }

  public Effect<BeatResult> recordBeat(RecordBeat command) {
    State state = currentState();
    if (!state.created()) {
      return effects().error("no such monitor");
    }
    if (command.expectedSequence() < state.nextSequence()) {
      Heartbeat recorded =
          state.history().stream()
              .filter(h -> h.sequence() == command.expectedSequence())
              .findFirst()
              .orElse(state.last());
      return effects()
          .reply(
              new BeatResult(recorded, false, true, state.active(), state.config().interval()));
    }
    BeatDecision.Outcome decided =
        BeatDecision.decide(
            state.config(),
            state.last(),
            command.check(),
            command.atEpochMillis(),
            state.nextSequence());
    BeatDecision.Outcome outcome =
        command.arrivedByPush()
            ? decided.withDuration(
                BeatDecision.pushDuration(state.last(), command.atEpochMillis()))
            : decided;
    return effects()
        .persist(new Event.BeatRecorded(outcome.heartbeat()))
        .thenReply(
            s ->
                new BeatResult(
                    outcome.heartbeat(),
                    outcome.sendNotification(),
                    false,
                    s.active(),
                    outcome.nextIntervalSeconds()));
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  public ReadOnlyEffect<BeatContext> beatContext() {
    State state = currentState();
    return effects()
        .reply(
            new BeatContext(
                state.config(),
                state.created(),
                state.active(),
                state.underMaintenance(),
                state.nextSequence(),
                state.last()));
  }

  /** Blank the message and the importance of every beat, which is what clearing events means. */
  public Effect<String> clearEvents() {
    return effects().persist(new Event.EventsCleared()).thenReply(s -> "cleared");
  }

  /** Drop every beat and every statistic, leaving the monitor configured but with no history. */
  public Effect<String> clearHeartbeats() {
    return effects().persist(new Event.HeartbeatsCleared()).thenReply(s -> "cleared");
  }

  public Effect<String> recordTlsInfo(Map<String, Object> info) {
    Object previous = currentState().tlsInfo().get("fingerprint256");
    Object current = info.get("fingerprint256");
    boolean changed = previous != null && !previous.equals(current);
    return effects()
        .persist(new Event.TlsInfoRecorded(info, changed))
        .thenReply(s -> "recorded");
  }

  public Effect<String> recordDomainInfo(Map<String, Object> info) {
    return effects().persist(new Event.DomainInfoRecorded(info)).thenReply(s -> "recorded");
  }

  public Effect<String> recordCertNotified(Integer days) {
    return effects().persist(new Event.CertNotified(days)).thenReply(s -> "recorded");
  }

  public Effect<String> delete() {
    return effects().persist(new Event.Deleted()).thenReply(s -> "deleted");
  }

  @Override
  public State applyEvent(Event event) {
    State state = currentState();
    return switch (event) {
      case Event.Created e ->
          new State(
              e.config(),
              true,
              e.config().active(),
              false,
              List.of(),
              List.of(),
              UptimeCalculator.empty(),
              Map.of(),
              Map.of(),
              Set.of(),
              state.beatsEverTaken());
      case Event.Reconfigured e ->
          new State(
              e.config(),
              true,
              state.active(),
              state.underMaintenance(),
              state.history(),
              state.importantHistory(),
              state.stats(),
              state.tlsInfo(),
              state.domainInfo(),
              state.certNotifiedDays(),
              state.beatsEverTaken());
      case Event.Started ignored -> copy(state, true, state.underMaintenance());
      case Event.Stopped ignored -> copy(state, false, state.underMaintenance());
      case Event.MaintenanceSet e -> copy(state, state.active(), e.under());
      case Event.BeatRecorded e -> state.withHeartbeat(e.heartbeat());
      case Event.EventsCleared ignored -> {
        List<Heartbeat> blanked = new ArrayList<>();
        for (Heartbeat beat : state.history()) {
          blanked.add(
              new Heartbeat(
                  beat.sequence(),
                  beat.monitorId(),
                  false,
                  beat.status(),
                  "",
                  beat.timeEpochMillis(),
                  beat.ping(),
                  beat.duration(),
                  beat.downCount(),
                  beat.retries(),
                  beat.endTimeEpochMillis(),
                  beat.response()));
        }
        yield new State(
            state.config(),
            state.created(),
            state.active(),
            state.underMaintenance(),
            List.copyOf(blanked),
            List.of(),
            state.stats(),
            state.tlsInfo(),
            state.domainInfo(),
            state.certNotifiedDays(),
            state.beatsEverTaken());
      }
      case Event.HeartbeatsCleared ignored ->
          new State(
              state.config(),
              state.created(),
              state.active(),
              state.underMaintenance(),
              List.of(),
              List.of(),
              UptimeCalculator.empty(),
              state.tlsInfo(),
              state.domainInfo(),
              state.certNotifiedDays(),
              state.beatsEverTaken());
      case Event.TlsInfoRecorded e ->
          new State(
              state.config(),
              state.created(),
              state.active(),
              state.underMaintenance(),
              state.history(),
              state.importantHistory(),
              state.stats(),
              e.info(),
              state.domainInfo(),
              // A renewed certificate re-arms every warning: the thresholds already passed were
              // about the certificate that has been replaced.
              e.fingerprintChanged() ? Set.of() : state.certNotifiedDays(),
              state.beatsEverTaken());
      case Event.DomainInfoRecorded e ->
          new State(
              state.config(),
              state.created(),
              state.active(),
              state.underMaintenance(),
              state.history(),
              state.importantHistory(),
              state.stats(),
              state.tlsInfo(),
              e.info(),
              state.certNotifiedDays(),
              state.beatsEverTaken());
      case Event.CertNotified e -> {
        Set<Integer> days = new LinkedHashSet<>(state.certNotifiedDays());
        days.add(e.days());
        yield new State(
            state.config(),
            state.created(),
            state.active(),
            state.underMaintenance(),
            state.history(),
            state.importantHistory(),
            state.stats(),
            state.tlsInfo(),
            state.domainInfo(),
            Set.copyOf(days),
            state.beatsEverTaken());
      }
      case Event.Deleted ignored -> emptyState();
    };
  }

  private static State copy(State state, boolean active, boolean underMaintenance) {
    MonitorConfig config =
        state.config() == null ? null : state.config().withActive(active).withMaintenance(underMaintenance);
    return new State(
        config,
        state.created(),
        active,
        underMaintenance,
        state.history(),
        state.importantHistory(),
        state.stats(),
        state.tlsInfo(),
        state.domainInfo(),
        state.certNotifiedDays(),
        state.beatsEverTaken());
  }

  /** The monitor as the interface reads it, with the fields the interface fills in from elsewhere. */
  public static Map<String, Object> toJson(State state, boolean includeSensitiveData) {
    MonitorConfig config =
        includeSensitiveData ? state.config() : state.config().withoutSensitiveData();
    Map<String, Object> json =
        io.akka.uptimekuma.notifications.Json.MAPPER.convertValue(config, LinkedHashMap.class);
    json.put("active", state.active());
    json.put("maintenance", state.underMaintenance());
    return json;
  }
}
