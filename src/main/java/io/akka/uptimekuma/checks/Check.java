package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;

/**
 * One kind of thing a monitor can check.
 *
 * <p>The contract is the source's: a check either succeeds, in which case the beat is up, or it
 * raises, in which case the beat's message is what it raised. Two types are allowed to name their
 * own status instead — a group takes its children's, and a manual monitor takes whatever a person
 * set — and those are the only two, because a check that quietly returned a non-up status without
 * raising would skip the retry counter entirely.
 */
public interface Check {

  /** The {@code type} string a monitor carries to select this check. */
  String type();

  /** Whether a stored condition tree is read by this check. */
  default boolean supportsConditions() {
    return false;
  }

  /** Whether this check may return a status other than UP without raising. */
  default boolean allowCustomStatus() {
    return false;
  }

  /**
   * Run the check.
   *
   * @throws CheckFailed when the thing being checked is not healthy. The message is what the beat
   *     records and what a notification quotes, so it is part of the observable behaviour.
   */
  CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed;
}
