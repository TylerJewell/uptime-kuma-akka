package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every kind of check, by the type string that selects it.
 *
 * <p>Also the place the contract is enforced: a check that quietly returned something other than UP
 * without being one of the two types allowed to would skip the retry counter, so that is refused
 * here rather than being trusted.
 */
public final class Checks {

  private Checks() {}

  private static final Map<String, Check> BY_TYPE = build();

  private static Map<String, Check> build() {
    List<Check> all = new ArrayList<>();
    all.add(new HttpFamilyCheck("http"));
    all.add(new HttpFamilyCheck("keyword"));
    all.add(new HttpFamilyCheck("json-query"));
    all.add(new GrpcCheck());
    all.addAll(SimpleChecks.all());
    all.addAll(DatabaseChecks.all());
    all.addAll(NetworkChecks.all());
    all.addAll(ExternalChecks.all());

    Map<String, Check> byType = new LinkedHashMap<>();
    for (Check check : all) {
      if (byType.put(check.type(), check) != null) {
        throw new IllegalStateException("Duplicate monitor type " + check.type());
      }
    }
    return byType;
  }

  public static List<String> types() {
    return List.copyOf(BY_TYPE.keySet());
  }

  public static Check byType(String type) {
    return BY_TYPE.get(type);
  }

  /**
   * Run one check.
   *
   * @return what the beat should record, or null when there is nothing to record — which only the
   *     push type produces, and only while it is still inside its window
   */
  public static CheckOutcome run(MonitorConfig config, CheckContext context) {
    Check check = BY_TYPE.get(config.type());
    if (check == null) {
      return CheckOutcome.failed("Unknown Monitor Type");
    }
    try {
      CheckOutcome outcome = check.check(config, context);
      if (outcome == null) {
        return null;
      }
      if (!check.allowCustomStatus() && outcome.status() != Status.UP) {
        return CheckOutcome.failed(
            "The monitor implementation is incorrect, non-UP error must throw error inside check()");
      }
      return outcome;
    } catch (CheckFailed e) {
      return CheckOutcome.failed(e.getMessage(), e.ping(), e.response());
    } catch (Exception e) {
      // Anything a check did not expect is still a failed beat rather than a crash of the loop.
      return CheckOutcome.failed(DatabaseChecks.rootMessage(e));
    }
  }

  /**
   * How long until a push monitor's window closes.
   *
   * <p>Reached from the loop rather than from a check, because it is the answer to "when should I
   * look again" rather than to "is it healthy" — the only type whose next beat is not a function of
   * its interval alone.
   */
  public static long pushWindowRemainingMillis(MonitorConfig config, CheckContext context) {
    return ExternalChecks.PushCheck.remainingMillis(config, context);
  }

  /**
   * The shape the interface reads to build its condition editors: which types support conditions
   * and, for each, which variables and operators.
   */
  public static Map<String, Object> typeList() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Check> entry : BY_TYPE.entrySet()) {
      // The interface asks only about the types that have a class of their own in the source;
      // the four handled inline there are not in its list and are not in this one.
      if (!MonitorConfig.TYPES.contains(entry.getKey())) {
        continue;
      }
      if (List.of("http", "keyword", "json-query", "ping", "push", "docker", "radius",
              "kafka-producer")
          .contains(entry.getKey())) {
        continue;
      }
      Map<String, Object> details = new LinkedHashMap<>();
      boolean supports = entry.getValue().supportsConditions();
      details.put("supportsConditions", supports);
      List<Object> variables = new ArrayList<>();
      if (supports) {
        for (String variable : io.akka.uptimekuma.domain.Conditions.variablesFor(entry.getKey())) {
          List<Object> operators = new ArrayList<>();
          for (String operator :
              io.akka.uptimekuma.domain.Conditions.operatorsFor(entry.getKey(), variable)) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("id", operator);
            rendered.put("caption", io.akka.uptimekuma.domain.Conditions.caption(operator));
            operators.add(rendered);
          }
          Map<String, Object> rendered = new LinkedHashMap<>();
          rendered.put("id", variable);
          rendered.put("operators", operators);
          variables.add(rendered);
        }
      }
      details.put("conditionVariables", variables);
      out.put(entry.getKey(), details);
    }
    return out;
  }
}
