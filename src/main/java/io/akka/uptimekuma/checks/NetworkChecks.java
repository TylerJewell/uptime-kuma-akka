package io.akka.uptimekuma.checks;

import io.akka.uptimekuma.domain.AcceptedStatusCodes;
import io.akka.uptimekuma.domain.BeatDecision.CheckOutcome;
import io.akka.uptimekuma.domain.Conditions;
import io.akka.uptimekuma.domain.MonitorConfig;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/** The monitor types that speak a network protocol other than plain HTTP. */
final class NetworkChecks {

  private NetworkChecks() {}

  static List<Check> all() {
    return List.of(
        new DnsCheck(),
        new SnmpCheck(),
        new MqttCheck(),
        new KafkaProducerCheck(),
        new WebSocketUpgradeCheck(),
        new NtpCheck(),
        new RadiusCheck());
  }

  /**
   * A name lookup against a named resolver.
   *
   * <p>Every record type the interface offers, because the message a person reads is assembled
   * differently for each — an address list, a mail exchanger with its priority, a zone's serial and
   * its timers. A condition tree, where one is set, is satisfied if any single record satisfies it.
   */
  static final class DnsCheck implements Check {
    @Override
    public String type() {
      return "dns";
    }

    @Override
    public boolean supportsConditions() {
      return true;
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String rrtype = config.dns_resolve_type() == null ? "A" : config.dns_resolve_type();
      long startedAt = System.nanoTime();
      List<Record> records;
      try {
        Lookup lookup = new Lookup(config.hostname(), Type.value(rrtype));
        if (config.dns_resolve_server() != null && !config.dns_resolve_server().isBlank()) {
          List<SimpleResolver> resolvers = new ArrayList<>();
          for (String server : config.dns_resolve_server().split(",")) {
            SimpleResolver resolver = new SimpleResolver(server.trim());
            if (config.port() != null) {
              resolver.setPort(config.port());
            }
            resolvers.add(resolver);
          }
          lookup.setResolver(
              resolvers.size() == 1
                  ? resolvers.get(0)
                  : new org.xbill.DNS.ExtendedResolver(resolvers.toArray(new SimpleResolver[0])));
        }
        Record[] found = lookup.run();
        if (lookup.getResult() != Lookup.SUCCESSFUL || found == null || found.length == 0) {
          throw new CheckFailed(lookup.getErrorString());
        }
        records = List.of(found);
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(e.getMessage() == null ? "DNS lookup failed" : e.getMessage());
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;

      List<String> values = new ArrayList<>();
      for (Record record : records) {
        values.add(renderValue(record, rrtype));
      }
      String message = renderMessage(rrtype, values);

      if (!config.conditions().isEmpty()) {
        boolean anyPassed = false;
        for (String value : values) {
          Map<String, Object> variables = new LinkedHashMap<>();
          variables.put("record", value);
          if (Conditions.evaluate(config.conditions(), variables)) {
            anyPassed = true;
            break;
          }
        }
        if (!anyPassed) {
          throw new CheckFailed(message, ping, null);
        }
      }
      return CheckOutcome.up(message, ping);
    }

    private static String renderValue(Record record, String rrtype) {
      String text = record.rdataToString();
      return switch (rrtype) {
        case "MX" -> {
          org.xbill.DNS.MXRecord mx = (org.xbill.DNS.MXRecord) record;
          yield "Hostname: " + mx.getTarget() + " - Priority: " + mx.getPriority();
        }
        case "SOA" -> {
          org.xbill.DNS.SOARecord soa = (org.xbill.DNS.SOARecord) record;
          yield "NS-Name: "
              + soa.getHost()
              + " | Hostmaster: "
              + soa.getAdmin()
              + " | Serial: "
              + soa.getSerial()
              + " | Refresh: "
              + soa.getRefresh()
              + " | Retry: "
              + soa.getRetry()
              + " | Expire: "
              + soa.getExpire()
              + " | MinTTL: "
              + soa.getMinimum();
        }
        case "SRV" -> {
          org.xbill.DNS.SRVRecord srv = (org.xbill.DNS.SRVRecord) record;
          yield "Name: "
              + srv.getTarget()
              + " | Port: "
              + srv.getPort()
              + " | Priority: "
              + srv.getPriority()
              + " | Weight: "
              + srv.getWeight();
        }
        // A text record arrives quoted; the quotes are the presentation format rather than part
        // of the value a condition compares.
        case "TXT" -> text.replaceAll("^\"|\"$", "");
        default -> text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
      };
    }

    private static String renderMessage(String rrtype, List<String> values) {
      return switch (rrtype) {
        case "A", "AAAA", "PTR", "TXT" -> "Records: " + String.join(" | ", values);
        case "NS" -> "Servers: " + String.join(" | ", values);
        case "CNAME", "SOA" -> values.get(0);
        default -> String.join(" | ", values);
      };
    }
  }

