package io.akka.uptimekuma.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Console;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * The command line.
 *
 * <p>A monitoring server is operated as much from a terminal as from a browser — a monitor is added
 * from a deployment script, a page is paused before a release, a password is reset by somebody who
 * cannot sign in — so the capability has a command-line surface as well as an interface and an API.
 *
 * <p>Everything goes through the service's own HTTP surface rather than around it, so a command
 * does exactly what the same action in the browser does and there is no second path into the state.
 */
public final class Kuma {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String base;
  private final HttpClient client;
  private String token;

  private Kuma(String base) {
    this.base = base.replaceAll("/+$", "");
    this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
  }

  public static void main(String[] args) {
    if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
      usage();
      return;
    }
    Map<String, String> options = new LinkedHashMap<>();
    List<String> positional = new ArrayList<>();
    for (String arg : args) {
      if (arg.startsWith("--")) {
        int equals = arg.indexOf('=');
        if (equals < 0) {
          options.put(arg.substring(2), "true");
        } else {
          options.put(arg.substring(2, equals), arg.substring(equals + 1));
        }
      } else {
        positional.add(arg);
      }
    }

    String url = options.getOrDefault("url", System.getenv().getOrDefault("UPTIME_KUMA_URL", "http://localhost:9158"));
    Kuma kuma = new Kuma(url);
    try {
      kuma.run(positional, options);
    } catch (Exception e) {
      System.err.println("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
      System.exit(1);
    }
  }

  private void run(List<String> args, Map<String, String> options) throws Exception {
    String command = args.get(0);
    switch (command) {
      case "reset-password" -> resetPassword(options);
      case "remove-2fa" -> remove2fa(options);
      case "setup" -> setup(options);
      case "monitor" -> monitor(args, options);
      case "notification" -> notification(args, options);
      case "status-page" -> statusPage(args, options);
      case "maintenance" -> maintenance(args, options);
      case "settings" -> settings(args, options);
      case "push" -> push(args);
      default -> {
        System.err.println("Unknown command: " + command);
        usage();
        System.exit(1);
      }
    }
  }

  // ---- recovery --------------------------------------------------------------------------------

