package io.akka.uptimekuma.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The smaller rules: status codes, statuses, validation and the timeout patch. */
class ContractTest {

  @Test
  void aSingleCodeMatchesOnlyItself() {
    assertTrue(AcceptedStatusCodes.matches(200, List.of("200")));
    assertFalse(AcceptedStatusCodes.matches(201, List.of("200")));
  }

  @Test
  void aRangeIsInclusiveAtBothEnds() {
    assertTrue(AcceptedStatusCodes.matches(200, List.of("200-299")));
    assertTrue(AcceptedStatusCodes.matches(299, List.of("200-299")));
    assertTrue(AcceptedStatusCodes.matches(250, List.of("200-299")));
    assertFalse(AcceptedStatusCodes.matches(300, List.of("200-299")));
    assertFalse(AcceptedStatusCodes.matches(199, List.of("200-299")));
  }

  @Test
  void anEmptyOrAbsentListMatchesNothing() {
    // A monitor with no accepted codes rejects every response, which is the source's answer and
    // not an oversight.
    assertFalse(AcceptedStatusCodes.matches(200, List.of()));
    assertFalse(AcceptedStatusCodes.matches(200, null));
  }

  @Test
  void anUnusableEntryIsSkippedRatherThanRefusingTheWholeList() {
    assertTrue(AcceptedStatusCodes.matches(200, List.of("1-2-3", "200-299")));
    assertTrue(AcceptedStatusCodes.matches(200, List.of("nonsense", "200")));
    assertFalse(AcceptedStatusCodes.matches(400, List.of("1-2-3", "nonsense")));
  }

  @Test
  void aCodeWithTrailingCharactersReadsAsItsLeadingDigits() {
    // The source parses each half by reading the leading digits and ignoring the rest, so a list
    // carried over from it behaves the same way here.
    assertTrue(AcceptedStatusCodes.matches(200, List.of("200x")));
  }

  @Test
  void severalRangesAreTriedInTurn() {
    assertTrue(AcceptedStatusCodes.matches(404, List.of("200-299", "400-499")));
    assertFalse(AcceptedStatusCodes.matches(500, List.of("200-299", "400-499")));
  }

  @Test
  void statusCodesAreTheOnesTheInterfaceReads() {
    assertEquals(0, Status.DOWN.code());
    assertEquals(1, Status.UP.code());
    assertEquals(2, Status.PENDING.code());
    assertEquals(3, Status.MAINTENANCE.code());
    assertEquals(Status.UP, Status.of(1));
  }

  @Test
  void flippingSwapsUpAndDownAndLeavesTheOtherTwoAlone() {
    assertEquals(Status.DOWN, Status.UP.flip());
    assertEquals(Status.UP, Status.DOWN.flip());
    // Unchanged, which is what lets an upside-down monitor still be pending.
    assertEquals(Status.PENDING, Status.PENDING.flip());
    assertEquals(Status.MAINTENANCE, Status.MAINTENANCE.flip());
  }

  @Test
  void maintenanceCountsAsUpForTheRatioAndPendingDoesNot() {
    assertTrue(Status.UP.countsAsUp());
    assertTrue(Status.MAINTENANCE.countsAsUp());
    assertFalse(Status.DOWN.countsAsUp());
    assertFalse(Status.PENDING.countsAsUp());
  }

  /**
   * A type nothing can run is stored, and the beat that tries to run it is what says so.
   *
   * <p>Refusing it here would be an improvement rather than a copy: the source's own `validate`
   * never looks at the type, so a monitor of type `smoke` is added successfully and every beat it
   * takes records `Unknown Monitor Type` as its message. Found by running the two side by side.
   */
  @Test
  void aMonitorWithAnUnknownTypeIsStoredAndFailsWhenItBeats() {
    MonitorConfig unknown = MonitorConfig.blank("m").toBuilder().type("smoke").build();
    assertNull(unknown.validate());
    assertEquals(
        "Unknown Monitor Type",
        io.akka.uptimekuma.checks.Checks.run(
                unknown, io.akka.uptimekuma.checks.CheckContext.plain(System.currentTimeMillis()))
            .msg());
  }

  @Test
  void anIntervalBelowOneSecondIsRefused() {
    assertEquals(
        "Interval cannot be less than 1 seconds",
        MonitorConfig.blank("m").toBuilder().interval(0).build().validate());
    assertEquals(
        "Retry interval cannot be less than 1 seconds",
        MonitorConfig.blank("m").toBuilder().retryInterval(0).build().validate());
  }

  @Test
  void aWorkableMonitorIsAccepted() {
    assertNull(MonitorConfig.blank("m").toBuilder().url("https://example.com").build().validate());
  }

  @Test
  void aResponseLimitOutsideItsRangeIsRefused() {
    assertNotNull(MonitorConfig.blank("m").toBuilder().responseMaxLength(-1).build().validate());
    assertNotNull(
        MonitorConfig.blank("m")
            .toBuilder()
            .responseMaxLength(MonitorConfig.RESPONSE_BODY_LENGTH_MAX + 1)
            .build()
            .validate());
    assertNull(MonitorConfig.blank("m").toBuilder().responseMaxLength(0).build().validate());
  }

  @Test
  void aServiceMonitorNeedsAWorkableName() {
    assertEquals(
        "Service Name is required.",
        MonitorConfig.blank("m").toBuilder().type("system-service").build().validate());
    assertEquals(
        "PM2 process name is required.",
        MonitorConfig.blank("m").toBuilder().type("pm2").build().validate());
  }

  @Test
  void aMonitorWithNoTimeoutIsGivenEightTenthsOfItsInterval() {
    // Computed in milliseconds and then consumed as seconds, which is the source's own
    // arithmetic and is reproduced rather than corrected.
    MonitorConfig config = MonitorConfig.blank("m").toBuilder().interval(20).timeout(0).build();
    assertEquals(16000, config.effectiveTimeout());
    MonitorConfig given = MonitorConfig.blank("m").toBuilder().interval(20).timeout(5).build();
    assertEquals(5, given.effectiveTimeout());
  }

  @Test
  void aMonitorHandedToANotificationTargetCarriesNoSecrets() {
    MonitorConfig config =
        MonitorConfig.blank("m")
            .toBuilder()
            .url("https://example.com")
            .basicAuthUser("admin")
            .build();
    assertNull(config.withoutSensitiveData().basic_auth_user());
    assertFalse(config.withoutSensitiveData().includeSensitiveData());
    assertEquals("https://example.com", config.withoutSensitiveData().url());
  }

  @Test
  void thirtyThreeTypesAreDeclared() {
    assertEquals(33, MonitorConfig.TYPES.size());
    assertEquals(5, MonitorConfig.SUPPORTS_CONDITIONS.size());
    assertEquals(2, MonitorConfig.ALLOWS_CUSTOM_STATUS.size());
  }
}
