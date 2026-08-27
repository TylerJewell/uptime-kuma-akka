package io.akka.uptimekuma.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Everything a monitor is configured with.
 *
 * <p>The component names are the source's own, from {@code Monitor.toJSON()} — a mixture of camel
 * case and snake case, because that is what the interface reads. The interface is shipped
 * unchanged (RENDERING.md R3), so a field renamed here to look tidier is a field a screen stops
 * finding.
 *
 * <p>Unknown properties are ignored rather than refused: the interface posts back the whole monitor
 * object it was given, including three keys it adds for its own use ({@code humanReadableInterval},
 * {@code globalpingdnsresolvetypeoptions}, {@code responsecheck}) which the source deletes on
 * arrival.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonitorConfig(
    String id,
    String name,
    String description,
    List<String> path,
    String pathName,
    String parent,
    List<String> childrenIDs,
    String url,
    boolean wsIgnoreSecWebsocketAcceptHeader,
    String wsSubprotocol,
    String method,
    String hostname,
    Integer port,
    String location,
    String protocol,
    int maxretries,
    int weight,
    boolean active,
    boolean forceInactive,
    String type,
    String subtype,
    double timeout,
    int interval,
    int retryInterval,
    boolean retryOnlyOnStatusCodeFailure,
    int resendInterval,
    String keyword,
    boolean invertKeyword,
    boolean expiryNotification,
    boolean domainExpiryNotification,
    boolean ignoreTls,
    boolean upsideDown,
    int packetSize,
    int maxredirects,
    List<String> accepted_statuscodes,
    String dns_resolve_type,
    String dns_resolve_server,
    String dns_last_result,
    String docker_container,
    String docker_host,
    String proxyId,
    Map<String, Boolean> notificationIDList,
    List<Map<String, Object>> tags,
    boolean maintenance,
    String mqttTopic,
    String mqttSuccessMessage,
    String mqttCheckType,
    String databaseQuery,
    String authMethod,
    String grpcUrl,
    String grpcProtobuf,
    String grpcMethod,
    String grpcServiceName,
    boolean grpcEnableTls,
    String radiusCalledStationId,
    String radiusCallingStationId,
    String game,
    boolean gamedigGivenPortOnly,
    String httpBodyEncoding,
    String jsonPath,
    String expectedValue,
    String system_service_name,
    String kafkaProducerTopic,
    List<String> kafkaProducerBrokers,
    boolean kafkaProducerSsl,
    boolean kafkaProducerAllowAutoTopicCreation,
    String kafkaProducerMessage,
    String screenshot,
    boolean cacheBust,
    String remote_browser,
    int screenshot_delay,
    String snmpOid,
    String jsonPathOperator,
    String snmpVersion,
    String snmp_v3_username,
    String smtpSecurity,
    List<String> rabbitmqNodes,
    List<Map<String, Object>> conditions,
    int ntpStratumThreshold,
    int ntpTimeOffsetThreshold,
    int ntpRootDispersionThreshold,
    String ipFamily,
    String expectedTlsAlert,
    Integer manual_status,
    boolean ping_numeric,
    int ping_count,
    int ping_per_request_timeout,
    boolean saveResponse,
    boolean saveErrorResponse,
    int responseMaxLength,
    // The sensitive half. Present everywhere except the monitor object handed to a notification
    // provider, which the source builds with includeSensitiveData off.
    String headers,
    String body,
    String grpcBody,
    String grpcMetadata,
    String basic_auth_user,
    String basic_auth_pass,
    String oauth_client_id,
    String oauth_client_secret,
    String oauth_token_url,
    String oauth_scopes,
    String oauth_audience,
    String oauth_auth_method,
    String bearer_token,
    String gamedigToken,
    String pushToken,
    String databaseConnectionString,
    String radiusUsername,
    String radiusPassword,
    String radiusSecret,
    String mqttUsername,
    String mqttPassword,
    String mqttWebsocketPath,
    String authWorkstation,
    String authDomain,
    String tlsCa,
    String tlsCert,
    String tlsKey,
    Map<String, Object> kafkaProducerSaslOptions,
    String rabbitmqUsername,
    String rabbitmqPassword,
    String customUrl,
    String rssTitle,
    boolean includeSensitiveData) {

  /** The set of type strings the source can execute. Anything else is "Unknown Monitor Type". */
  public static final List<String> TYPES =
      List.of(
          "http", "keyword", "json-query", "ping", "push", "docker", "radius", "kafka-producer",
          "real-browser", "tailscale-ping", "websocket-upgrade", "dns", "postgres", "mqtt", "smtp",
          "group", "snmp", "grpc-keyword", "mongodb", "rabbitmq", "sip-options", "gamedig", "steam",
          "port", "manual", "globalping", "redis", "pm2", "system-service", "sqlserver", "mysql",
          "oracledb", "ntp");

  /** Types whose check reads a condition tree. */
  public static final List<String> SUPPORTS_CONDITIONS =
      List.of("dns", "mqtt", "sqlserver", "mysql", "oracledb");

  /** Types whose check may write a status other than UP without throwing. */
  public static final List<String> ALLOWS_CUSTOM_STATUS = List.of("group", "manual");

  public static final int MIN_INTERVAL_SECOND = 1;
  public static final int PING_PACKET_SIZE_MIN = 1;
  public static final int PING_PACKET_SIZE_MAX = 65500;
  public static final int PING_PACKET_SIZE_DEFAULT = 56;
  public static final int PING_GLOBAL_TIMEOUT_MIN = 1;
  public static final int PING_GLOBAL_TIMEOUT_MAX = 300;
  public static final int PING_GLOBAL_TIMEOUT_DEFAULT = 10;
  public static final int PING_COUNT_MIN = 1;
  public static final int PING_COUNT_MAX = 100;
  public static final int PING_COUNT_DEFAULT = 1;
  public static final int PING_PER_REQUEST_TIMEOUT_MIN = 1;
  public static final int PING_PER_REQUEST_TIMEOUT_MAX = 60;
  public static final int PING_PER_REQUEST_TIMEOUT_DEFAULT = 2;
  public static final int RESPONSE_BODY_LENGTH_DEFAULT = 1024;
  public static final int RESPONSE_BODY_LENGTH_MAX = 1024 * 1024;

  /** The defaults the source's schema gives a monitor row nobody has filled in. */
  public static MonitorConfig blank(String id) {
    return new Builder(id).build();
  }

  /**
   * The checks the source runs before it will store a monitor.
   *
   * @return the refusal message, or null when the monitor is storable
   */
  /**
   * What the source refuses to store, in the source's own words.
   *
   * <p>Two things it deliberately does **not** check are worth naming, because checking them would
   * be an improvement and an improvement is a divergence. It does not check the type — an unknown
   * type is stored happily and reported as {@code Unknown Monitor Type} by the beat that tries to
   * run it — and each ping bound is skipped when the field is absent or zero rather than being
   * treated as out of range. Every message below is the string the source throws, including where
   * two of them differ only in capitalisation.
   */
  public String validate() {
    if (interval < MIN_INTERVAL_SECOND) {
      return "Interval cannot be less than " + MIN_INTERVAL_SECOND + " seconds";
    }
    if (retryInterval < MIN_INTERVAL_SECOND) {
      return "Retry interval cannot be less than " + MIN_INTERVAL_SECOND + " seconds";
    }
    if (responseMaxLength < 0) {
      return "Response max length cannot be less than 0";
    }
    if (responseMaxLength > RESPONSE_BODY_LENGTH_MAX) {
      return "Response max length cannot be more than " + RESPONSE_BODY_LENGTH_MAX + " bytes";
    }
    if (("system-service".equals(type) || "pm2".equals(type))
        && (system_service_name == null || system_service_name.isBlank())) {
      return "pm2".equals(type) ? "PM2 process name is required." : "Service Name is required.";
    }
    if ("system-service".equals(type)
        && !system_service_name.matches("^[a-zA-Z0-9._\\-@]+$")) {
      return "Invalid service name. Please use the internal Service Name (no spaces).";
    }
    if ("pm2".equals(type) && system_service_name.matches(".*[\\x00-\\x1F\\x7F].*")) {
      return "Invalid PM2 process name.";
    }
    if ("ping".equals(type)) {
      if (packetSize != 0 && (packetSize < PING_PACKET_SIZE_MIN || packetSize > PING_PACKET_SIZE_MAX)) {
        return "Packet size must be between "
            + PING_PACKET_SIZE_MIN
            + " and "
            + PING_PACKET_SIZE_MAX
            + " (default: "
            + PING_PACKET_SIZE_DEFAULT
            + ")";
      }
      if (ping_per_request_timeout != 0
          && (ping_per_request_timeout < PING_PER_REQUEST_TIMEOUT_MIN
              || ping_per_request_timeout > PING_PER_REQUEST_TIMEOUT_MAX)) {
        return "Per-ping timeout must be between "
            + PING_PER_REQUEST_TIMEOUT_MIN
            + " and "
            + PING_PER_REQUEST_TIMEOUT_MAX
            + " seconds (default: "
            + PING_PER_REQUEST_TIMEOUT_DEFAULT
            + ")";
      }
      if (ping_count != 0 && (ping_count < PING_COUNT_MIN || ping_count > PING_COUNT_MAX)) {
        return "Echo requests count must be between "
            + PING_COUNT_MIN
            + " and "
            + PING_COUNT_MAX
            + " (default: "
            + PING_COUNT_DEFAULT
            + ")";
      }
      if (timeout != 0) {
        long rounded = Math.round(timeout);
        if (rounded < ping_per_request_timeout
            || rounded < PING_GLOBAL_TIMEOUT_MIN
            || rounded > PING_GLOBAL_TIMEOUT_MAX) {
          return "Timeout must be between "
              + PING_GLOBAL_TIMEOUT_MIN
              + " and "
              + PING_GLOBAL_TIMEOUT_MAX
              + " seconds (default: "
              + PING_GLOBAL_TIMEOUT_DEFAULT
              + ")";
        }
      }
    }
    if ("real-browser".equals(type) && screenshot_delay != 0) {
      if (screenshot_delay < 0) {
        return "Screenshot delay must be a non-negative number";
      }
      double fromTimeout = interval * 1000 * 0.8;
      if (screenshot_delay >= fromTimeout) {
        return "Screenshot delay must be less than " + trim(fromTimeout) + "ms (0.8 × interval)";
      }
      double fromInterval = interval * 1000 * 0.5;
      if (screenshot_delay >= fromInterval) {
        return "Screenshot delay must be less than " + trim(fromInterval) + "ms (0.5 × interval)";
      }
    }
    return null;
  }

  /** A whole number without the trailing decimal JavaScript would not print either. */
  private static String trim(double value) {
    return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
  }

  /**
   * The timeout a check should actually use.
   *
   * <p>A monitor stored with no timeout is patched at beat time to eight tenths of its interval —
   * computed in milliseconds and then consumed as seconds, which is the source's own arithmetic and
   * is reproduced rather than corrected. Fixing it here would give every workload comparing the two
   * a different timeout on one side.
   */
  public double effectiveTimeout() {
    return timeout <= 0 ? interval * 1000 * 0.8 : timeout;
  }

  /** The monitor as the source hands it to a notification provider: no secrets. */
  public MonitorConfig withoutSensitiveData() {
    return toBuilder().clearSensitiveData().build();
  }

  public MonitorConfig withId(String replacement) {
    return toBuilder().id(replacement).build();
  }

  public MonitorConfig withActive(boolean running) {
    return toBuilder().active(running).build();
  }

  public MonitorConfig withMaintenance(boolean under) {
    return toBuilder().maintenance(under).build();
  }

  public MonitorConfig withDnsLastResult(String result) {
    return toBuilder().dns_last_result(result).build();
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * A mutable staging area for a record with a hundred and thirty components.
   *
   * <p>Every {@code with…} above would otherwise be a hundred-and-thirty-argument constructor call
   * whose only difference from its neighbour is one position, and a component inserted in the
   * middle would silently shift every one of them by one.
   */
  public static final class Builder {
    private String id;
    private String name;
    private String description;
    private List<String> path = List.of();
    private String pathName = "";
    private String parent;
    private List<String> childrenIDs = List.of();
    private String url;
    private boolean wsIgnoreSecWebsocketAcceptHeader;
    private String wsSubprotocol = "";
    private String method = "GET";
    private String hostname;
    private Integer port;
    private String location;
    private String protocol;
    private int maxretries = 0;
    private int weight = 2000;
    private boolean active = true;
    private boolean forceInactive = false;
    private String type = "http";
    private String subtype;
    private double timeout = 0d;
    private int interval = 60;
    private int retryInterval = 60;
    private boolean retryOnlyOnStatusCodeFailure = false;
    private int resendInterval = 0;
    private String keyword;
    private boolean invertKeyword = false;
    private boolean expiryNotification = true;
    private boolean domainExpiryNotification = false;
    private boolean ignoreTls = false;
    private boolean upsideDown = false;
    private int packetSize = 56;
    private int maxredirects = 10;
    private List<String> accepted_statuscodes = List.of("200-299");
    private String dns_resolve_type;
    private String dns_resolve_server;
    private String dns_last_result;
    private String docker_container;
    private String docker_host;
    private String proxyId;
    private Map<String, Boolean> notificationIDList = Map.of();
    private List<Map<String, Object>> tags = List.of();
    private boolean maintenance = false;
    private String mqttTopic;
    private String mqttSuccessMessage;
    private String mqttCheckType = "keyword";
    private String databaseQuery;
    private String authMethod;
    private String grpcUrl;
    private String grpcProtobuf;
    private String grpcMethod;
    private String grpcServiceName;
    private boolean grpcEnableTls = false;
    private String radiusCalledStationId;
    private String radiusCallingStationId;
    private String game;
    private boolean gamedigGivenPortOnly = true;
    private String httpBodyEncoding = "json";
    private String jsonPath;
    private String expectedValue;
    private String system_service_name;
    private String kafkaProducerTopic;
    private List<String> kafkaProducerBrokers = List.of();
    private boolean kafkaProducerSsl = false;
    private boolean kafkaProducerAllowAutoTopicCreation = false;
    private String kafkaProducerMessage;
    private String screenshot;
    private boolean cacheBust = false;
    private String remote_browser;
    private int screenshot_delay = 0;
    private String snmpOid;
    private String jsonPathOperator;
    private String snmpVersion = "2c";
    private String snmp_v3_username;
    private String smtpSecurity;
    private List<String> rabbitmqNodes = List.of();
    private List<Map<String, Object>> conditions = List.of();
    private int ntpStratumThreshold = 5;
    private int ntpTimeOffsetThreshold = 1000;
    private int ntpRootDispersionThreshold = 500;
    private String ipFamily;
    private String expectedTlsAlert;
    private Integer manual_status;
    private boolean ping_numeric = true;
    private int ping_count = 1;
    private int ping_per_request_timeout = 2;
    private boolean saveResponse = false;
    private boolean saveErrorResponse = true;
    private int responseMaxLength = RESPONSE_BODY_LENGTH_DEFAULT;
    private String headers;
    private String body;
    private String grpcBody;
    private String grpcMetadata;
    private String basic_auth_user;
    private String basic_auth_pass;
    private String oauth_client_id;
    private String oauth_client_secret;
    private String oauth_token_url;
    private String oauth_scopes;
    private String oauth_audience;
    private String oauth_auth_method;
    private String bearer_token;
    private String gamedigToken;
    private String pushToken;
    private String databaseConnectionString;
    private String radiusUsername;
    private String radiusPassword;
    private String radiusSecret;
    private String mqttUsername;
    private String mqttPassword;
    private String mqttWebsocketPath;
    private String authWorkstation;
    private String authDomain;
    private String tlsCa;
    private String tlsCert;
    private String tlsKey;
    private Map<String, Object> kafkaProducerSaslOptions = Map.of();
    private String rabbitmqUsername;
    private String rabbitmqPassword;
    private String customUrl;
    private String rssTitle;
    private boolean includeSensitiveData = true;

    public Builder(String id) {
      this.id = id;
    }

    private Builder(MonitorConfig c) {
      id = c.id;
      name = c.name;
      description = c.description;
      path = c.path;
      pathName = c.pathName;
      parent = c.parent;
      childrenIDs = c.childrenIDs;
      url = c.url;
      wsIgnoreSecWebsocketAcceptHeader = c.wsIgnoreSecWebsocketAcceptHeader;
      wsSubprotocol = c.wsSubprotocol;
      method = c.method;
      hostname = c.hostname;
      port = c.port;
      location = c.location;
      protocol = c.protocol;
      maxretries = c.maxretries;
      weight = c.weight;
      active = c.active;
      forceInactive = c.forceInactive;
      type = c.type;
      subtype = c.subtype;
      timeout = c.timeout;
      interval = c.interval;
      retryInterval = c.retryInterval;
      retryOnlyOnStatusCodeFailure = c.retryOnlyOnStatusCodeFailure;
      resendInterval = c.resendInterval;
      keyword = c.keyword;
      invertKeyword = c.invertKeyword;
      expiryNotification = c.expiryNotification;
      domainExpiryNotification = c.domainExpiryNotification;
      ignoreTls = c.ignoreTls;
      upsideDown = c.upsideDown;
      packetSize = c.packetSize;
      maxredirects = c.maxredirects;
      accepted_statuscodes = c.accepted_statuscodes;
      dns_resolve_type = c.dns_resolve_type;
      dns_resolve_server = c.dns_resolve_server;
      dns_last_result = c.dns_last_result;
      docker_container = c.docker_container;
      docker_host = c.docker_host;
      proxyId = c.proxyId;
      notificationIDList = c.notificationIDList;
      tags = c.tags;
      maintenance = c.maintenance;
      mqttTopic = c.mqttTopic;
      mqttSuccessMessage = c.mqttSuccessMessage;
      mqttCheckType = c.mqttCheckType;
      databaseQuery = c.databaseQuery;
      authMethod = c.authMethod;
      grpcUrl = c.grpcUrl;
      grpcProtobuf = c.grpcProtobuf;
      grpcMethod = c.grpcMethod;
      grpcServiceName = c.grpcServiceName;
      grpcEnableTls = c.grpcEnableTls;
      radiusCalledStationId = c.radiusCalledStationId;
      radiusCallingStationId = c.radiusCallingStationId;
      game = c.game;
      gamedigGivenPortOnly = c.gamedigGivenPortOnly;
      httpBodyEncoding = c.httpBodyEncoding;
      jsonPath = c.jsonPath;
      expectedValue = c.expectedValue;
      system_service_name = c.system_service_name;
      kafkaProducerTopic = c.kafkaProducerTopic;
      kafkaProducerBrokers = c.kafkaProducerBrokers;
      kafkaProducerSsl = c.kafkaProducerSsl;
      kafkaProducerAllowAutoTopicCreation = c.kafkaProducerAllowAutoTopicCreation;
      kafkaProducerMessage = c.kafkaProducerMessage;
      screenshot = c.screenshot;
      cacheBust = c.cacheBust;
      remote_browser = c.remote_browser;
      screenshot_delay = c.screenshot_delay;
      snmpOid = c.snmpOid;
      jsonPathOperator = c.jsonPathOperator;
      snmpVersion = c.snmpVersion;
      snmp_v3_username = c.snmp_v3_username;
      smtpSecurity = c.smtpSecurity;
      rabbitmqNodes = c.rabbitmqNodes;
      conditions = c.conditions;
      ntpStratumThreshold = c.ntpStratumThreshold;
      ntpTimeOffsetThreshold = c.ntpTimeOffsetThreshold;
      ntpRootDispersionThreshold = c.ntpRootDispersionThreshold;
      ipFamily = c.ipFamily;
      expectedTlsAlert = c.expectedTlsAlert;
      manual_status = c.manual_status;
      ping_numeric = c.ping_numeric;
      ping_count = c.ping_count;
      ping_per_request_timeout = c.ping_per_request_timeout;
      saveResponse = c.saveResponse;
      saveErrorResponse = c.saveErrorResponse;
      responseMaxLength = c.responseMaxLength;
      headers = c.headers;
      body = c.body;
      grpcBody = c.grpcBody;
      grpcMetadata = c.grpcMetadata;
      basic_auth_user = c.basic_auth_user;
      basic_auth_pass = c.basic_auth_pass;
      oauth_client_id = c.oauth_client_id;
      oauth_client_secret = c.oauth_client_secret;
      oauth_token_url = c.oauth_token_url;
      oauth_scopes = c.oauth_scopes;
      oauth_audience = c.oauth_audience;
      oauth_auth_method = c.oauth_auth_method;
      bearer_token = c.bearer_token;
      gamedigToken = c.gamedigToken;
      pushToken = c.pushToken;
      databaseConnectionString = c.databaseConnectionString;
      radiusUsername = c.radiusUsername;
      radiusPassword = c.radiusPassword;
      radiusSecret = c.radiusSecret;
      mqttUsername = c.mqttUsername;
      mqttPassword = c.mqttPassword;
      mqttWebsocketPath = c.mqttWebsocketPath;
      authWorkstation = c.authWorkstation;
      authDomain = c.authDomain;
      tlsCa = c.tlsCa;
      tlsCert = c.tlsCert;
      tlsKey = c.tlsKey;
      kafkaProducerSaslOptions = c.kafkaProducerSaslOptions;
      rabbitmqUsername = c.rabbitmqUsername;
      rabbitmqPassword = c.rabbitmqPassword;
      customUrl = c.customUrl;
      rssTitle = c.rssTitle;
      includeSensitiveData = c.includeSensitiveData;
    }

    public Builder id(String v) {
      id = v;
      return this;
    }

    public Builder name(String v) {
      name = v;
      return this;
    }

    public Builder type(String v) {
      type = v;
      return this;
    }

    public Builder url(String v) {
      url = v;
      return this;
    }

    public Builder hostname(String v) {
      hostname = v;
      return this;
    }

    public Builder port(Integer v) {
      port = v;
      return this;
    }

    public Builder keyword(String v) {
      keyword = v;
      return this;
    }

    public Builder invertKeyword(boolean v) {
      invertKeyword = v;
      return this;
    }

    public Builder interval(int v) {
      interval = v;
      return this;
    }

    public Builder retryInterval(int v) {
      retryInterval = v;
      return this;
    }

    public Builder maxretries(int v) {
      maxretries = v;
      return this;
    }

    public Builder resendInterval(int v) {
      resendInterval = v;
      return this;
    }

    public Builder upsideDown(boolean v) {
      upsideDown = v;
      return this;
    }

    public Builder active(boolean v) {
      active = v;
      return this;
    }

    public Builder maintenance(boolean v) {
      maintenance = v;
      return this;
    }

    public Builder acceptedStatusCodes(List<String> v) {
      accepted_statuscodes = v;
      return this;
    }

    public Builder timeout(double v) {
      timeout = v;
      return this;
    }

    public Builder method(String v) {
      method = v;
      return this;
    }

    public Builder body(String v) {
      body = v;
      return this;
    }

    public Builder headers(String v) {
      headers = v;
      return this;
    }

    public Builder jsonPath(String v) {
      jsonPath = v;
      return this;
    }

    public Builder jsonPathOperator(String v) {
      jsonPathOperator = v;
      return this;
    }

    public Builder expectedValue(String v) {
      expectedValue = v;
      return this;
    }

    public Builder retryOnlyOnStatusCodeFailure(boolean v) {
      retryOnlyOnStatusCodeFailure = v;
      return this;
    }

    public Builder manualStatus(Integer v) {
      manual_status = v;
      return this;
    }

    public Builder parent(String v) {
      parent = v;
      return this;
    }

    public Builder childrenIDs(List<String> v) {
      childrenIDs = v;
      return this;
    }

    public Builder pushToken(String v) {
      pushToken = v;
      return this;
    }

    public Builder conditions(List<Map<String, Object>> v) {
      conditions = v;
      return this;
    }

    public Builder dnsResolveType(String v) {
      dns_resolve_type = v;
      return this;
    }

    public Builder dnsResolveServer(String v) {
      dns_resolve_server = v;
      return this;
    }

    public Builder dns_last_result(String v) {
      dns_last_result = v;
      return this;
    }

    public Builder notificationIDList(Map<String, Boolean> v) {
      notificationIDList = v;
      return this;
    }

    public Builder ignoreTls(boolean v) {
      ignoreTls = v;
      return this;
    }

    public Builder maxredirects(int v) {
      maxredirects = v;
      return this;
    }

    public Builder expiryNotification(boolean v) {
      expiryNotification = v;
      return this;
    }

    public Builder domainExpiryNotification(boolean v) {
      domainExpiryNotification = v;
      return this;
    }

    public Builder databaseConnectionString(String v) {
      databaseConnectionString = v;
      return this;
    }

    public Builder databaseQuery(String v) {
      databaseQuery = v;
      return this;
    }

    public Builder saveResponse(boolean v) {
      saveResponse = v;
      return this;
    }

    public Builder saveErrorResponse(boolean v) {
      saveErrorResponse = v;
      return this;
    }

    public Builder responseMaxLength(int v) {
      responseMaxLength = v;
      return this;
    }

    public Builder weight(int v) {
      weight = v;
      return this;
    }

    public Builder description(String v) {
      description = v;
      return this;
    }

    public Builder path(List<String> v) {
      path = v;
      return this;
    }

    public Builder pathName(String v) {
      pathName = v;
      return this;
    }

    public Builder tags(List<Map<String, Object>> v) {
      tags = v;
      return this;
    }

    public Builder screenshot(String v) {
      screenshot = v;
      return this;
    }

    public Builder forceInactive(boolean v) {
      forceInactive = v;
      return this;
    }

    public Builder basicAuthUser(String v) {
      basic_auth_user = v;
      return this;
    }

    public Builder basicAuthPass(String v) {
      basic_auth_pass = v;
      return this;
    }

    public Builder bearerToken(String v) {
      bearer_token = v;
      return this;
    }

    public Builder authMethod(String v) {
      authMethod = v;
      return this;
    }

    public Builder httpBodyEncoding(String v) {
      httpBodyEncoding = v;
      return this;
    }

    public Builder cacheBust(boolean v) {
      cacheBust = v;
      return this;
    }

    public Builder game(String v) {
      game = v;
      return this;
    }

    public Builder systemServiceName(String v) {
      system_service_name = v;
      return this;
    }

    public Builder subtype(String v) {
      subtype = v;
      return this;
    }

    public Builder snmpOid(String v) {
      snmpOid = v;
      return this;
    }

    public Builder rabbitmqNodes(java.util.List<String> v) {
      rabbitmqNodes = v;
      return this;
    }

    public Builder smtpSecurity(String v) {
      smtpSecurity = v;
      return this;
    }

    public Builder expectedTlsAlert(String v) {
      expectedTlsAlert = v;
      return this;
    }

    public Builder dockerContainer(String v) {
      docker_container = v;
      return this;
    }

    public Builder dockerHost(String v) {
      docker_host = v;
      return this;
    }

    public Builder remoteBrowser(String v) {
      remote_browser = v;
      return this;
    }

    public Builder proxyId(String v) {
      proxyId = v;
      return this;
    }

    public Builder mqttTopic(String v) {
      mqttTopic = v;
      return this;
    }

    public Builder mqttSuccessMessage(String v) {
      mqttSuccessMessage = v;
      return this;
    }

    public Builder mqttCheckType(String v) {
      mqttCheckType = v;
      return this;
    }

    public Builder pingCount(int v) {
      ping_count = v;
      return this;
    }

    public Builder packetSize(int v) {
      packetSize = v;
      return this;
    }

    public Builder pingPerRequestTimeout(int v) {
      ping_per_request_timeout = v;
      return this;
    }

    public Builder screenshotDelay(int v) {
      screenshot_delay = v;
      return this;
    }

    public Builder ntpThresholds(int stratum, int offset, int dispersion) {
      ntpStratumThreshold = stratum;
      ntpTimeOffsetThreshold = offset;
      ntpRootDispersionThreshold = dispersion;
      return this;
    }

    /** Blank every field the source withholds from a notification provider. */
    public Builder clearSensitiveData() {
      headers = null;
      body = null;
      grpcBody = null;
      grpcMetadata = null;
      basic_auth_user = null;
      basic_auth_pass = null;
      oauth_client_id = null;
      oauth_client_secret = null;
      oauth_token_url = null;
      oauth_scopes = null;
      oauth_audience = null;
      oauth_auth_method = null;
      bearer_token = null;
      gamedigToken = null;
      pushToken = null;
      databaseConnectionString = null;
      radiusUsername = null;
      radiusPassword = null;
      radiusSecret = null;
      mqttUsername = null;
      mqttPassword = null;
      mqttWebsocketPath = null;
      authWorkstation = null;
      authDomain = null;
      tlsCa = null;
      tlsCert = null;
      tlsKey = null;
      kafkaProducerSaslOptions = Map.of();
      rabbitmqUsername = null;
      rabbitmqPassword = null;
      includeSensitiveData = false;
      return this;
    }

    public MonitorConfig build() {
      return new MonitorConfig(
          id,
          name,
          description,
          path,
          pathName,
          parent,
          childrenIDs,
          url,
          wsIgnoreSecWebsocketAcceptHeader,
          wsSubprotocol,
          method,
          hostname,
          port,
          location,
          protocol,
          maxretries,
          weight,
          active,
          forceInactive,
          type,
          subtype,
          timeout,
          interval,
          retryInterval,
          retryOnlyOnStatusCodeFailure,
          resendInterval,
          keyword,
          invertKeyword,
          expiryNotification,
          domainExpiryNotification,
          ignoreTls,
          upsideDown,
          packetSize,
          maxredirects,
          accepted_statuscodes,
          dns_resolve_type,
          dns_resolve_server,
          dns_last_result,
          docker_container,
          docker_host,
          proxyId,
          notificationIDList,
          tags,
          maintenance,
          mqttTopic,
          mqttSuccessMessage,
          mqttCheckType,
          databaseQuery,
          authMethod,
          grpcUrl,
          grpcProtobuf,
          grpcMethod,
          grpcServiceName,
          grpcEnableTls,
          radiusCalledStationId,
          radiusCallingStationId,
          game,
          gamedigGivenPortOnly,
          httpBodyEncoding,
          jsonPath,
          expectedValue,
          system_service_name,
          kafkaProducerTopic,
          kafkaProducerBrokers,
          kafkaProducerSsl,
          kafkaProducerAllowAutoTopicCreation,
          kafkaProducerMessage,
          screenshot,
          cacheBust,
          remote_browser,
          screenshot_delay,
          snmpOid,
          jsonPathOperator,
          snmpVersion,
          snmp_v3_username,
          smtpSecurity,
          rabbitmqNodes,
          conditions,
          ntpStratumThreshold,
          ntpTimeOffsetThreshold,
          ntpRootDispersionThreshold,
          ipFamily,
          expectedTlsAlert,
          manual_status,
          ping_numeric,
          ping_count,
          ping_per_request_timeout,
          saveResponse,
          saveErrorResponse,
          responseMaxLength,
          headers,
          body,
          grpcBody,
          grpcMetadata,
          basic_auth_user,
          basic_auth_pass,
          oauth_client_id,
          oauth_client_secret,
          oauth_token_url,
          oauth_scopes,
          oauth_audience,
          oauth_auth_method,
          bearer_token,
          gamedigToken,
          pushToken,
          databaseConnectionString,
          radiusUsername,
          radiusPassword,
          radiusSecret,
          mqttUsername,
          mqttPassword,
          mqttWebsocketPath,
          authWorkstation,
          authDomain,
          tlsCa,
          tlsCert,
          tlsKey,
          kafkaProducerSaslOptions,
          rabbitmqUsername,
          rabbitmqPassword,
          customUrl,
          rssTitle,
          includeSensitiveData);
    }
  }
}
