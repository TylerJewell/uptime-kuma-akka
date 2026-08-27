package io.akka.uptimekuma.domain;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * A planned outage, and whether it is happening now.
 *
 * <p>Six strategies. Three of them — weekday, day-of-month and interval — are stored as the cron
 * pattern they generate rather than as their own kind of schedule, which is what makes them all one
 * question at read time: when does the next window open, and is now inside one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaintenanceWindow(
    String id,
    String title,
    String description,
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
    Integer duration,
    String timezoneOption,
    String lastStartDate) {

  public static final List<String> STRATEGIES =
      List.of(
          "manual",
          "single",
          "recurring-interval",
          "recurring-weekday",
          "recurring-day-of-month",
          "cron");

  private static final DateTimeFormatter SQL =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final CronParser CRON_PARSER =
      new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

  /**
   * What is wrong with a cron pattern, in the words the source's own parser uses, or null.
   *
   * <p>The source refuses a window whose pattern it cannot read, rather than storing it and
   * reporting the window as unknown afterwards, and the message a person sees comes straight from
   * `croner`. Its three complaints are reproduced here: the wrong number of parts, a part holding
   * something that is not a number or a name, and a number outside the range its field allows.
   */
  public static String cronComplaint(String pattern) {
    String text = pattern == null ? "" : pattern.trim();
    String[] parts = text.isEmpty() ? new String[0] : text.split("\\s+");
    if (parts.length < 5 || parts.length > 6) {
      return "CronPattern: invalid configuration format ('"
          + (pattern == null ? "" : pattern)
          + "'), exactly five or six space separated parts are required.";
    }
    for (int i = 0; i < parts.length; i++) {
      if (!parts[i].matches("[0-9A-Za-z*/,\\-?]+")) {
        return "CronPattern: configuration entry "
            + (i + 1)
            + " ("
            + parts[i]
            + ") contains illegal characters.";
      }
    }
    try {
      CRON_PARSER.parse(text).validate();
    } catch (Exception e) {
      String[] fields =
          parts.length == 6
              ? new String[] {"second", "minute", "hour", "day", "month", "dayOfWeek"}
              : new String[] {"minute", "hour", "day", "month", "dayOfWeek"};
      for (int i = 0; i < parts.length; i++) {
        if (parts[i].matches("[0-9]+") && outOfRange(fields[i], Integer.parseInt(parts[i]))) {
          return "CronPattern: Invalid value for " + fields[i] + ": " + parts[i];
        }
      }
      return "CronPattern: configuration entry 1 (" + parts[0] + ") contains illegal characters.";
    }
    return null;
  }

  private static boolean outOfRange(String field, int value) {
    return switch (field) {
      case "second", "minute" -> value > 59;
      case "hour" -> value > 23;
      case "day" -> value < 1 || value > 31;
      case "month" -> value < 1 || value > 12;
      case "dayOfWeek" -> value > 7;
      default -> false;
    };
  }

  /** The timezone this window is read in — the server's, unless it names its own. */
  public ZoneId zone(String serverTimezone) {
    if (timezoneOption == null
        || timezoneOption.isBlank()
        || "SAME_AS_SERVER".equals(timezoneOption)) {
      return ZoneId.of(serverTimezone);
    }
    try {
      return ZoneId.of(timezoneOption);
    } catch (Exception e) {
      return ZoneId.of(serverTimezone);
    }
  }

  /**
   * Where this window stands.
   *
   * @return one of inactive, under-maintenance, scheduled, ended, unknown
   */
  public String status(String serverTimezone, long nowEpochMillis) {
    if (!active) {
      return "inactive";
    }
    if ("manual".equals(strategy)) {
      return "under-maintenance";
    }
    ZoneId zone = zone(serverTimezone);
    ZonedDateTime now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone);
    if (startDate != null && !startDate.isBlank() && now.isBefore(parse(startDate, zone))) {
      return "scheduled";
    }
    if (endDate != null && !endDate.isBlank() && now.isAfter(parse(endDate, zone))) {
      return "ended";
    }
    if ("single".equals(strategy)) {
      return "under-maintenance";
    }
    String pattern = effectiveCron();
    if (pattern == null || pattern.isBlank() || duration == null || duration <= 0) {
      return "unknown";
    }
    try {
      CRON_PARSER.parse(pattern);
    } catch (Exception e) {
      // A pattern nothing can read means no schedule was ever armed, and the source reports that
      // as unknown rather than as a window waiting to open.
      return "unknown";
    }
    return runningTimeslot(serverTimezone, nowEpochMillis).isPresent()
        ? "under-maintenance"
        : "scheduled";
  }

  public boolean isUnderMaintenance(String serverTimezone, long nowEpochMillis) {
    return "under-maintenance".equals(status(serverTimezone, nowEpochMillis));
  }

  /** @param startEpochMillis and endEpochMillis bound the window now falls inside */
  public record Timeslot(long startEpochMillis, long endEpochMillis) {}

  /**
   * The window that contains now, if there is one.
   *
   * <p>Found by asking the schedule for the first firing at or after one duration ago, which is the
   * only firing that could still be open.
   */
  public Optional<Timeslot> runningTimeslot(String serverTimezone, long nowEpochMillis) {
    String pattern = effectiveCron();
    if (pattern == null || pattern.isBlank() || duration == null || duration <= 0) {
      return Optional.empty();
    }
    ZoneId zone = zone(serverTimezone);
    ZonedDateTime now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone);
    ExecutionTime execution;
    try {
      execution = ExecutionTime.forCron(CRON_PARSER.parse(pattern));
    } catch (Exception e) {
      return Optional.empty();
    }
    Optional<ZonedDateTime> next = execution.nextExecution(now.minusSeconds(duration));
    if (next.isEmpty()) {
      return Optional.empty();
    }
    ZonedDateTime start = next.get();
    ZonedDateTime end = start.plusSeconds(duration);
    if (now.isAfter(start) && now.isBefore(end)) {
      return Optional.of(
          new Timeslot(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli()));
    }
    return Optional.empty();
  }

  /** The window after the current instant, for the interface's schedule list. */
  public Optional<Timeslot> nextTimeslot(String serverTimezone, long nowEpochMillis) {
    String pattern = effectiveCron();
    if (pattern == null || pattern.isBlank() || duration == null || duration <= 0) {
      return Optional.empty();
    }
    ZoneId zone = zone(serverTimezone);
    ZonedDateTime now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone);
    try {
      return ExecutionTime.forCron(CRON_PARSER.parse(pattern))
          .nextExecution(now)
          .map(
              start ->
                  new Timeslot(
                      start.toInstant().toEpochMilli(),
                      start.plusSeconds(duration).toInstant().toEpochMilli()));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * The cron pattern this window runs on, generated from the strategy where it is not given.
   *
   * <p>The interval strategy generates a daily pattern and then skips firings itself, because cron
   * cannot say "every N days"; the skip is applied in {@link #runningTimeslot} through the
   * last-start date rather than in the pattern.
   */
  public String effectiveCron() {
    if ("cron".equals(strategy)) {
      return cron;
    }
    if (!strategy.startsWith("recurring-")) {
      return "";
    }
    LocalTime start = parseTime(startTime);
    String minute = String.valueOf(start.getMinute());
    String hour = String.valueOf(start.getHour());
    return switch (strategy) {
      // Two spaces between the hour and the first wildcard. That is what the source writes, and
      // a pattern is compared as a string when a window is edited.
      case "recurring-interval" -> minute + " " + hour + "  * * *";
      case "recurring-weekday" -> {
        TreeSet<Integer> sorted = new TreeSet<>(weekdays == null ? List.of() : weekdays);
        yield minute
            + " "
            + hour
            + " * * "
            + String.join(",", sorted.stream().map(String::valueOf).toList());
      }
      case "recurring-day-of-month" -> {
        List<String> days = new ArrayList<>();
        for (String day : daysOfMonth == null ? List.<String>of() : daysOfMonth) {
          String mapped = "lastDay1".equals(day) ? "L" : day;
          // lastDay2 through lastDay4 have no cron spelling at all, so the source drops them.
          if (mapped.startsWith("lastDay")) {
            continue;
          }
          if (!days.contains(mapped)) {
            days.add(mapped);
          }
        }
        yield minute + " " + hour + " " + String.join(",", days) + " * *";
      }
      default -> "";
    };
  }

  /** The duration a generated pattern implies, in seconds, crossing midnight where it has to. */
  public int calculatedDuration() {
    LocalTime start = parseTime(startTime);
    LocalTime end = parseTime(endTime);
    int seconds = (int) Duration.between(start, end).getSeconds();
    return seconds < 0 ? seconds + 24 * 3600 : seconds;
  }

  private static LocalTime parseTime(String text) {
    if (text == null || text.isBlank()) {
      return LocalTime.MIDNIGHT;
    }
    String[] parts = text.split(":");
    return LocalTime.of(
        Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
  }

  private static ZonedDateTime parse(String text, ZoneId zone) {
    String cleaned = text.replace("T", " ").replace("Z", "");
    if (cleaned.length() == 16) {
      cleaned = cleaned + ":00";
    }
    if (cleaned.length() > 19) {
      cleaned = cleaned.substring(0, 19);
    }
    return java.time.LocalDateTime.parse(cleaned, SQL).atZone(zone);
  }

  /** The window as the interface reads it. */
  public Map<String, Object> toJson(String serverTimezone, long now) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", id);
    json.put("title", title);
    json.put("description", description);
    json.put("strategy", strategy);
    json.put("intervalDay", intervalDay);
    json.put("active", active);
    List<String> dateRange = new ArrayList<>();
    dateRange.add(startDate);
    if (endDate != null && !endDate.isBlank()) {
      dateRange.add(endDate);
    }
    json.put("dateRange", dateRange);
    json.put("timeRange", List.of(timeObject(startTime), timeObject(endTime)));
    json.put("weekdays", weekdays == null ? List.of() : weekdays);
    json.put("daysOfMonth", daysOfMonth == null ? List.of() : daysOfMonth);
    json.put("timeslotList", timeslotList(serverTimezone, now));
    json.put("cron", cron);
    json.put("duration", duration);
    // `parseInt(null / 60)` is zero, not null, and that is what the interface's form reads.
    json.put("durationMinutes", duration == null ? 0 : duration / 60);
    ZoneId zone = zone(serverTimezone);
    json.put("timezone", zone.getId());
    json.put("timezoneOption", timezoneOption);
    json.put(
        "timezoneOffset",
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .getOffset()
            .getId()
            .replace("Z", "+00:00"));
    json.put("status", status(serverTimezone, now));
    return json;
  }

  private List<Map<String, Object>> timeslotList(String serverTimezone, long now) {
    List<Map<String, Object>> slots = new ArrayList<>();
    if ("manual".equals(strategy)) {
      return slots;
    }
    if ("single".equals(strategy)) {
      slots.add(slot(startDate, endDate));
      return slots;
    }
    runningTimeslot(serverTimezone, now)
        .ifPresent(t -> slots.add(slot(iso(t.startEpochMillis()), iso(t.endEpochMillis()))));
    nextTimeslot(serverTimezone, now)
        .ifPresent(t -> slots.add(slot(iso(t.startEpochMillis()), iso(t.endEpochMillis()))));
    return slots;
  }

  private static String iso(long epochMillis) {
    return Instant.ofEpochMilli(epochMillis).toString();
  }

  private static Map<String, Object> slot(String start, String end) {
    Map<String, Object> slot = new LinkedHashMap<>();
    slot.put("startDate", start);
    slot.put("endDate", end);
    return slot;
  }

  private static Map<String, Object> timeObject(String text) {
    LocalTime time = parseTime(text);
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("hours", time.getHour());
    json.put("minutes", time.getMinute());
    return json;
  }
}
