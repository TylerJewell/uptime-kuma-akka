package io.akka.uptimekuma.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The six ways a planned outage can be scheduled, and what each says about now.
 *
 * <p>Every case names an instant, because the whole of what this decides is a comparison against
 * one — and a case that read the real clock would answer differently depending on when it ran.
 */
class MaintenanceWindowTest {

  private static final String ZONE = "UTC";

  private static MaintenanceWindow window(
      String strategy,
      boolean active,
      String startDate,
      String endDate,
      String startTime,
      String endTime,
      List<Integer> weekdays,
      List<String> daysOfMonth,
      Integer intervalDay,
      String cron,
      Integer duration) {
    return new MaintenanceWindow(
        "1",
        "Upgrade",
        "Rolling restart",
        strategy,
        active,
        startDate,
        endDate,
        startTime,
        endTime,
        weekdays,
        daysOfMonth,
        intervalDay,
        cron,
        duration,
        "SAME_AS_SERVER",
        null);
  }

  private static long at(String instant) {
    return Instant.parse(instant).toEpochMilli();
  }

  @Test
  void aWindowThatIsSwitchedOffIsInactiveWhateverItsSchedule() {
    MaintenanceWindow off =
        window("manual", false, null, null, null, null, null, null, null, null, null);
    assertEquals("inactive", off.status(ZONE, at("2026-01-02T03:04:05Z")));
    assertFalse(off.isUnderMaintenance(ZONE, at("2026-01-02T03:04:05Z")));
  }

  @Test
  void aManualWindowIsUnderMaintenanceForAsLongAsItIsOn() {
    MaintenanceWindow manual =
        window("manual", true, null, null, null, null, null, null, null, null, null);
    assertEquals("under-maintenance", manual.status(ZONE, at("2026-01-02T03:04:05Z")));
    assertEquals("under-maintenance", manual.status(ZONE, at("2030-01-01T00:00:00Z")));
  }

