package io.akka.uptimekuma.checks;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.Conditions;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.bson.Document;

/** The monitor types that reach a data store and ask it one question. */
final class DatabaseChecks {

  private DatabaseChecks() {}

  static List<Check> all() {
    return List.of(
        new PostgresCheck(),
        new MysqlCheck(),
        new SqlServerCheck(),
        new OracleCheck(),
        new MongodbCheck(),
        new RedisCheck(),
        new RabbitMqCheck());
  }

  /**
   * The three SQL types that read a condition tree share every line of their behaviour except the
   * connection.
   */
  abstract static class SingleValueQuery implements Check {

    abstract Connection connect(MonitorConfig config) throws Exception;

    @Override
    public boolean supportsConditions() {
      return true;
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String query =
          config.databaseQuery() == null || config.databaseQuery().isBlank()
              ? "SELECT 1"
              : config.databaseQuery();
      long startedAt = System.nanoTime();
      String result;
      try (Connection connection = connect(config);
          Statement statement = connection.createStatement()) {
        try (ResultSet rows = statement.executeQuery(query)) {
          if (!config.conditions().isEmpty()) {
            result = singleValue(rows);
          } else {
            result = firstValueOrEmpty(rows);
          }
        }
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed("Database connection/query failed: " + rootMessage(e));
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
      if (!config.conditions().isEmpty()) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("result", result);
        if (Conditions.evaluate(config.conditions(), variables)) {
          return CheckOutcome.up("Query did meet specified conditions", ping);
        }
        throw new CheckFailed(
            "Query result did not meet the specified conditions (" + result + ")", ping, null);
      }
      return CheckOutcome.up(result, ping);
    }

    /**
     * The one value the conditions read.
     *
     * <p>A query that returns more than one row or more than one column is refused rather than
     * having a value picked out of it, because which one was meant is not knowable.
     */
    static String singleValue(ResultSet rows) throws Exception {
      if (!rows.next()) {
        throw new CheckFailed("Query returned no results");
      }
      ResultSetMetaData meta = rows.getMetaData();
      if (meta.getColumnCount() > 1) {
        throw new CheckFailed("Multiple columns were found, expected only one value");
      }
      String value = String.valueOf(rows.getObject(1));
      if (rows.next()) {
        throw new CheckFailed("Multiple values were found, expected only one value");
      }
      return value;
    }

    private static String firstValueOrEmpty(ResultSet rows) throws Exception {
      if (!rows.next()) {
        return "";
      }
      return String.valueOf(rows.getObject(1));
    }
  }

  static final class PostgresCheck extends SingleValueQuery {
    @Override
    public String type() {
      return "postgres";
    }

    @Override
    Connection connect(MonitorConfig config) throws Exception {
      return DriverManager.getConnection(jdbc(config.databaseConnectionString()));
    }

    /** Accept both the driver's own URL and the {@code postgres://} form the interface asks for. */
    private static String jdbc(String connectionString) {
      if (connectionString == null) {
        return null;
      }
      if (connectionString.startsWith("jdbc:")) {
        return connectionString;
      }
      return "jdbc:" + connectionString.replaceFirst("^postgres://", "postgresql://");
    }
  }

  static final class MysqlCheck extends SingleValueQuery {
    @Override
    public String type() {
      return "mysql";
    }

    @Override
    Connection connect(MonitorConfig config) throws Exception {
      String url = config.databaseConnectionString();
      if (url != null && !url.startsWith("jdbc:")) {
        url = "jdbc:" + url;
      }
      Properties properties = new Properties();
      // The password lives in the field the radius type also uses, which is the source's own
      // reuse of a column rather than a separate one.
      if (config.radiusPassword() != null) {
        properties.setProperty("password", config.radiusPassword());
      }
      return DriverManager.getConnection(url, properties);
    }
  }

  static final class SqlServerCheck extends SingleValueQuery {
    @Override
    public String type() {
      return "sqlserver";
    }

