package io.akka.uptimekuma.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A monitor's uptime and response time, in three resolutions.
 *
 * <p>Every beat lands in three buckets at once — the minute, the hour and the day it fell in — and
 * a window is answered by walking backwards over buckets of the right size rather than over the
 * beats themselves. That is what lets a year of uptime be answered from three hundred and
 * sixty-five numbers.
 *
 * <p>The bucket keys are unix seconds of the truncated period, and the daily key truncates in UTC
 * rather than in the server's timezone, deliberately: a server that moves timezone would otherwise
 * move every historical day boundary underneath the figures already reported.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UptimeCalculator(
    Map<Long, Bucket> minutely,
    Map<Long, Bucket> hourly,
    Map<Long, Bucket> daily,
    Bucket lastMinutely,
    Bucket lastHourly,
    Bucket lastDaily) {

  /** How many buckets of each resolution are kept. */
  public static final int MINUTELY_CAPACITY = 24 * 60;

  public static final int HOURLY_CAPACITY = 30 * 24;
  public static final int DAILY_CAPACITY = 365;

  /**
   * One bucket's tally.
   *
   * @param maintenance how many beats fell in this period while the monitor was under maintenance.
   *     Counted separately because maintenance is excluded from the uptime ratio altogether — it
   *     does not count as up and it does not count as down.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Bucket(
      long timestamp,
      int up,
      int down,
      int maintenance,
      double avgPing,
      Double minPing,
      Double maxPing) {

    public static Bucket empty(long timestamp) {
      return new Bucket(timestamp, 0, 0, 0, 0d, null, null);
    }
  }

  public static UptimeCalculator empty() {
    return new UptimeCalculator(
        new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), null, null, null);
  }

  public static long minutelyKey(long epochMillis) {
    return epochMillis / 1000L / 60L * 60L;
  }

  public static long hourlyKey(long epochMillis) {
    return epochMillis / 1000L / 3600L * 3600L;
  }

  /** The start of the UTC day the instant falls in. */
  public static long dailyKey(long epochMillis) {
    ZonedDateTime utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
    return utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond();
  }

  /**
   * Fold one beat in.
   *
   * @param status the beat's status
   * @param ping the beat's response time, or null when it did not measure one
   * @param atEpochMillis when the beat happened
   */
  public UptimeCalculator update(Status status, Double ping, long atEpochMillis) {
    Map<Long, Bucket> nextMinutely = new TreeMap<>(minutely);
    Map<Long, Bucket> nextHourly = new TreeMap<>(hourly);
    Map<Long, Bucket> nextDaily = new TreeMap<>(daily);

    Bucket m = fold(nextMinutely, minutelyKey(atEpochMillis), status, ping, MINUTELY_CAPACITY);
    Bucket h = fold(nextHourly, hourlyKey(atEpochMillis), status, ping, HOURLY_CAPACITY);
    Bucket d = fold(nextDaily, dailyKey(atEpochMillis), status, ping, DAILY_CAPACITY);

    return new UptimeCalculator(nextMinutely, nextHourly, nextDaily, m, h, d);
  }

  private static Bucket fold(
      Map<Long, Bucket> buckets, long key, Status status, Double ping, int capacity) {
    Bucket current = buckets.getOrDefault(key, Bucket.empty(key));
    Bucket updated;
    if (status == Status.MAINTENANCE) {
      updated =
          new Bucket(
              key,
              current.up(),
              current.down(),
              current.maintenance() + 1,
              current.avgPing(),
              current.minPing(),
              current.maxPing());
    } else if (status.countsAsUp()) {
      int up = current.up() + 1;
      double avg = current.avgPing();
      Double min = current.minPing();
      Double max = current.maxPing();
      if (ping != null && !ping.isNaN()) {
        if (current.up() == 0) {
          avg = ping;
          min = ping;
          max = ping;
        } else {
          avg = (current.avgPing() * (up - 1) + ping) / up;
          min = min == null ? ping : Math.min(min, ping);
          max = max == null ? ping : Math.max(max, ping);
        }
      }
      updated = new Bucket(key, up, current.down(), current.maintenance(), avg, min, max);
    } else {
      // A down beat contributes nothing to the response time. The source logs and discards any
      // ping that arrives with one, because the number would be the time a failure took.
      updated =
          new Bucket(
              key,
              current.up(),
              current.down() + 1,
              current.maintenance(),
              current.avgPing(),
              current.minPing(),
              current.maxPing());
    }
    buckets.put(key, updated);
    while (buckets.size() > capacity) {
      buckets.remove(((TreeMap<Long, Bucket>) buckets).firstKey());
    }
    return updated;
  }

  /**
   * @param uptime a fraction between zero and one, not a percentage
   * @param avgPing the mean response time over the window, or null when nothing in it was up
   */
  public record Window(int up, int down, double uptime, Double avgPing) {}

  public Window getData(int count, String unit, long nowEpochMillis) {
    Map<Long, Bucket> buckets;
    long step;
    long key;
    int cap;
    Bucket fallback;
    switch (unit) {
      case "minute" -> {
        buckets = minutely;
        step = 60L;
        key = minutelyKey(nowEpochMillis);
        cap = MINUTELY_CAPACITY;
        fallback = lastMinutely;
      }
      case "hour" -> {
        buckets = hourly;
        step = 3600L;
        key = hourlyKey(nowEpochMillis);
        cap = HOURLY_CAPACITY;
        fallback = lastHourly;
      }
      case "day" -> {
        buckets = daily;
        step = 86400L;
        key = dailyKey(nowEpochMillis);
        cap = DAILY_CAPACITY;
        fallback = lastDaily;
      }
      default -> throw new IllegalArgumentException("Invalid unit: " + unit);
    }
    int limit = Math.min(count, cap);

    int up = 0;
    int down = 0;
    double totalPing = 0;
    for (int i = 0; i < limit; i++) {
      Bucket bucket = buckets.get(key - i * step);
      if (bucket != null) {
        up += bucket.up();
        down += bucket.down();
        totalPing += bucket.avgPing() * bucket.up();
      }
    }

    if (up == 0 && down == 0 && fallback != null) {
      // A monitor that has been paused longer than the window still shows the figure it last
      // had, rather than dropping to zero uptime as though it had gone down.
      up = fallback.up();
      down = fallback.down();
      totalPing = fallback.avgPing() * fallback.up();
    }

    double uptime = (up + down == 0) ? 0 : (double) up / (up + down);
    Double avgPing = up == 0 ? null : totalPing / up;
    return new Window(up, down, uptime, avgPing);
  }

  public Window get24Hour(long now) {
    return getData(1440, "minute", now);
  }

  public Window get7Day(long now) {
    return getData(168, "hour", now);
  }

  public Window get30Day(long now) {
    return getData(30, "day", now);
  }

  public Window get1Year(long now) {
    return getData(365, "day", now);
  }

  /**
   * The window a badge's {@code :duration} path segment names.
   *
   * @param duration a count followed by one of {@code m h d w M y}
   */
  public Window getDataByDuration(String duration, long now) {
    if (duration == null || duration.length() < 2) {
      throw new IllegalArgumentException("Invalid duration: " + duration);
    }
    String unit = duration.substring(duration.length() - 1);
    int num;
    try {
      num = Integer.parseInt(duration.substring(0, duration.length() - 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid duration: " + duration);
    }
    return switch (unit) {
      case "m" -> getData(num, "minute", now);
      case "h" -> getData(num, "hour", now);
      case "d" -> getData(num, "day", now);
      case "w" -> getData(num * 7, "day", now);
      case "M" -> getData(num * 30, "day", now);
      case "y" -> getData(num * 365, "day", now);
      default ->
          throw new IllegalArgumentException(
              "Unsupported unit (" + unit + ") for badge duration " + duration);
    };
  }

  /** The bucket series a chart is drawn from, oldest first. */
  public List<Map<String, Object>> getDataArray(int count, String unit, long nowEpochMillis) {
    Map<Long, Bucket> buckets;
    long step;
    long key;
    int cap;
    switch (unit) {
      case "minute" -> {
        buckets = minutely;
        step = 60L;
        key = minutelyKey(nowEpochMillis);
        cap = MINUTELY_CAPACITY;
      }
      case "hour" -> {
        buckets = hourly;
        step = 3600L;
        key = hourlyKey(nowEpochMillis);
        cap = HOURLY_CAPACITY;
      }
      case "day" -> {
        buckets = daily;
        step = 86400L;
        key = dailyKey(nowEpochMillis);
        cap = DAILY_CAPACITY;
      }
      default -> throw new IllegalArgumentException("Invalid unit: " + unit);
    }
    int limit = Math.min(count, cap);
    List<Map<String, Object>> series = new ArrayList<>();
    for (int i = limit - 1; i >= 0; i--) {
      long at = key - i * step;
      Bucket bucket = buckets.get(at);
      if (bucket == null) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("timestamp", at);
      row.put("up", bucket.up());
      row.put("down", bucket.down());
      row.put("avgPing", bucket.up() == 0 ? null : bucket.avgPing());
      row.put("minPing", bucket.minPing());
      row.put("maxPing", bucket.maxPing());
      if (bucket.maintenance() > 0) {
        row.put("maintenance", bucket.maintenance());
      }
      series.add(row);
    }
    return series;
  }
}
