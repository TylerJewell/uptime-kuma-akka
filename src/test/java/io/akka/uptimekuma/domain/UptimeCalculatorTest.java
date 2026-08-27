package io.akka.uptimekuma.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The bucketed statistics, against the same cases the source's own suite covers.
 *
 * <p>Every case fixes the clock, because a bucket key is derived from an instant and a test that
 * read the real clock would land on a boundary sometimes and not others.
 */
class UptimeCalculatorTest {

  private static final long BASE = Instant.parse("2023-08-12T20:46:59Z").toEpochMilli();

  @Test
  void aMinuteKeyTruncatesToTheMinute() {
    long start = Instant.parse("2023-08-12T20:46:00Z").toEpochMilli();
    assertEquals(
        UptimeCalculator.minutelyKey(start), UptimeCalculator.minutelyKey(start + 1000));
    assertEquals(
        UptimeCalculator.minutelyKey(start), UptimeCalculator.minutelyKey(start + 59_000));
  }

  @Test
  void aDayKeyTruncatesInUtcRatherThanLocally() {
    long lateEvening = Instant.parse("2023-12-22T21:38:39Z").toEpochMilli();
    long sameDay = Instant.parse("2023-12-22T00:00:00Z").toEpochMilli();
    assertEquals(UptimeCalculator.dailyKey(sameDay), UptimeCalculator.dailyKey(lateEvening));
  }

  @Test
  void noDataIsZeroUptimeAndNoAveragePing() {
    UptimeCalculator.Window window = UptimeCalculator.empty().get24Hour(BASE);
    assertEquals(0, window.uptime());
    assertNull(window.avgPing());
  }

  @Test
  void oneUpBeatIsFullUptimeAndItsOwnPing() {
    UptimeCalculator stats = UptimeCalculator.empty().update(Status.UP, 100d, BASE);
    UptimeCalculator.Window window = stats.get24Hour(BASE);
    assertEquals(1, window.uptime());
    assertEquals(100, window.avgPing());
  }

  @Test
  void twoUpBeatsAverageTheirPings() {
    UptimeCalculator stats =
        UptimeCalculator.empty().update(Status.UP, 100d, BASE).update(Status.UP, 200d, BASE);
    assertEquals(150, stats.get24Hour(BASE).avgPing());
  }

  @Test
  void aRunningMeanIsKeptRatherThanASum() {
    UptimeCalculator stats =
        UptimeCalculator.empty()
            .update(Status.UP, 0d, BASE)
            .update(Status.UP, 100d, BASE)
            .update(Status.UP, 400d, BASE);
    assertEquals(1, stats.get24Hour(BASE).uptime());
    assertEquals(166.66666666666666, stats.get24Hour(BASE).avgPing(), 1e-9);
  }

  @Test
  void maintenanceCountsAsNeitherUpNorDown() {
    UptimeCalculator stats = UptimeCalculator.empty().update(Status.MAINTENANCE, null, BASE);
    UptimeCalculator.Window window = stats.get24Hour(BASE);
    assertEquals(0, window.uptime());
    assertNull(window.avgPing());
  }

  @Test
  void pendingCountsAsDown() {
    UptimeCalculator stats = UptimeCalculator.empty().update(Status.PENDING, null, BASE);
    assertEquals(0, stats.get24Hour(BASE).uptime());
  }

  @Test
  void aDownBeatContributesNoPing() {
    UptimeCalculator stats =
        UptimeCalculator.empty().update(Status.DOWN, null, BASE).update(Status.UP, 0.5, BASE);
    UptimeCalculator.Window window = stats.get24Hour(BASE);
    assertEquals(0.5, window.uptime());
    assertEquals(0.5, window.avgPing());
  }

  @Test
  void anUpBeatAndADownBeatSplitTheRatio() {
    UptimeCalculator stats =
        UptimeCalculator.empty().update(Status.UP, 123d, BASE).update(Status.DOWN, null, BASE);
    UptimeCalculator.Window window = stats.get24Hour(BASE);
    assertEquals(0.5, window.uptime());
    assertEquals(123, window.avgPing());
  }