    @Override
    Connection connect(MonitorConfig config) throws Exception {
      String url = config.databaseConnectionString();
      if (url != null && !url.startsWith("jdbc:")) {
        url = "jdbc:" + url;
      }
      return DriverManager.getConnection(url);
    }
  }

  static final class OracleCheck extends SingleValueQuery {
    @Override
    public String type() {
      return "oracledb";
    }

    @Override
    Connection connect(MonitorConfig config) throws Exception {
      String url = config.databaseConnectionString();
      if (url != null && !url.startsWith("jdbc:")) {
        url = "jdbc:oracle:thin:@" + url;
      }
      return DriverManager.getConnection(
          url, config.basic_auth_user(), config.basic_auth_pass());
    }
  }

  static final class MongodbCheck implements Check {
    @Override
    public String type() {
      return "mongodb";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      long startedAt = System.nanoTime();
      Document command;
      try {
        String raw = config.databaseQuery();
        command =
            raw == null || raw.isBlank() ? new Document("ping", 1) : Document.parse(raw);
      } catch (Exception e) {
        throw new CheckFailed("MongoDB command failed");
      }
      Document result;
      String databaseName;
      try (MongoClient client = MongoClients.create(config.databaseConnectionString())) {
        databaseName = databaseOf(config.databaseConnectionString());
        MongoDatabase database = client.getDatabase(databaseName);
        result = database.runCommand(command);
      } catch (Exception e) {
        throw new CheckFailed("MongoDB command failed");
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;

      if (config.jsonPath() != null && !config.jsonPath().isBlank()) {
        Object value;
        try {
          value = com.dashjoin.jsonata.Jsonata.jsonata(config.jsonPath()).evaluate(result);
        } catch (Exception e) {
          value = null;
        }
        if (value == null) {
          throw new CheckFailed("Queried value not found.");
        }
        if (config.expectedValue() != null && !config.expectedValue().isBlank()) {
          if (!config.expectedValue().equals(String.valueOf(value))) {
            throw new CheckFailed(
                "Query executed, but value is not equal to expected value, value was: ["
                    + value
                    + "]");
          }
          return CheckOutcome.up("Command executed successfully and expected value was found", ping);
        }
        return CheckOutcome.up(
            "Command executed successfully and the jsonata expression produces a result.", ping);
      }
      return CheckOutcome.up("Command executed successfully", ping);
    }

    /** The database named in the connection string, or the server's default. */
    private static String databaseOf(String connectionString) {
      try {
        String path = URI.create(connectionString).getPath();
        if (path != null && path.length() > 1) {
          return path.substring(1);
        }
      } catch (Exception e) {
        return "admin";
      }
      return "admin";
    }
  }

  /**
   * A key-value store, asked to say hello.
   *
   * <p>The protocol is small enough to speak directly: a command is an array of length-prefixed
   * strings and the answer to this one is a single line, so a driver would be four hundred
   * kilobytes to send eleven bytes.
   */
  static final class RedisCheck implements Check {
    @Override
    public String type() {
      return "redis";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String connectionString = config.databaseConnectionString();
      URI uri;
      try {
        uri = URI.create(connectionString);
      } catch (Exception e) {
        throw new CheckFailed("Invalid Redis connection string");
      }
      boolean tls = "rediss".equals(uri.getScheme());
      int port = uri.getPort() == -1 ? 6379 : uri.getPort();
      int timeoutMillis = (int) Math.max(1, config.effectiveTimeout() * 1000);
      long startedAt = System.nanoTime();
      try (Socket socket = openSocket(uri.getHost(), port, timeoutMillis, tls, config.ignoreTls())) {
        OutputStream out = socket.getOutputStream();
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
          String[] parts = userInfo.split(":", 2);
          if (parts.length == 2 && !parts[0].isEmpty()) {
            out.write(command("AUTH", parts[0], parts[1]));
          } else {
            out.write(command("AUTH", parts.length == 2 ? parts[1] : parts[0]));
          }
          out.flush();
          readLine(socket);
        }
        out.write(command("PING"));
        out.flush();
        String reply = readLine(socket);
        double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
        if (reply == null || reply.isEmpty()) {
          throw new CheckFailed("No reply from Redis");
        }
        if (reply.charAt(0) == '-') {
          throw new CheckFailed(reply.substring(1));
        }
        return CheckOutcome.up(reply.substring(1), ping);
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(rootMessage(e));
      }
    }