  @Test
  void aSingleWindowIsScheduledBeforeItAndEndedAfterIt() {
    MaintenanceWindow single =
        window(
            "single",
            true,
            "2026-01-02 02:00:00",
            "2026-01-02 04:00:00",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertEquals("scheduled", single.status(ZONE, at("2026-01-02T01:00:00Z")));
    assertEquals("under-maintenance", single.status(ZONE, at("2026-01-02T03:00:00Z")));
    assertEquals("ended", single.status(ZONE, at("2026-01-02T05:00:00Z")));
  }

  @Test
  void aWeekdayStrategyGeneratesTheCronPatternItImplies() {
    MaintenanceWindow weekly =
        window(
            "recurring-weekday",
            true,
            null,
            null,
            "02:30",
            "04:30",
            List.of(3, 1),
            null,
            null,
            null,
            null);
    // Sorted, because the pattern is compared as a string when a window is edited.
    assertEquals("30 2 * * 1,3", weekly.effectiveCron());
    assertEquals(2 * 3600, weekly.calculatedDuration());
  }

  @Test
  void aDayOfMonthStrategyGeneratesItsOwnPattern() {
    MaintenanceWindow monthly =
        window(
            "recurring-day-of-month",
            true,
            null,
            null,
            "01:00",
            "02:00",
            null,
            List.of("1", "15", "lastDay1"),
            null,
            null,
            null);
    assertEquals("0 1 1,15,L * *", monthly.effectiveCron());
  }

  @Test
  void aDayOfMonthStrategyDropsTheLastDaysCronCannotSpell() {
    MaintenanceWindow monthly =
        window(
            "recurring-day-of-month",
            true,
            null,
            null,
            "01:00",
            "02:00",
            null,
            List.of("1", "lastDay2", "lastDay3"),
            null,
            null,
            null);
    // Only the last day itself has a spelling; the second- and third-last do not.
    assertEquals("0 1 1 * *", monthly.effectiveCron());
  }

  @Test
  void anIntervalStrategyGeneratesADailyPatternWithTwoSpaces() {
    MaintenanceWindow interval =
        window(
            "recurring-interval", true, null, null, "05:15", "06:15", null, null, 3, null, null);
    // Two spaces between the hour and the first wildcard: that is what the source writes, and a
    // pattern is compared as a string.
    assertEquals("15 5  * * *", interval.effectiveCron());
  }

  @Test
  void aDurationCrossingMidnightWrapsRatherThanGoingNegative() {
    MaintenanceWindow overnight =
        window(
            "recurring-weekday",
            true,
            null,
            null,
            "23:00",
            "01:00",
            List.of(6),
            null,
            null,
            null,
            null);
    assertEquals(2 * 3600, overnight.calculatedDuration());
  }

  @Test
  void aCronWindowIsUnderMaintenanceInsideItsFiringAndScheduledOutsideIt() {
    // Every day at three, for an hour.
    MaintenanceWindow nightly =
        window("cron", true, null, null, null, null, null, null, null, "0 3 * * *", 3600);
    assertEquals("under-maintenance", nightly.status(ZONE, at("2026-01-02T03:30:00Z")));
    assertEquals("scheduled", nightly.status(ZONE, at("2026-01-02T05:00:00Z")));
    assertEquals("scheduled", nightly.status(ZONE, at("2026-01-02T02:59:00Z")));
  }

  @Test
  void aCronWindowRespectsTheDatesAroundIt() {
    MaintenanceWindow nightly =
        new MaintenanceWindow(
            "1",
            "Upgrade",
            "",
            "cron",
            true,
            "2026-02-01 00:00:00",
            "2026-03-01 00:00:00",
            null,
            null,
            null,
            null,
            null,
            "0 3 * * *",
            3600,
            "SAME_AS_SERVER",
            null);
    assertEquals("scheduled", nightly.status(ZONE, at("2026-01-15T03:30:00Z")));
    assertEquals("under-maintenance", nightly.status(ZONE, at("2026-02-15T03:30:00Z")));
    assertEquals("ended", nightly.status(ZONE, at("2026-03-15T03:30:00Z")));
  }

  @Test
  void aWindowWithNoWorkablePatternIsUnknownRatherThanOpen() {
    MaintenanceWindow broken =
        window("cron", true, null, null, null, null, null, null, null, "not a pattern", 3600);
    assertEquals("unknown", broken.status(ZONE, at("2026-01-02T03:30:00Z")));
    assertFalse(broken.isUnderMaintenance(ZONE, at("2026-01-02T03:30:00Z")));
  }

  @Test
  void aWindowNamingItsOwnTimezoneIsReadInIt() {
    MaintenanceWindow tokyo =
        new MaintenanceWindow(
            "1", "Upgrade", "", "cron", true, null, null, null, null, null, null, null,
            "0 3 * * *", 3600, "Asia/Tokyo", null);
    // Three in the morning in Tokyo is the previous evening in London.
    assertTrue(tokyo.isUnderMaintenance(ZONE, at("2026-01-01T18:30:00Z")));
    assertFalse(tokyo.isUnderMaintenance(ZONE, at("2026-01-01T03:30:00Z")));
  }

  @Test
  void theJsonAWindowIsListedByCarriesItsScheduleAndItsStatus() {
    MaintenanceWindow nightly =
        window("cron", true, null, null, "03:00", "04:00", null, null, null, "0 3 * * *", 3600);
    Map<String, Object> json = nightly.toJson(ZONE, at("2026-01-02T03:30:00Z"));
    assertEquals("cron", json.get("strategy"));
    assertEquals("under-maintenance", json.get("status"));
    assertEquals(3600, json.get("duration"));
    assertEquals(60, json.get("durationMinutes"));
    assertEquals("UTC", json.get("timezone"));
    assertEquals("+00:00", json.get("timezoneOffset"));
    // The interface reads the schedule as a list of slots, and a running window is the first.
    assertFalse(((List<?>) json.get("timeslotList")).isEmpty());
  }

  @Test
  void aManualWindowHasNoSlotsToShow() {
    MaintenanceWindow manual =
        window("manual", true, null, null, null, null, null, null, null, null, null);
    Map<String, Object> json = manual.toJson(ZONE, at("2026-01-02T03:30:00Z"));
    assertTrue(((List<?>) json.get("timeslotList")).isEmpty());
  }

  /**
   * The three complaints the source's own cron parser makes, on eight patterns.
   *
   * <p>Every string on the right was read off `croner`, the library the source hands the pattern
   * to, in the same session that produced this test. The rule is that the *write* is refused: the
   * port used to store a pattern nothing could read and report the window as `unknown` afterwards,
   * which looks like a window waiting to open. R102.
   */
  @Test
  void anUnreadableCronPatternIsRefusedInTheSourcesOwnWords() {
    assertNull(MaintenanceWindow.cronComplaint("0 4 * * *"));
    assertEquals(
        "CronPattern: invalid configuration format ('not a pattern'), exactly five or six space"
            + " separated parts are required.",
        MaintenanceWindow.cronComplaint("not a pattern"));
    assertEquals(
        "CronPattern: invalid configuration format ('* * * * * * *'), exactly five or six space"
            + " separated parts are required.",
        MaintenanceWindow.cronComplaint("* * * * * * *"));
    assertEquals(
        "CronPattern: invalid configuration format ('0 4 * *'), exactly five or six space separated"
            + " parts are required.",
        MaintenanceWindow.cronComplaint("0 4 * *"));
    assertEquals(
        "CronPattern: invalid configuration format (''), exactly five or six space separated parts"
            + " are required.",
        MaintenanceWindow.cronComplaint(""));
    assertEquals(
        "CronPattern: Invalid value for minute: 99", MaintenanceWindow.cronComplaint("99 4 * * *"));
    assertEquals(
        "CronPattern: Invalid value for dayOfWeek: 9",
        MaintenanceWindow.cronComplaint("0 4 * * 9"));
    assertEquals(
        "CronPattern: configuration entry 1 (a) contains illegal characters.",
        MaintenanceWindow.cronComplaint("a b c d e"));
  }

  @Test
  void everyStrategyTheInterfaceOffersIsDeclared() {
    assertEquals(6, MaintenanceWindow.STRATEGIES.size());
    assertTrue(MaintenanceWindow.STRATEGIES.contains("recurring-day-of-month"));
  }
}