  /** One object read out of a device's management tree. */
  static final class SnmpCheck implements Check {
    @Override
    public String type() {
      return "snmp";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      String port = config.port() == null ? "161" : String.valueOf(config.port());
      Address address = GenericAddress.parse("udp:" + config.hostname() + "/" + port);
      long startedAt = System.nanoTime();
      String value;
      try (DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping()) {
        Snmp snmp = new Snmp(transport);
        transport.listen();
        Target<Address> target;
        PDU pdu;
        if ("3".equals(config.snmpVersion())) {
          if (config.snmp_v3_username() == null || config.snmp_v3_username().isBlank()) {
            throw new CheckFailed("SNMPv3 username is required");
          }
          USM usm =
              new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
          SecurityModels.getInstance().addSecurityModel(usm);
          usm.addUser(
              new OctetString(config.snmp_v3_username()),
              new UsmUser(new OctetString(config.snmp_v3_username()), null, null, null, null));
          UserTarget<Address> userTarget = new UserTarget<>();
          // No authentication and no privacy: the interface offers a username and nothing else
          // for version three, so that is the only level a monitor can be configured for.
          userTarget.setSecurityLevel(SecurityLevel.NOAUTH_NOPRIV);
          userTarget.setSecurityName(new OctetString(config.snmp_v3_username()));
          userTarget.setVersion(SnmpConstants.version3);
          target = userTarget;
          pdu = new ScopedPDU();
        } else {
          CommunityTarget<Address> community = new CommunityTarget<>();
          // The community string is stored in the field the radius type also uses. That is the
          // source's own reuse of a column, not a mistake here.
          community.setCommunity(
              new OctetString(
                  config.radiusPassword() == null ? "public" : config.radiusPassword()));
          community.setVersion(
              "1".equals(config.snmpVersion()) ? SnmpConstants.version1 : SnmpConstants.version2c);
          target = community;
          pdu = new PDU();
        }
        target.setAddress(address);
        target.setRetries(config.maxretries());
        target.setTimeout((long) (config.effectiveTimeout() * 1000));
        pdu.add(new VariableBinding(new OID(config.snmpOid())));
        pdu.setType(PDU.GET);

        ResponseEvent<Address> event = snmp.send(pdu, target);
        snmp.close();
        if (event == null || event.getResponse() == null) {
          throw new CheckFailed("RequestTimedOutException: timeout");
        }
        List<? extends VariableBinding> bindings = event.getResponse().getVariableBindings();
        if (bindings == null || bindings.isEmpty()) {
          throw new CheckFailed(
              "No varbinds returned from SNMP session (OID: " + config.snmpOid() + ")");
        }
        VariableBinding binding = bindings.get(0);
        if (binding.isException()) {
          throw new CheckFailed(
              "The SNMP query returned that no instance exists for OID " + config.snmpOid());
        }
        value = binding.getVariable().toString();
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        throw new CheckFailed(e.getMessage() == null ? "SNMP query failed" : e.getMessage());
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
      JsonQuery.Result result =
          JsonQuery.evaluate(
              value, config.jsonPath(), config.jsonPathOperator(), config.expectedValue());
      String comparison =
          "(comparing "
              + result.response()
              + " "
              + config.jsonPathOperator()
              + " "
              + config.expectedValue()
              + ")";
      if (result.status()) {
        return CheckOutcome.up("JSON query passes " + comparison, ping);
      }
      throw new CheckFailed("JSON query does not pass " + comparison, ping, null);
    }
  }

