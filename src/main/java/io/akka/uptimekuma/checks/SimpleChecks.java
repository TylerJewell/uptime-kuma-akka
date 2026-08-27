package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Status;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The monitor types whose check is a short, self-contained piece of work. */
final class SimpleChecks {

  private SimpleChecks() {}

  static List<Check> all() {
    return List.of(
        new GroupCheck(),
        new ManualCheck(),
        new PingCheck(),
        new PortCheck(),
        new SmtpCheck(),
        new SipOptionsCheck(),
        new Pm2Check(),
        new SystemServiceCheck(),
        new TailscalePingCheck());
  }

  /**
   * A group's status is its children's.
   *
   * <p>One of the two types allowed to name a status without raising, because a group that is
   * pending is a real answer rather than a failure — its children have not settled yet.
   */
  static final class GroupCheck implements Check {
    @Override
    public String type() {
      return "group";
    }

    @Override
    public boolean allowCustomStatus() {
      return true;
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      List<CheckContext.ChildStatus> children =
          context.children().stream().filter(CheckContext.ChildStatus::active).toList();
      if (children.isEmpty()) {
        return CheckOutcome.custom(Status.PENDING, "Group empty", null);
      }
      List<String> down = new ArrayList<>();
      List<String> pending = new ArrayList<>();
      for (CheckContext.ChildStatus child : children) {
        if (child.status() == Status.DOWN) {
          down.add(child.name());
        } else if (child.status() == Status.PENDING) {
          pending.add(child.name());
        }
      }
      if (!down.isEmpty()) {
        String message = "Child monitors down: " + String.join(", ", down);
        if (!pending.isEmpty()) {
          message = message + "; pending: " + String.join(", ", pending);
        }
        throw new CheckFailed(message);
      }
      if (!pending.isEmpty()) {
        return CheckOutcome.custom(
            Status.PENDING, "Pending child monitors: " + String.join(", ", pending), null);
      }
      return CheckOutcome.custom(Status.UP, "All children up and running", null);
    }
  }

  /** A status a person set by hand, which no check can contradict. */
  static final class ManualCheck implements Check {
    @Override
    public String type() {
      return "manual";
    }

    @Override
    public boolean allowCustomStatus() {
      return true;
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) {
      Integer manual = config.manual_status();
      if (manual == null) {
        return CheckOutcome.custom(
            Status.PENDING, "Manual monitoring - No status set", null);
      }
      Status status = Status.of(manual);
      String message =
          switch (status) {
            case UP -> "Up";
            case DOWN -> "Down";
            default -> "Pending";
          };
      return CheckOutcome.custom(status, message, null);
    }
  }

  /**
   * An ICMP echo, run through the operating system's own tool.
   *
   * <p>Raw ICMP needs a privileged socket, so the source shells out and so does this. What is read
   * back is the round-trip time, which is the number the whole type exists to produce.
   */
  static final class PingCheck implements Check {
    @Override
    public String type() {
      return "ping";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String host = Punycode.encode(stripBrackets(config.hostname()));
      boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
      List<String> command = new ArrayList<>();
      command.add("ping");
      if (windows) {
        command.add("-n");
        command.add(String.valueOf(config.ping_count()));
        command.add("-l");
        command.add(String.valueOf(config.packetSize()));
        command.add("-w");
        command.add(String.valueOf(config.ping_per_request_timeout() * 1000));
        if (config.ping_numeric()) {
          command.add("-4");
        }
      } else {
        command.add("-c");
        command.add(String.valueOf(config.ping_count()));
        command.add("-s");
        command.add(String.valueOf(config.packetSize()));
        command.add("-W");
        command.add(String.valueOf(config.ping_per_request_timeout()));
        if (config.ping_numeric()) {
          command.add("-n");
        }
      }
      command.add(host);

      String output;
      int exit;
      try {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        output = new String(process.getInputStream().readAllBytes());
        boolean finished =
            process.waitFor((long) (config.effectiveTimeout() * 1000), TimeUnit.MILLISECONDS);
        if (!finished) {
          process.destroyForcibly();
          throw new CheckFailed("Ping timed out");
        }
        exit = process.exitValue();
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(e.getMessage() == null ? "Ping failed" : e.getMessage());
      }
      if (exit != 0) {
        throw new CheckFailed(firstMeaningfulLine(output, host));
      }
      Double time = parseTime(output);
      if (time == null) {
        throw new CheckFailed(firstMeaningfulLine(output, host));
      }
      return CheckOutcome.up("", time);
    }

    /** An address written in brackets is the URL form; the tool wants it bare. */
    private static String stripBrackets(String host) {
      if (host == null) {
        return null;
      }
      return host.startsWith("[") && host.endsWith("]")
          ? host.substring(1, host.length() - 1)
          : host;
    }