  private void resetPassword(Map<String, String> options) throws Exception {
    System.out.println("== Uptime Kuma Reset Password Tool ==");
    String secret = options.get("secret");
    if (secret == null) {
      secret = System.getenv("UPTIME_KUMA_CLI_SECRET");
    }
    String password = options.get("new-password");
    if (password != null) {
      System.out.println("Using password from argument");
      System.out.println(
          "Warning: the password might be stored, in plain text, in your shell's history");
    } else {
      password = prompt("New Password: ");
      String confirmation = prompt("Confirm New Password: ");
      if (!password.equals(confirmation)) {
        System.out.println("Passwords do not match, please try again.");
        return;
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("secret", secret);
    body.put("newPassword", password);
    body.put("dryRun", options.containsKey("dry-run"));
    Map<String, Object> answer = post("/cli/reset-password", body);
    if (answer.get("username") != null) {
      System.out.println("Found user: " + answer.get("username"));
    }
    System.out.println(answer.get("msg"));
    System.out.println("Finished.");
    if (!Boolean.TRUE.equals(answer.get("ok"))) {
      System.exit(1);
    }
  }

  private void remove2fa(Map<String, String> options) throws Exception {
    System.out.println("== Uptime Kuma Remove 2FA Tool ==");
    String secret = options.get("secret");
    if (secret == null) {
      secret = System.getenv("UPTIME_KUMA_CLI_SECRET");
    }
    if (!options.containsKey("yes")) {
      String answer = prompt("Are you sure want to remove 2FA? [y/N] ");
      if (!"y".equalsIgnoreCase(answer.trim())) {
        System.out.println("Finished.");
        return;
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("secret", secret);
    Map<String, Object> answer = post("/cli/remove-2fa", body);
    if (answer.get("username") != null) {
      System.out.println("Found user: " + answer.get("username"));
    }
    System.out.println(answer.get("msg"));
    System.out.println("Finished.");
    if (!Boolean.TRUE.equals(answer.get("ok"))) {
      System.exit(1);
    }
  }

  // ---- ordinary work ---------------------------------------------------------------------------

  private void setup(Map<String, String> options) throws Exception {
    String username = required(options, "username");
    String password = required(options, "password");
    Map<String, Object> answer = call("setup", List.of(username, password));
    report(answer);
  }

  private void monitor(List<String> args, Map<String, String> options) throws Exception {
    String action = args.size() > 1 ? args.get(1) : "list";
    switch (action) {
      case "list" -> {
        signIn(options);
        Map<String, Object> answer = call("getMonitorList", List.of());
        for (Map<String, Object> emission : emissions(answer)) {
          if ("monitorList".equals(emission.get("name"))) {
            Map<?, ?> monitors = (Map<?, ?>) ((List<?>) emission.get("args")).get(0);
            for (Object entry : monitors.values()) {
              Map<?, ?> monitor = (Map<?, ?>) entry;
              System.out.printf(
                  "%-6s %-10s %-30s %s%n",
                  monitor.get("id"),
                  monitor.get("type"),
                  monitor.get("name"),
                  Boolean.TRUE.equals(monitor.get("active")) ? "active" : "paused");
            }
          }
        }
      }
      case "get" -> {
        signIn(options);
        report(call("getMonitor", List.of(args.get(2))));
      }
      case "add" -> {
        signIn(options);
        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("name", required(options, "name"));
        monitor.put("type", options.getOrDefault("type", "http"));
        monitor.put("url", options.get("url"));
        monitor.put("hostname", options.get("hostname"));
        if (options.containsKey("port")) {
          monitor.put("port", Integer.valueOf(options.get("port")));
        }
        monitor.put("interval", Integer.parseInt(options.getOrDefault("interval", "60")));
        monitor.put("retryInterval", Integer.parseInt(options.getOrDefault("retry-interval", "60")));
        monitor.put("maxretries", Integer.parseInt(options.getOrDefault("retries", "0")));
        monitor.put("accepted_statuscodes", List.of(options.getOrDefault("accept", "200-299")));
        monitor.put("active", true);
        report(call("add", List.of(monitor)));
      }
      case "pause" -> {
        signIn(options);
        report(call("pauseMonitor", List.of(args.get(2))));
      }
      case "resume" -> {
        signIn(options);
        report(call("resumeMonitor", List.of(args.get(2))));
      }
      case "delete" -> {
        signIn(options);
        report(call("deleteMonitor", List.of(args.get(2), options.containsKey("with-children"))));
      }
      case "beats" -> {
        signIn(options);
        Map<String, Object> answer =
            call(
                "monitorImportantHeartbeatListPaged",
                List.of(args.get(2), 0, Integer.parseInt(options.getOrDefault("count", "20"))));
        Map<?, ?> result = (Map<?, ?>) answer.get("result");
        for (Object beat : (List<?>) result.get("data")) {
          Map<?, ?> row = (Map<?, ?>) beat;
          System.out.printf("%-24s %-3s %s%n", row.get("time"), row.get("status"), row.get("msg"));
        }
      }
      default -> System.err.println("Unknown monitor action: " + action);
    }
  }

  private void notification(List<String> args, Map<String, String> options) throws Exception {
    signIn(options);
    String action = args.size() > 1 ? args.get(1) : "list";
    if ("list".equals(action)) {
      Map<String, Object> answer = call("getMonitorList", List.of());
      for (Map<String, Object> emission : emissions(answer)) {
        if ("notificationList".equals(emission.get("name"))) {
          for (Object entry : (List<?>) ((List<?>) emission.get("args")).get(0)) {
            Map<?, ?> notification = (Map<?, ?>) entry;
            System.out.printf(
                "%-6s %-24s %s%n",
                notification.get("id"), notification.get("type"), notification.get("name"));
          }
        }
      }
    } else if ("test".equals(action)) {
      Map<String, Object> notification =
          MAPPER.readValue(required(options, "config"), LinkedHashMap.class);
      report(call("testNotification", List.of(notification)));
    } else {
      System.err.println("Unknown notification action: " + action);
    }
  }

  private void statusPage(List<String> args, Map<String, String> options) throws Exception {
    signIn(options);
    String action = args.size() > 1 ? args.get(1) : "list";
    switch (action) {
      case "list" -> {
        Map<String, Object> answer = call("getMonitorList", List.of());
        for (Map<String, Object> emission : emissions(answer)) {
          if ("statusPageList".equals(emission.get("name"))) {
            Map<?, ?> pages = (Map<?, ?>) ((List<?>) emission.get("args")).get(0);
            for (Object entry : pages.values()) {
              Map<?, ?> page = (Map<?, ?>) entry;
              System.out.printf("%-20s %s%n", page.get("slug"), page.get("title"));
            }
          }
        }
      }
      case "add" -> report(call("addStatusPage", List.of(required(options, "title"), required(options, "slug"))));
      case "delete" -> report(call("deleteStatusPage", List.of(args.get(2))));
      default -> System.err.println("Unknown status-page action: " + action);
    }
  }

  private void maintenance(List<String> args, Map<String, String> options) throws Exception {
    signIn(options);
    String action = args.size() > 1 ? args.get(1) : "list";
    switch (action) {
      case "list" -> {
        Map<String, Object> answer = call("getMaintenanceList", List.of());
        for (Map<String, Object> emission : emissions(answer)) {
          if ("maintenanceList".equals(emission.get("name"))) {
            Map<?, ?> windows = (Map<?, ?>) ((List<?>) emission.get("args")).get(0);
            for (Object entry : windows.values()) {
              Map<?, ?> window = (Map<?, ?>) entry;
              System.out.printf(
                  "%-6s %-20s %-24s %s%n",
                  window.get("id"), window.get("strategy"), window.get("title"), window.get("status"));
            }
          }
        }
      }
      case "pause" -> report(call("pauseMaintenance", List.of(args.get(2))));
      case "resume" -> report(call("resumeMaintenance", List.of(args.get(2))));
      default -> System.err.println("Unknown maintenance action: " + action);
    }
  }

  private void settings(List<String> args, Map<String, String> options) throws Exception {
    signIn(options);
    String action = args.size() > 1 ? args.get(1) : "get";
    if ("get".equals(action)) {
      report(call("getSettings", List.of()));
    } else if ("set".equals(action)) {
      Map<String, Object> values = new LinkedHashMap<>();
      for (Map.Entry<String, String> option : options.entrySet()) {
        if (List.of("url", "username", "password", "token", "secret").contains(option.getKey())) {
          continue;
        }
        values.put(option.getKey(), option.getValue());
      }
      report(call("setSettings", List.of(values, options.getOrDefault("password", ""))));
    } else {
      System.err.println("Unknown settings action: " + action);
    }
  }

  /** Beat into a push monitor from a script, which is what the type exists for. */
  private void push(List<String> args) throws Exception {
    String token = args.get(1);
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/push/" + token)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    System.out.println(response.body());
  }

  // ---- talking to the service --------------------------------------------------------------------

  private void signIn(Map<String, String> options) throws Exception {
    if (options.containsKey("token")) {
      token = options.get("token");
      return;
    }
    String username = options.get("username");
    String password = options.get("password");
    if (username == null || password == null) {
      // A server with authentication switched off answers without a token, so an absent
      // credential is not an error until a call is refused.
      return;
    }
    Map<String, Object> credentials = new LinkedHashMap<>();
    credentials.put("username", username);
    credentials.put("password", password);
    if (options.containsKey("2fa")) {
      credentials.put("token", options.get("2fa"));
    }
    Map<String, Object> answer = call("login", List.of(credentials));
    Map<?, ?> result = (Map<?, ?>) answer.get("result");
    if (result == null || !Boolean.TRUE.equals(result.get("ok"))) {
      throw new IllegalStateException("could not sign in: " + (result == null ? "no answer" : result.get("msg")));
    }
    token = String.valueOf(result.get("token"));
  }

  private Map<String, Object> call(String event, List<Object> args) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("args", args);
    body.put("token", token);
    return post("/socket/" + event, body);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(base + path + " answered " + response.statusCode());
    }
    return MAPPER.readValue(response.body(), LinkedHashMap.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> emissions(Map<String, Object> answer) {
    Object emit = answer.get("emit");
    return emit instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private static void report(Map<String, Object> answer) throws Exception {
    Object result = answer.get("result");
    if (result == null) {
      System.out.println("ok");
      return;
    }
    System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    if (result instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"))) {
      System.exit(1);
    }
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }

  /** Read one line, without echoing it when the terminal can hide it. */
  private static String prompt(String question) {
    Console console = System.console();
    if (console != null && question.toLowerCase().contains("password")) {
      char[] typed = console.readPassword(question);
      return typed == null ? "" : new String(typed);
    }
    System.out.print(question);
    System.out.flush();
    Scanner scanner = new Scanner(System.in);
    return scanner.hasNextLine() ? scanner.nextLine() : "";
  }

  private static void usage() {
    System.out.println(
        """
        uptime-kuma-akka — the command line

        Where the server is:
          --url=http://localhost:9158        or the UPTIME_KUMA_URL environment variable

        Signing in (not needed when authentication is switched off):
          --username=... --password=...      or --token=...

        Commands:
          setup --username=... --password=...
          monitor list
          monitor get <id>
          monitor add --name=... [--type=http] [--url=...] [--hostname=...] [--port=...]
                      [--interval=60] [--retry-interval=60] [--retries=0] [--accept=200-299]
          monitor pause <id>
          monitor resume <id>
          monitor delete <id> [--with-children]
          monitor beats <id> [--count=20]
          notification list
          notification test --config='{"type":"webhook","name":"x","webhookURL":"..."}'
          status-page list
          status-page add --title=... --slug=...
          status-page delete <slug>
          maintenance list
          maintenance pause <id>
          maintenance resume <id>
          settings get
          settings set --key=value ... --password=...
          push <token>

        When you cannot sign in (needs UPTIME_KUMA_CLI_SECRET, set on the server):
          reset-password [--new-password=...] [--dry-run] [--secret=...]
          remove-2fa [--yes] [--secret=...]
        """);
  }
}