  /**
   * A subscription that waits for one message on a topic.
   *
   * <p>Unlike every other type this one does not ask a question — it listens, and the check
   * succeeds when a message arrives that says what it should. So the deadline is the check, and a
   * quiet topic is a failure.
   */
  static final class MqttCheck implements Check {
    @Override
    public String type() {
      return "mqtt";
    }

    @Override
    public boolean supportsConditions() {
      return true;
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      long timeoutMillis = (long) (config.interval() * 1000 * 0.8);
      String scheme = config.mqttWebsocketPath() != null && !config.mqttWebsocketPath().isBlank()
          ? "ws"
          : "tcp";
      String broker =
          scheme
              + "://"
              + config.hostname()
              + ":"
              + (config.port() == null ? 1883 : config.port())
              + (("ws".equals(scheme)) ? config.mqttWebsocketPath() : "");
      CompletableFuture<String[]> received = new CompletableFuture<>();
      long startedAt = System.nanoTime();
      MqttClient client = null;
      try {
        client =
            new MqttClient(broker, "uptime-kuma-" + System.nanoTime(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout((int) Math.max(1, timeoutMillis / 1000));
        if (config.mqttUsername() != null && !config.mqttUsername().isBlank()) {
          options.setUserName(config.mqttUsername());
          options.setPassword(
              (config.mqttPassword() == null ? "" : config.mqttPassword()).toCharArray());
        }
        client.setCallback(
            new MqttCallback() {
              @Override
              public void connectionLost(Throwable cause) {
                received.completeExceptionally(cause);
              }

              @Override
              public void messageArrived(String topic, MqttMessage message) {
                received.complete(new String[] {topic, new String(message.getPayload())});
              }

              @Override
              public void deliveryComplete(IMqttDeliveryToken token) {
                // Nothing is published by this check, so nothing is ever delivered.
              }
            });
        client.connect(options);
        client.subscribe(config.mqttTopic());
        String[] message = received.get(timeoutMillis, TimeUnit.MILLISECONDS);
        double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
        return judge(config, message[0], message[1], ping);
      } catch (CheckFailed e) {
        throw e;
      } catch (java.util.concurrent.TimeoutException e) {
        throw new CheckFailed("Timeout, Message not received");
      } catch (Exception e) {
        throw new CheckFailed(
            e.getMessage() == null ? "MQTT connection failed" : e.getMessage());
      } finally {
        if (client != null) {
          try {
            client.disconnectForcibly(500);
            client.close();
          } catch (Exception ignored) {
            // A broker that will not let go of the connection has still answered the question.
          }
        }
      }
    }

    private CheckOutcome judge(MonitorConfig config, String topic, String payload, double ping)
        throws CheckFailed {
      if (!config.conditions().isEmpty()) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("topic", topic);
        variables.put("message", payload);
        Object jsonValue = null;
        if (config.jsonPath() != null && !config.jsonPath().isBlank()) {
          try {
            jsonValue =
                com.dashjoin.jsonata.Jsonata.jsonata(config.jsonPath())
                    .evaluate(JsonQuery.Json.MAPPER.readValue(payload, Object.class));
          } catch (Exception e) {
            jsonValue = null;
          }
        }
        variables.put("json_value", jsonValue == null ? "" : String.valueOf(jsonValue));
        if (Conditions.evaluate(config.conditions(), variables)) {
          return CheckOutcome.up("Topic: " + topic + "; Message: " + payload, ping);
        }
        throw new CheckFailed(
            "Conditions not met - Topic: " + topic + "; Message: " + payload, ping, null);
      }
      if ("json-query".equals(config.mqttCheckType())) {
        Object value;
        try {
          value =
              com.dashjoin.jsonata.Jsonata.jsonata(config.jsonPath())
                  .evaluate(JsonQuery.Json.MAPPER.readValue(payload, Object.class));
        } catch (Exception e) {
          value = null;
        }
        if (value == null) {
          throw new CheckFailed(
              "Message received but value is not equal to expected value, value was: [null]",
              ping,
              null);
        }
        if (config.expectedValue() != null
            && !config.expectedValue().equals(String.valueOf(value))) {
          throw new CheckFailed(
              "Message received but value is not equal to expected value, value was: ["
                  + value
                  + "]",
              ping,
              null);
        }
        return CheckOutcome.up("Message received, expected value is found", ping);
      }
      String expected = config.mqttSuccessMessage();
      if (expected == null || payload.contains(expected)) {
        return CheckOutcome.up("Topic: " + topic + "; Message: " + payload, ping);
      }
      throw new CheckFailed(
          "Message Mismatch - Topic: " + topic + "; Message: " + payload, ping, null);
    }
  }