    private static Double parseTime(String output) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("(?:time|Average)[=<]\\s*([0-9.]+)\\s*ms")
              .matcher(output);
      Double last = null;
      while (matcher.find()) {
        last = Double.valueOf(matcher.group(1));
      }
      return last;
    }

    private static String firstMeaningfulLine(String output, String host) {
      for (String line : output.split("\\R")) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("PING") && !trimmed.startsWith("Pinging")) {
          return trimmed;
        }
      }
      return "Ping failed for " + host;
    }
  }

  /**
   * A TCP connection, optionally carried far enough to look at the certificate.
   *
   * <p>Three modes: connect and hang up; connect, negotiate TLS and read the certificate; and
   * connect expecting the handshake to be refused with a named alert, which is how a server that is
   * meant to reject anonymous clients is checked.
   */
  static final class PortCheck implements Check {
    @Override
    public String type() {
      return "port";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      int timeoutMillis = (int) Math.max(1, config.effectiveTimeout() * 1000);
      if (config.expectedTlsAlert() != null && !config.expectedTlsAlert().isBlank()) {
        return expectAlert(config, timeoutMillis);
      }
      long startedAt = System.nanoTime();
      try (Socket socket = new Socket()) {
        socket.connect(
            new InetSocketAddress(config.hostname(), config.port() == null ? 0 : config.port()),
            timeoutMillis);
      } catch (Exception e) {
        throw new CheckFailed("Connection failed");
      }
      double elapsed = (System.nanoTime() - startedAt) / 1_000_000.0;
      long rounded = Math.round(elapsed);
      return CheckOutcome.up(rounded + " ms", (double) rounded);
    }

    private CheckOutcome expectAlert(MonitorConfig config, int timeoutMillis) throws CheckFailed {
      long startedAt = System.nanoTime();
      try {
        javax.net.ssl.SSLSocketFactory factory =
            Http.sslContext(!config.ignoreTls(), config.tlsCert(), config.tlsKey(), config.tlsCa())
                .getSocketFactory();
        try (Socket plain = new Socket()) {
          plain.connect(new InetSocketAddress(config.hostname(), config.port()), timeoutMillis);
          try (javax.net.ssl.SSLSocket socket =
              (javax.net.ssl.SSLSocket)
                  factory.createSocket(plain, config.hostname(), config.port(), false)) {
            socket.setSoTimeout(timeoutMillis);
            socket.startHandshake();
          }
        }
      } catch (javax.net.ssl.SSLException e) {
        Integer alert = TlsAlerts.parseNumber(e.getMessage());
        String expected = config.expectedTlsAlert();
        String actualName = alert == null ? null : TlsAlerts.name(alert);
        if (actualName != null && actualName.equals(expected)) {
          double elapsed = (System.nanoTime() - startedAt) / 1_000_000.0;
          return CheckOutcome.up(
              "TLS alert received as expected: " + actualName + " (" + alert + ")",
              Math.round(elapsed) * 1.0);
        }
        if (alert != null) {
          throw new CheckFailed(
              "Expected TLS alert '"
                  + expected
                  + "' but received '"
                  + actualName
                  + "' ("
                  + alert
                  + ")");
        }
        throw new CheckFailed(
            "Expected TLS alert '" + expected + "' but got unexpected error: " + e.getMessage());
      } catch (Exception e) {
        throw new CheckFailed(
            "Expected TLS alert '"
                + config.expectedTlsAlert()
                + "' but got unexpected error: "
                + e.getMessage());
      }
      throw new CheckFailed(
          "Expected TLS alert '"
              + config.expectedTlsAlert()
              + "' but connection succeeded without any alert");
    }
  }

  /** A mail server, checked by opening a session far enough to greet it. */
  static final class SmtpCheck implements Check {
    @Override
    public String type() {
      return "smtp";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      int port = config.port() == null ? 25 : config.port();
      int timeoutMillis = (int) Math.max(1, config.effectiveTimeout() * 1000);
      String security = config.smtpSecurity() == null ? "nostarttls" : config.smtpSecurity();
      long startedAt = System.nanoTime();
      try {
        SmtpSession session =
            SmtpSession.open(config.hostname(), port, timeoutMillis, security, config.ignoreTls());
        session.close();
      } catch (Exception e) {
        throw new CheckFailed("SMTP connection doesn't verify: " + e.getMessage());
      }
      double elapsed = (System.nanoTime() - startedAt) / 1_000_000.0;
      return CheckOutcome.up("SMTP connection verifies successfully", Math.round(elapsed) * 1.0);
    }
  }

  /** A SIP endpoint, asked what it supports. */
  static final class SipOptionsCheck implements Check {
    /** The source gives this one a fixed deadline rather than the monitor's own. */
    private static final int TIMEOUT_MILLIS = 3000;

    @Override
    public String type() {
      return "sip-options";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      int port = config.port() == null ? 5060 : config.port();
      long startedAt = System.nanoTime();
      String response;
      try {
        response = Sip.options(config.hostname(), port, TIMEOUT_MILLIS);
      } catch (Exception e) {
        throw new CheckFailed("Error in output: " + e.getMessage());
      }
      if (response == null || response.isBlank()) {
        throw new CheckFailed("No output from sipsak");
      }
      for (String line : response.split("\\R")) {
        if (line.contains("200 OK")) {
          double elapsed = (System.nanoTime() - startedAt) / 1_000_000.0;
          return CheckOutcome.up(line.trim(), Math.round(elapsed) * 1.0);
        }
      }
      throw new CheckFailed("Error in output: " + response.trim());
    }
  }

  /** A process under a node process manager. */
  static final class Pm2Check implements Check {
    @Override
    public String type() {
      return "pm2";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String output = Shell.run(List.of("pm2", "jlist"), 10000);
      String name = config.system_service_name();
      try {
        List<?> processes = JsonQuery.Json.MAPPER.readValue(output, List.class);
        for (Object entry : processes) {
          if (entry instanceof java.util.Map<?, ?> process
              && name.equals(String.valueOf(process.get("name")))) {
            Object env = process.get("pm2_env");
            String status =
                env instanceof java.util.Map<?, ?> map ? String.valueOf(map.get("status")) : null;
            if ("online".equals(status)) {
              return CheckOutcome.up("PM2 process '" + name + "' is online.", null);
            }
            throw new CheckFailed("PM2 process '" + name + "' is " + status + ".");
          }
        }
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed("PM2 process '" + name + "' was not found.");
      }
      throw new CheckFailed("PM2 process '" + name + "' was not found.");
    }
  }

  /** A service under the operating system's own supervisor. */
  static final class SystemServiceCheck implements Check {
    @Override
    public String type() {
      return "system-service";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      String name = config.system_service_name();
      if (platform.contains("win")) {
        if (name == null || !name.matches("^[A-Za-z0-9._-]+$")) {
          throw new CheckFailed("Invalid service name.");
        }
        String output =
            Shell.run(
                List.of(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "(Get-Service -Name '" + name + "').Status"),
                15000);
        String status = output.trim();
        if ("Running".equalsIgnoreCase(status)) {
          return CheckOutcome.up("Service '" + name + "' is running.", null);
        }
        if (status.isEmpty()) {
          throw new CheckFailed("Service '" + name + "' is not running/found.");
        }
        throw new CheckFailed("Service '" + name + "' is " + status + ".");
      }
      if (platform.contains("linux")) {
        if (name == null || !name.matches("^[a-zA-Z0-9._\\-@]+$")) {
          throw new CheckFailed("Invalid service name.");
        }
        String output = Shell.run(List.of("systemctl", "is-active", name), 15000).trim();
        if ("active".equals(output)) {
          return CheckOutcome.up("Service '" + name + "' is running.", null);
        }
        if (output.isEmpty()) {
          throw new CheckFailed("Service '" + name + "' is not running/found.");
        }
        throw new CheckFailed("Service '" + name + "' is " + output + ".");
      }
      throw new CheckFailed("System Service monitoring is not supported on " + platform);
    }
  }

  /** A host inside a private mesh, reachable only through the mesh's own tool. */
  static final class TailscalePingCheck implements Check {
    @Override
    public String type() {
      return "tailscale-ping";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String output =
          Shell.run(
              List.of("tailscale", "ping", "--c", "1", config.hostname()),
              (long) (config.interval() * 1000 * 0.8));
      for (String line : output.split("\\R")) {
        if (line.contains("pong from")) {
          java.util.regex.Matcher matcher =
              java.util.regex.Pattern.compile("in (\\d+)ms").matcher(line);
          Double ping = matcher.find() ? Double.valueOf(matcher.group(1)) : null;
          return CheckOutcome.up("OK", ping);
        }
        if (line.contains("timed out")) {
          throw new CheckFailed(line.trim());
        }
        if (line.contains("no matching peer")) {
          throw new CheckFailed("Nonexistant or inaccessible due to ACLs");
        }
        if (line.contains("is local Tailscale IP")) {
          throw new CheckFailed(line.trim());
        }
      }
      throw new CheckFailed("Unexpected output: " + output.trim());
    }
  }

  /** Running an external command and reading everything it printed. */
  static final class Shell {
    private Shell() {}

    static String run(List<String> command, long timeoutMillis) throws CheckFailed {
      try {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(process.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            output.append(line).append('\n');
          }
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new CheckFailed("Command timed out: " + String.join(" ", command));
        }
        return output.toString();
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(e.getMessage() == null ? "Command failed" : e.getMessage());
      }
    }
  }
}