  @Test
  void aWindowWithNothingInItFallsBackToTheLastBucketTouched() {
    UptimeCalculator stats = UptimeCalculator.empty();
    for (int i = 0; i < 3; i++) {
      stats = stats.update(Status.UP, 0d, BASE);
    }
    stats = stats.update(Status.UP, 1d, BASE).update(Status.DOWN, null, BASE);
    assertEquals(0.8, stats.get24Hour(BASE).uptime(), 1e-9);
    // A day later the window holds nothing, and a paused monitor keeps showing the figure it had
    // rather than dropping to zero as though it had gone down.
    long dayLater = BASE + 24L * 3600 * 1000;
    assertEquals(0.8, stats.get24Hour(dayLater).uptime(), 1e-9);
    long twoDaysLater = BASE + 48L * 3600 * 1000;
    assertEquals(0.8, stats.get24Hour(twoDaysLater).uptime(), 1e-9);
  }

  @Test
  void thirtyDaysOfAlternatingBeats() {
    UptimeCalculator stats = UptimeCalculator.empty();
    long day = BASE;
    int up = 0;
    int down = 0;
    for (int i = 0; i < 30; i++) {
      if (i % 2 == 0) {
        stats = stats.update(Status.UP, 10d, day);
        up++;
      } else {
        stats = stats.update(Status.DOWN, null, day);
        down++;
      }
      assertEquals((double) up / (up + down), stats.get30Day(day).uptime(), 1e-9);
      day += 24L * 3600 * 1000;
    }
  }

  @Test
  void aYearOfAlternatingBeats() {
    UptimeCalculator stats = UptimeCalculator.empty();
    long day = BASE;
    for (int i = 0; i < 365; i++) {
      stats = stats.update(i % 2 == 0 ? Status.UP : Status.DOWN, 10d, day);
      day += 24L * 3600 * 1000;
    }
    long last = day - 24L * 3600 * 1000;
    assertEquals(183.0 / 365.0, stats.get1Year(last).uptime(), 1e-9);
    assertEquals(15.0 / 30.0, stats.get30Day(last).uptime(), 1e-9);
  }

  @Test
  void theBucketListsAreBounded() {
    UptimeCalculator stats = UptimeCalculator.empty();
    long minute = BASE;
    for (int i = 0; i < UptimeCalculator.MINUTELY_CAPACITY + 200; i++) {
      stats = stats.update(Status.UP, 1d, minute);
      minute += 60_000;
    }
    assertEquals(UptimeCalculator.MINUTELY_CAPACITY, stats.minutely().size());
  }

  @Test
  void aBadgeDurationSelectsTheRightResolution() {
    UptimeCalculator stats = UptimeCalculator.empty().update(Status.UP, 5d, BASE);
    assertEquals(1, stats.getDataByDuration("24h", BASE).uptime());
    assertEquals(1, stats.getDataByDuration("30d", BASE).uptime());
    assertEquals(1, stats.getDataByDuration("1y", BASE).uptime());
    assertEquals(1, stats.getDataByDuration("2w", BASE).uptime());
    assertEquals(1, stats.getDataByDuration("1M", BASE).uptime());
  }

  @Test
  void anUnreadableDurationIsRefused() {
    UptimeCalculator stats = UptimeCalculator.empty();
    assertThrows(IllegalArgumentException.class, () -> stats.getDataByDuration("24x", BASE));
    assertThrows(IllegalArgumentException.class, () -> stats.getDataByDuration("nonsense", BASE));
  }

  @Test
  void aChartSeriesCarriesOnlyTheBucketsThatHaveSomethingInThem() {
    UptimeCalculator stats = UptimeCalculator.empty().update(Status.UP, 7d, BASE);
    var series = stats.getDataArray(60, "minute", BASE);
    assertEquals(1, series.size());
    assertEquals(1, series.get(0).get("up"));
    assertEquals(7.0, series.get(0).get("avgPing"));
  }
}