  /** One message produced onto a topic. */
  static final class KafkaProducerCheck implements Check {
    @Override
    public String type() {
      return "kafka-producer";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      Properties properties = new Properties();
      properties.put("bootstrap.servers", String.join(",", config.kafkaProducerBrokers()));
      properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
      properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
      properties.put("client.id", "Uptime-Kuma/" + context.settings().getOrDefault("version", "2.5.3"));
      properties.put(
          "request.timeout.ms", String.valueOf((long) (config.effectiveTimeout() * 1000)));
      properties.put("max.block.ms", String.valueOf((long) (config.effectiveTimeout() * 1000)));
      if (config.kafkaProducerSsl()) {
        properties.put("security.protocol", "SSL");
      }
      Map<String, Object> sasl = config.kafkaProducerSaslOptions();
      if (sasl != null && sasl.get("mechanism") != null && !"None".equals(sasl.get("mechanism"))) {
        properties.put("security.protocol", config.kafkaProducerSsl() ? "SASL_SSL" : "SASL_PLAINTEXT");
        properties.put("sasl.mechanism", String.valueOf(sasl.get("mechanism")).toUpperCase());
        properties.put(
            "sasl.jaas.config",
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                + sasl.get("username")
                + "\" password=\""
                + sasl.get("password")
                + "\";");
      }
      long startedAt = System.nanoTime();
      try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
        producer
            .send(
                new ProducerRecord<>(
                    config.kafkaProducerTopic(), config.kafkaProducerMessage()))
            .get((long) (config.effectiveTimeout() * 1000) + 1000, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        throw new CheckFailed(DatabaseChecks.rootMessage(e));
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
      return CheckOutcome.up("Message sent successfully.", ping);
    }
  }

  /**
   * A WebSocket handshake, judged by the code the connection closes with.
   *
   * <p>The accepted-codes field holds close codes here rather than HTTP statuses, which is why a
   * monitor of this type defaults to one thousand — the code a clean close uses.
   */
  static final class WebSocketUpgradeCheck implements Check {
    @Override
    public String type() {
      return "websocket-upgrade";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      long timeoutMillis =
          (long) ((config.timeout() <= 0 ? 20 : config.timeout()) * 1000);
      CompletableFuture<int[]> closed = new CompletableFuture<>();
      CompletableFuture<String> reason = new CompletableFuture<>();
      long startedAt = System.nanoTime();
      try {
        HttpClient client =
            Http.client(
                !config.ignoreTls(),
                config.ipFamily(),
                context.proxy(),
                0,
                Duration.ofMillis(timeoutMillis),
                "mtls".equals(config.authMethod()) ? config.tlsCert() : null,
                "mtls".equals(config.authMethod()) ? config.tlsKey() : null,
                "mtls".equals(config.authMethod()) ? config.tlsCa() : null);
        WebSocket.Builder builder = client.newWebSocketBuilder();
        builder.connectTimeout(Duration.ofMillis(timeoutMillis));
        if ("basic".equals(config.authMethod())) {
          builder.header(
              "Authorization", Http.basicAuth(config.basic_auth_user(), config.basic_auth_pass()));
        } else if ("bearer".equals(config.authMethod())) {
          builder.header("Authorization", "Bearer " + config.bearer_token());
        }
        if (config.headers() != null && !config.headers().isBlank()) {
          try {
            Map<String, Object> extra =
                JsonQuery.Json.MAPPER.readValue(config.headers(), Map.class);
            extra.forEach((key, value) -> builder.header(key, String.valueOf(value)));
          } catch (Exception ignored) {
            // A headers field that will not parse is skipped rather than failing the check,
            // which is what the source does.
          }
        }
        if (config.wsSubprotocol() != null && !config.wsSubprotocol().isBlank()) {
          String[] parts = config.wsSubprotocol().trim().split("\\s+");
          builder.subprotocols(
              parts[0],
              java.util.Arrays.copyOfRange(parts, 1, parts.length));
        }
        WebSocket socket =
            builder
                .buildAsync(
                    URI.create(config.url()),
                    new WebSocket.Listener() {
                      @Override
                      public CompletionStage<?> onClose(
                          WebSocket webSocket, int statusCode, String closeReason) {
                        closed.complete(new int[] {statusCode});
                        reason.complete(closeReason);
                        return null;
                      }

                      @Override
                      public void onError(WebSocket webSocket, Throwable error) {
                        closed.completeExceptionally(error);
                      }
                    })
                .get(timeoutMillis, TimeUnit.MILLISECONDS);
        // The handshake succeeded, so the connection is closed cleanly and that close code is
        // what the accepted list is compared against.
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(timeoutMillis, TimeUnit.MILLISECONDS);
        int[] result = closed.get(timeoutMillis, TimeUnit.MILLISECONDS);
        double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
        List<String> accepted =
            config.accepted_statuscodes() == null || config.accepted_statuscodes().isEmpty()
                ? List.of("1000")
                : config.accepted_statuscodes();
        if (AcceptedStatusCodes.matches(result[0], accepted)) {
          return CheckOutcome.up(reason.getNow(""), ping);
        }
        String name = CloseCodes.name(result[0]);
        throw new CheckFailed(
            name != null ? name : "Unexpected status code: " + result[0], ping, null);
      } catch (CheckFailed e) {
        throw e;
      } catch (Exception e) {
        String message = DatabaseChecks.rootMessage(e);
        throw new CheckFailed(message.isBlank() ? "Unknown Websocket Error" : message);
      }
    }
  }

