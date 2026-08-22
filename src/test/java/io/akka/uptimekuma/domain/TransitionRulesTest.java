package io.akka.uptimekuma.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 R10–R13, enumerated rather than sampled.
 *
 * <p>The class is small enough to write out in full — four statuses squared plus the four
 * first-beat cases — and writing it out is what the source's own probe did (question-log rows 1–3).
 * Three of the twenty are the reason: entering a maintenance window from UP or DOWN is important
 * and silent, leaving one upward is important and silent, and entering one from PENDING is neither.
 * A test over three transitions agrees with a rule that has any of those backwards.
 */
class TransitionRulesTest {

  private static final List<Status> STATUSES =
      List.of(Status.UP, Status.DOWN, Status.PENDING, Status.MAINTENANCE);

  private record Transition(boolean isFirstBeat, Status previous, Status current) {
    String label() {
      return (isFirstBeat ? "first" : previous.name()) + "->" + current.name();
    }
  }

  private static List<Transition> everyTransition() {
    var all = new ArrayList<Transition>();
    for (Status current : STATUSES) {
      all.add(new Transition(true, null, current));
    }
    for (Status previous : STATUSES) {
      for (Status current : STATUSES) {
        all.add(new Transition(false, previous, current));
      }
    }
    return all;
  }

  @Test
  void elevenOfTheTwentyTransitionsAreImportant() {
    var transitions = everyTransition();
    assertThat(transitions).hasSize(20);

    var important =
        transitions.stream()
            .filter(t -> TransitionRules.isImportant(t.previous(), t.current()))
            .map(Transition::label)
            .sorted()
            .toList();

    assertThat(important)
        .containsExactly(
            "DOWN->MAINTENANCE",
            "DOWN->UP",
            "MAINTENANCE->DOWN",
            "MAINTENANCE->UP",
            "PENDING->DOWN",
            "UP->DOWN",
            "UP->MAINTENANCE",
            "first->DOWN",
            "first->MAINTENANCE",
            "first->PENDING",
            "first->UP");
  }

  @Test
  void eightOfTheTwentyNotify() {
    var notifying =
        everyTransition().stream()
            .filter(t -> TransitionRules.notifies(t.previous(), t.current()))
            .map(Transition::label)
            .sorted()
            .toList();

    assertThat(notifying)
        .containsExactly(
            "DOWN->UP",
            "MAINTENANCE->DOWN",
            "PENDING->DOWN",
            "UP->DOWN",
            "first->DOWN",
            "first->MAINTENANCE",
            "first->PENDING",
            "first->UP");
  }

  @Test
  void exactlyThreeTransitionsAreImportantAndSilent() {
    var gap =
        everyTransition().stream()
            .filter(t -> TransitionRules.isImportant(t.previous(), t.current()))
            .filter(t -> !TransitionRules.notifies(t.previous(), t.current()))
            .map(Transition::label)
            .sorted()
            .toList();

    assertThat(gap).containsExactly("DOWN->MAINTENANCE", "MAINTENANCE->UP", "UP->MAINTENANCE");
  }

  @Test
  void nothingNotifiesThatIsNotAlsoImportant() {
    assertThat(
            everyTransition().stream()
                .filter(t -> TransitionRules.notifies(t.previous(), t.current()))
                .filter(t -> !TransitionRules.isImportant(t.previous(), t.current()))
                .toList())
        .isEmpty();
  }

  @Test
  void enteringTheRetryWindowAndLeavingItAreBothQuiet() {
    assertThat(TransitionRules.isImportant(Status.UP, Status.PENDING)).isFalse();
    assertThat(TransitionRules.isImportant(Status.PENDING, Status.UP)).isFalse();
    assertThat(TransitionRules.isImportant(Status.DOWN, Status.PENDING)).isFalse();
    assertThat(TransitionRules.isImportant(Status.PENDING, Status.MAINTENANCE)).isFalse();
  }

  @Test
  void firstBeatGateSendsOnlyWhenTheFirstBeatIsDown() {
    var outcomes = new LinkedHashMap<String, Boolean>();
    for (boolean isFirstBeat : List.of(true, false)) {
      for (Status status : STATUSES) {
        outcomes.put(
            (isFirstBeat ? "first" : "later") + "/" + status,
            TransitionRules.passesFirstBeatGate(isFirstBeat, status));
      }
    }

    assertThat(outcomes)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "first/UP", false,
                "first/DOWN", true,
                "first/PENDING", false,
                "first/MAINTENANCE", false,
                "later/UP", true,
                "later/DOWN", true,
                "later/PENDING", true,
                "later/MAINTENANCE", true));
  }
}