    private static Socket openSocket(
        String host, int port, int timeoutMillis, boolean tls, boolean ignoreTls) throws Exception {
      if (tls) {
        javax.net.ssl.SSLSocket socket =
            (javax.net.ssl.SSLSocket)
                Http.sslContext(!ignoreTls, null, null, null).getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        socket.startHandshake();
        return socket;
      }
      Socket socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      return socket;
    }

    private static byte[] command(String... parts) {
      StringBuilder out = new StringBuilder("*").append(parts.length).append("\r\n");
      for (String part : parts) {
        out.append('$')
            .append(part.getBytes(StandardCharsets.UTF_8).length)
            .append("\r\n")
            .append(part)
            .append("\r\n");
      }
      return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String readLine(Socket socket) throws Exception {
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      return reader.readLine();
    }
  }

  /**
   * A message broker, asked through its management interface whether any node is raising an alarm.
   *
   * <p>A cluster is given as a list, and one healthy node is enough — which is what makes the
   * message name how many were tried.
   */
  static final class RabbitMqCheck implements Check {
    @Override
    public String type() {
      return "rabbitmq";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      List<String> nodes = config.rabbitmqNodes();
      if (nodes == null || nodes.isEmpty()) {
        throw new CheckFailed("All 0 nodes failed because ");
      }
      List<String> errors = new ArrayList<>();
      long startedAt = System.nanoTime();
      for (String node : nodes) {
        try {
          java.net.http.HttpClient client =
              Http.client(
                  true,
                  null,
                  null,
                  0,
                  java.time.Duration.ofMillis((long) (config.effectiveTimeout() * 1000)),
                  null,
                  null,
                  null);
          java.net.http.HttpRequest request =
              java.net.http.HttpRequest.newBuilder(
                      URI.create(
                          Http.withParam(
                              node.replaceAll("/+$", "") + "/api/health/checks/alarms/", "_", "1")))
                  .header(
                      "Authorization",
                      Http.basicAuth(config.rabbitmqUsername(), config.rabbitmqPassword()))
                  .timeout(java.time.Duration.ofMillis((long) ((config.effectiveTimeout() + 10) * 1000)))
                  .GET()
                  .build();
          java.net.http.HttpResponse<String> response =
              client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() == 200) {
            double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
            return CheckOutcome.up(
                nodes.size() == 1
                    ? "Node is reachable and there are no alerts in the cluster"
                    : "One of the "
                        + nodes.size()
                        + " nodes is reachable and there are no alerts in the cluster",
                ping);
          }
          errors.add(node + " responded " + response.statusCode());
        } catch (java.net.http.HttpTimeoutException e) {
          throw new CheckFailed("Request timed out");
        } catch (Exception e) {
          errors.add(node + " " + rootMessage(e));
        }
      }
      throw new CheckFailed(
          "All " + nodes.size() + " nodes failed because " + String.join("; ", errors));
    }
  }

  /**
   * A failure's message where no URL is at hand to name — a driver, a socket, a resolver.
   *
   * <p>The transport table still applies, so a refusal reaching a database check reads the same way
   * it reads on an HTTP one, minus the {@code host:port} the caller could not supply.
   */
  static String rootMessage(Throwable error) {
    return TransportErrors.message(error, null);
  }
}