  /** The names a WebSocket close code carries. */
  static final class CloseCodes {
    private CloseCodes() {}

    static String name(int code) {
      return switch (code) {
        case 1000 -> "Normal Closure";
        case 1001 -> "Going Away";
        case 1002 -> "Protocol Error";
        case 1003 -> "Unsupported Data";
        case 1005 -> "No Status Received";
        case 1006 -> "Abnormal Closure";
        case 1007 -> "Invalid Frame Payload Data";
        case 1008 -> "Policy Violation";
        case 1009 -> "Message Too Big";
        case 1010 -> "Mandatory Extension";
        case 1011 -> "Internal Server Error";
        case 1012 -> "Service Restart";
        case 1013 -> "Try Again Later";
        case 1014 -> "Bad Gateway";
        case 1015 -> "TLS Handshake Failed";
        case 3000 -> "Unauthorized";
        case 3003 -> "Forbidden";
        default -> null;
      };
    }
  }

  /**
   * A time server, checked against three separate tolerances.
   *
   * <p>A clock source can be reachable and still useless: too far down the chain from a reference,
   * too far from the true time, or too unsure of itself. All three are read off one exchange.
   */
  static final class NtpCheck implements Check {
    @Override
    public String type() {
      return "ntp";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      int port = config.port() == null ? 123 : config.port();
      int timeoutMillis = (int) ((config.timeout() <= 0 ? 10 : config.timeout()) * 1000);
      long startedAt = System.nanoTime();
      Ntp.Reading reading;
      try {
        reading = Ntp.query(config.hostname(), port, timeoutMillis);
      } catch (java.net.SocketTimeoutException e) {
        throw new CheckFailed("NTP request timed out");
      } catch (Exception e) {
        throw new CheckFailed(
            e.getMessage() == null ? "NTP request failed" : e.getMessage());
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;

      if (reading.stratum() == 16) {
        throw new CheckFailed("NTP server is unsynchronized (stratum 16)");
      }
      if (reading.stratum() >= config.ntpStratumThreshold()) {
        throw new CheckFailed(
            "Stratum "
                + reading.stratum()
                + " meets or exceeds threshold "
                + config.ntpStratumThreshold());
      }
      if (Math.abs(reading.offsetMillis()) > config.ntpTimeOffsetThreshold()) {
        throw new CheckFailed(
            "Time offset "
                + format(reading.offsetMillis())
                + "ms exceeds threshold "
                + config.ntpTimeOffsetThreshold()
                + "ms");
      }
      if (reading.rootDispersionMillis() > config.ntpRootDispersionThreshold()) {
        throw new CheckFailed(
            "Root dispersion "
                + format(reading.rootDispersionMillis())
                + "ms exceeds threshold "
                + config.ntpRootDispersionThreshold()
                + "ms");
      }
      return CheckOutcome.up(
          "Stratum: "
              + reading.stratum()
              + ", RefID: "
              + reading.refId()
              + ", Offset: "
              + format(reading.offsetMillis())
              + "ms, Delay: "
              + format(reading.roundTripMillis())
              + "ms, Dispersion: "
              + format(reading.rootDispersionMillis())
              + "ms",
          ping);
    }

    private static String format(double value) {
      return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
  }

  /** An authentication server, asked whether it recognises a set of credentials. */
  static final class RadiusCheck implements Check {
    @Override
    public String type() {
      return "radius";
    }

    @Override
    public CheckOutcome check(MonitorConfig config, CheckContext context) throws CheckFailed {
      int port = config.port() == null ? 1812 : config.port();
      // This one type is given four tenths of the interval rather than the monitor's timeout.
      int timeoutMillis = (int) (config.interval() * 1000 * 0.4);
      long startedAt = System.nanoTime();
      String code;
      try {
        code =
            Radius.accessRequest(
                config.hostname(),
                port,
                config.radiusUsername(),
                config.radiusPassword(),
                config.radiusSecret(),
                config.radiusCalledStationId(),
                config.radiusCallingStationId(),
                timeoutMillis);
      } catch (Exception e) {
        throw new CheckFailed(e.getMessage() == null ? "RADIUS request failed" : e.getMessage());
      }
      double ping = (System.nanoTime() - startedAt) / 1_000_000.0;
      if ("Access-Reject".equals(code)) {
        throw new CheckFailed("RADIUS Access-Reject from " + config.hostname() + ":" + port);
      }
      return CheckOutcome.up(code, ping);
    }
  }

  /** A single SNTP exchange, and the three numbers read off it. */
  static final class Ntp {
    private Ntp() {}

    /** Seconds between the protocol's own epoch and the one the platform counts from. */
    private static final long EPOCH_OFFSET_SECONDS = 2208988800L;

    record Reading(
        int leapIndicator,
        int stratum,
        String refId,
        double offsetMillis,
        double roundTripMillis,
        double rootDispersionMillis) {}

    static byte[] createPacket() {
      byte[] packet = new byte[48];
      // Leap indicator none, version three, mode client.
      packet[0] = 0x1b;
      return packet;
    }

    static double readTimestamp(byte[] packet, int offset) {
      long seconds = readUnsigned(packet, offset);
      long fraction = readUnsigned(packet, offset + 4);
      return (seconds - EPOCH_OFFSET_SECONDS) * 1000.0 + (fraction * 1000.0) / 0x100000000L;
    }

    private static long readUnsigned(byte[] packet, int offset) {
      return ((long) (packet[offset] & 0xff) << 24)
          | ((long) (packet[offset + 1] & 0xff) << 16)
          | ((long) (packet[offset + 2] & 0xff) << 8)
          | (packet[offset + 3] & 0xff);
    }

    static Reading parse(byte[] packet, double t1, double t4) {
      if (packet.length < 48) {
        throw new IllegalArgumentException("Malformed NTP response: expected 48+ bytes");
      }
      int leapIndicator = (packet[0] >> 6) & 0x03;
      int stratum = packet[1] & 0xff;
      double rootDispersion = readUnsigned(packet, 8) / 65536.0 * 1000.0;
      String refId;
      if (stratum <= 1) {
        // A reference clock names itself in four printable characters.
        StringBuilder text = new StringBuilder();
        for (int i = 12; i < 16; i++) {
          if (packet[i] != 0) {
            text.append((char) (packet[i] & 0xff));
          }
        }
        refId = text.toString();
      } else {
        refId =
            (packet[12] & 0xff)
                + "."
                + (packet[13] & 0xff)
                + "."
                + (packet[14] & 0xff)
                + "."
                + (packet[15] & 0xff);
      }
      double t2 = readTimestamp(packet, 32);
      double t3 = readTimestamp(packet, 40);
      double offset = ((t2 - t1) + (t3 - t4)) / 2.0;
      double roundTrip = (t4 - t1) - (t3 - t2);
      return new Reading(leapIndicator, stratum, refId, offset, roundTrip, rootDispersion);
    }

    static Reading query(String host, int port, int timeoutMillis) throws Exception {
      try (DatagramSocket socket = new DatagramSocket()) {
        socket.setSoTimeout(timeoutMillis);
        byte[] request = createPacket();
        double t1 = System.currentTimeMillis();
        socket.send(
            new DatagramPacket(request, request.length, InetAddress.getByName(host), port));
        byte[] buffer = new byte[48];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        socket.receive(reply);
        double t4 = System.currentTimeMillis();
        return parse(buffer, t1, t4);
      }
    }
  }

  /** One RADIUS Access-Request, and the code the server answered with. */
  static final class Radius {
    private Radius() {}

    private static final int ACCESS_REQUEST = 1;

    static String accessRequest(
        String host,
        int port,
        String username,
        String password,
        String secret,
        String calledStationId,
        String callingStationId,
        int timeoutMillis)
        throws Exception {
      byte[] authenticator = new byte[16];
      new java.security.SecureRandom().nextBytes(authenticator);
      java.io.ByteArrayOutputStream attributes = new java.io.ByteArrayOutputStream();
      writeAttribute(attributes, 1, username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      writeAttribute(
          attributes, 2, encryptPassword(password, secret, authenticator));
      if (calledStationId != null && !calledStationId.isEmpty()) {
        writeAttribute(
            attributes, 30, calledStationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      if (callingStationId != null && !callingStationId.isEmpty()) {
        writeAttribute(
            attributes, 31, callingStationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      byte[] attributeBytes = attributes.toByteArray();
      int length = 20 + attributeBytes.length;
      java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
      packet.write(ACCESS_REQUEST);
      packet.write(1);
      packet.write((length >> 8) & 0xff);
      packet.write(length & 0xff);
      packet.write(authenticator);
      packet.write(attributeBytes);
      byte[] request = packet.toByteArray();

      try (DatagramSocket socket = new DatagramSocket()) {
        socket.setSoTimeout(timeoutMillis);
        socket.send(
            new DatagramPacket(request, request.length, InetAddress.getByName(host), port));
        byte[] buffer = new byte[4096];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        socket.receive(reply);
        return codeName(buffer[0] & 0xff);
      }
    }

    private static void writeAttribute(
        java.io.ByteArrayOutputStream out, int type, byte[] value) {
      out.write(type);
      out.write(value.length + 2);
      out.write(value, 0, value.length);
    }

    /**
     * The password is exclusive-ored with a chain of digests, sixteen bytes at a time — the
     * protocol has no encryption of its own, so this is what keeps it off the wire in the clear.
     */
    private static byte[] encryptPassword(String password, String secret, byte[] authenticator)
        throws Exception {
      byte[] plain = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      int padded = ((plain.length + 15) / 16) * 16;
      if (padded == 0) {
        padded = 16;
      }
      byte[] buffer = new byte[padded];
      System.arraycopy(plain, 0, buffer, 0, plain.length);
      byte[] secretBytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] previous = authenticator;
      byte[] out = new byte[padded];
      for (int block = 0; block < padded / 16; block++) {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        digest.update(secretBytes);
        digest.update(previous);
        byte[] hash = digest.digest();
        byte[] chunk = new byte[16];
        for (int i = 0; i < 16; i++) {
          chunk[i] = (byte) (buffer[block * 16 + i] ^ hash[i]);
        }
        System.arraycopy(chunk, 0, out, block * 16, 16);
        previous = chunk;
      }
      return out;
    }

    private static String codeName(int code) {
      return switch (code) {
        case 2 -> "Access-Accept";
        case 3 -> "Access-Reject";
        case 11 -> "Access-Challenge";
        default -> "Code-" + code;
      };
    }
  }
}
