package io.akka.uptimekuma.api;

import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.uptimekuma.application.RecordFields;
import io.akka.uptimekuma.application.ApiKeyEntity;
import io.akka.uptimekuma.application.ApiKeyListView;
import io.akka.uptimekuma.application.DockerHostEntity;
import io.akka.uptimekuma.application.DockerHostListView;
import io.akka.uptimekuma.application.HeartbeatAnnouncementEntity;
import io.akka.uptimekuma.application.HeartbeatFeedView;
import io.akka.uptimekuma.application.Ids;
import io.akka.uptimekuma.application.MaintenanceEntity;
import io.akka.uptimekuma.application.MaintenanceListView;
import io.akka.uptimekuma.application.Maintenances;
import io.akka.uptimekuma.application.MonitorBeat;
import io.akka.uptimekuma.application.MonitorEntity;
import io.akka.uptimekuma.application.MonitorListView;
import io.akka.uptimekuma.application.NotificationEntity;
import io.akka.uptimekuma.application.NotificationListView;
import io.akka.uptimekuma.application.Notifications;
import io.akka.uptimekuma.application.ProxyEntity;
import io.akka.uptimekuma.application.ProxyListView;
import io.akka.uptimekuma.application.RecordRow;
import io.akka.uptimekuma.application.RemoteBrowserEntity;
import io.akka.uptimekuma.application.RemoteBrowserListView;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.SettingsEntity;
import io.akka.uptimekuma.application.StatusPageEntity;
import io.akka.uptimekuma.application.StatusPageListView;
import io.akka.uptimekuma.application.StoredRecord;
import io.akka.uptimekuma.application.TagEntity;
import io.akka.uptimekuma.application.TagListView;
import io.akka.uptimekuma.application.UserEntity;
import io.akka.uptimekuma.application.Versions;
import io.akka.uptimekuma.checks.Checks;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.MaintenanceWindow;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.Passwords;
import io.akka.uptimekuma.domain.Totp;
import io.akka.uptimekuma.domain.UptimeCalculator;
import io.akka.uptimekuma.notifications.Config;
import io.akka.uptimekuma.notifications.Context;
import io.akka.uptimekuma.notifications.HttpSender;
import io.akka.uptimekuma.notifications.Json;
import io.akka.uptimekuma.notifications.Providers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every call the interface makes, and what it answers.
 *
 * <p>The names, the argument order and the shape of every answer are the source's, because the
 * interface is shipped unchanged — a renamed key is a screen that stops working. What is different
 * is only how the call arrives and how the messages that follow it get back, which is the data
 * layer this port replaces.
 *
 * @see SocketBridgeEndpoint for the transport
 */
public final class SocketHandlers {

  /**
   * @param payload what the interface's callback is handed
   * @param emissions the messages the source would have pushed as a consequence of this call,
   *     handed back with the answer so the interface's own handlers are reached
   */
  public record Reply(Object payload, List<Emission> emissions) {

    public static Reply of(Object payload, Emission... emissions) {
      return new Reply(payload, List.of(emissions));
    }
  }

  private final ComponentClient componentClient;
  private final akka.javasdk.timer.TimerScheduler timers;

  public SocketHandlers(ComponentClient componentClient, akka.javasdk.timer.TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  /** The events a caller who has not signed in may make. */
  private static final List<String> PUBLIC_EVENTS =
      List.of(
          "login",
          "loginByToken",
          "logout",
          "needSetup",
          "setup",
          "getWebpushVapidPublicKey",
          "getIncidentHistory");

  public boolean isPublic(String event) {
    return PUBLIC_EVENTS.contains(event);
  }

  public Reply handle(String event, List<Object> args, Sessions.Signed session) throws Exception {
    return switch (event) {
      case "needSetup" -> Reply.of(Sessions.needsSetup(componentClient));
      case "setup" -> setup(str(args, 0), str(args, 1));
      case "login" -> login(map(args, 0));
      case "loginByToken" -> loginByToken(str(args, 0));
      case "logout" -> Reply.of(null);

      case "getMonitorList" -> Reply.of(ok(), monitorList());
      case "getMonitor" -> getMonitor(str(args, 0));
      case "add" -> addMonitor(map(args, 0), session);
      case "editMonitor" -> editMonitor(map(args, 0), session);
      case "deleteMonitor" -> deleteMonitor(str(args, 0), bool(args, 1));
      case "pauseMonitor" -> pauseMonitor(str(args, 0));
      case "resumeMonitor" -> resumeMonitor(str(args, 0));
      case "getMonitorBeats" -> getMonitorBeats(str(args, 0), optionalNumber(args, 1));
      case "getMonitorChartData" -> getMonitorChartData(str(args, 0), number(args, 1));
      case "monitorImportantHeartbeatListCount" -> importantCount(str(args, 0));
      case "monitorImportantHeartbeatListPaged" ->
          importantPaged(str(args, 0), (int) number(args, 1), (int) number(args, 2));
      case "clearEvents" -> clearEvents(str(args, 0));
      case "clearHeartbeats" -> clearHeartbeats(str(args, 0));
      case "clearStatistics" -> clearStatistics();
      case "checkDomain" -> checkDomain(map(args, 0));

      case "getTags" -> getTags();
      case "addTag" -> addTag(map(args, 0));
      case "editTag" -> editTag(map(args, 0));
      case "deleteTag" -> deleteTag(str(args, 0));
      case "addMonitorTag" -> monitorTag(str(args, 0), str(args, 1), str(args, 2), "add");
      case "editMonitorTag" -> monitorTag(str(args, 0), str(args, 1), str(args, 2), "edit");
      case "deleteMonitorTag" -> monitorTag(str(args, 0), str(args, 1), str(args, 2), "delete");

      case "getSettings" -> getSettings();
      case "setSettings" -> setSettings(map(args, 0), str(args, 1), session);
      case "changePassword" -> changePassword(map(args, 0), session);

      case "addNotification" -> addNotification(map(args, 0), str(args, 1), session);
      case "deleteNotification" -> deleteNotification(str(args, 0), session);
      case "testNotification" -> testNotification(map(args, 0));
      case "checkApprise" -> Reply.of(io.akka.uptimekuma.notifications.Apprise.available());

      case "addProxy" -> addProxy(map(args, 0), str(args, 1), session);
      case "deleteProxy" -> deleteProxy(str(args, 0));

      case "addDockerHost" -> addDockerHost(map(args, 0), str(args, 1));
      case "deleteDockerHost" -> deleteDockerHost(str(args, 0));
      case "testDockerHost" -> testDockerHost(map(args, 0));

      case "addRemoteBrowser" -> addRemoteBrowser(map(args, 0), str(args, 1));
      case "deleteRemoteBrowser" -> deleteRemoteBrowser(str(args, 0));
      case "testRemoteBrowser" -> testRemoteBrowser(map(args, 0));

      case "addAPIKey" -> addApiKey(map(args, 0), session);
      case "getAPIKeyList" -> Reply.of(ok(), apiKeyList());
      case "deleteAPIKey" -> deleteApiKey(str(args, 0));
      case "disableAPIKey" -> setApiKeyActive(str(args, 0), false);
      case "enableAPIKey" -> setApiKeyActive(str(args, 0), true);

      case "getMaintenanceList" -> Reply.of(ok(), maintenanceList());
      case "getMaintenance" -> getMaintenance(str(args, 0));
      case "addMaintenance" -> addMaintenance(map(args, 0), session);
      case "editMaintenance" -> editMaintenance(map(args, 0), session);
      case "deleteMaintenance" -> deleteMaintenance(str(args, 0));
      case "pauseMaintenance" -> setMaintenanceActive(str(args, 0), false);
      case "resumeMaintenance" -> setMaintenanceActive(str(args, 0), true);
      case "addMonitorMaintenance" -> linkMaintenance(str(args, 0), args.get(1), "monitors");
      case "addMaintenanceStatusPage" -> linkMaintenance(str(args, 0), args.get(1), "statusPages");
      case "getMonitorMaintenance" -> maintenanceLinks(str(args, 0), "monitors", "monitors");
      case "getMaintenanceStatusPage" -> maintenanceLinks(str(args, 0), "statusPages", "statusPages");

      case "addStatusPage" -> addStatusPage(str(args, 0), str(args, 1));
      case "getStatusPage" -> getStatusPage(str(args, 0));
      case "saveStatusPage" -> saveStatusPage(str(args, 0), map(args, 1), str(args, 2), args.get(3));
      case "deleteStatusPage" -> deleteStatusPage(str(args, 0));
      case "postIncident" -> postIncident(str(args, 0), map(args, 1));
      case "editIncident" -> editIncident(str(args, 0), str(args, 1), map(args, 2));
      case "deleteIncident" -> deleteIncident(str(args, 0), str(args, 1));
      case "resolveIncident" -> resolveIncident(str(args, 0), str(args, 1));
      case "unpinIncident" -> unpinIncident(str(args, 0));
      case "getIncidentHistory" -> incidentHistory(str(args, 0), str(args, 1));

      case "prepare2FA" -> prepare2fa(str(args, 0), session);
      case "save2FA" -> save2fa(str(args, 0), session);
      case "disable2FA" -> disable2fa(str(args, 0), session);
      case "verifyToken" -> verifyToken(str(args, 0), str(args, 1), session);
      case "twoFAStatus" -> twoFaStatus(session);

      case "getDatabaseSize" -> Reply.of(okWith("size", databaseSize()));
      case "shrinkDatabase" -> Reply.of(ok());

      case "initServerTimezone" -> initServerTimezone(str(args, 0));
      case "getGameList" -> Reply.of(okWith("gameList", gameList()));
      case "getPM2ProcessList" -> Reply.of(okWith("processList", List.of()));
      case "testChrome" -> testChrome(str(args, 0));
      case "getPushExample" -> pushExample(str(args, 0));
      case "disconnectOtherSocketClients" -> Reply.of(null);
      case "getWebpushVapidPublicKey" -> Reply.of(okWith("msg", webpushPublicKey()));

      case "cloudflared_join",
              "cloudflared_leave",
              "cloudflared_start",
              "cloudflared_stop",
              "cloudflared_removeToken" ->
          cloudflared(event);

      default -> Reply.of(failed(event + " is not a call this server answers"));
    };
  }

  // ---- accounts --------------------------------------------------------------------------------

  private Reply setup(String username, String password) {
    // The weak-password check comes first, so a second caller on a server that already has an
    // account is still told which of the two things is wrong with what they sent. The source's
    // order, and the reason it is visible: the two answers are different sentences.
    if (Passwords.tooWeak(password)) {
      return Reply.of(failedI18n("passwordTooWeak"));
    }
    if (!Sessions.needsSetup(componentClient)) {
      Map<String, Object> refusal =
          failed(
              "Uptime Kuma has been initialized. If you want to run setup again, please delete "
                  + "the database.");
      // The source's catch reports whether the message needs translating, and this one does not.
      refusal.put("msgi18n", false);
      return Reply.of(refusal);
    }
    // An account is stored under its own name, which is what makes signing in one read.
    String id = username.trim();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("username", username);
    fields.put("password", Passwords.generate(password));
    fields.put("active", true);
    fields.put("twofa_status", false);
    componentClient.forKeyValueEntity(id).method(UserEntity::put).invoke(RecordFields.of(fields));
    // Taken after the account is stored and before this answers, so a second caller arriving in
    // the gap is refused by the check above rather than by the account list, which lags. R101.
    Ids.nextNumber(componentClient, "user");
    return Reply.of(okI18n("successAdded"));
  }

  private Reply login(Map<String, Object> data) {
    String username = data == null ? null : String.valueOf(data.get("username"));
    String password = data == null ? null : String.valueOf(data.get("password"));
    Object tokenValue = data == null ? null : data.get("token");
    String token = tokenValue == null ? "" : String.valueOf(tokenValue);

    StoredRecord user = Sessions.byUsername(componentClient, username);
    if (user == null || !user.flag("active") || !Passwords.verify(password, user.str("password"))) {
      return Reply.of(failedI18n("authIncorrectCreds"));
    }
    // A stored hash of the older shape is rewritten the moment it verifies, so an account carried
    // over from a 1.x database stops depending on the legacy path after its owner next signs in.
    if (Passwords.needRehash(user.str("password"))) {
      componentClient
          .forKeyValueEntity(user.id())
          .method(UserEntity::patch)
          .invoke(RecordFields.of(Map.of("password", Passwords.generate(password))));
      user = componentClient.forKeyValueEntity(user.id()).method(UserEntity::get).invoke();
    }

    boolean twoFactor = user.flag("twofa_status");
    if (twoFactor && token.isEmpty()) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("tokenRequired", true);
      return Reply.of(payload);
    }
    if (!token.isEmpty()) {
      if (!Totp.verify(token, user.str("twofa_secret"), System.currentTimeMillis())
          || token.equals(user.str("twofa_last_token"))) {
        return Reply.of(failedI18n("authInvalidToken"));
      }
      componentClient
          .forKeyValueEntity(user.id())
          .method(UserEntity::patch)
          .invoke(RecordFields.of(Map.of("twofa_last_token", token)));
    }
    Map<String, Object> payload = ok();
    payload.put("token", Sessions.create(componentClient, user.str("username"), user.str("password")));
    return new Reply(payload, afterLogin());
  }

  private Reply loginByToken(String token) {
    Sessions.Signed signed = Sessions.verify(componentClient, token);
    if (signed == null) {
      return Reply.of(failedI18n("authInvalidToken"));
    }
    return new Reply(ok(), afterLogin());
  }

  /** Everything the source pushes the moment a client signs in, in the order it pushes it. */
  public List<Emission> afterLogin() {
    List<Emission> emissions = new ArrayList<>();
    emissions.add(monitorList());
    emissions.add(
        Emission.of("info", Settings.publicInfo(componentClient, false, Versions.APP_VERSION)));
    emissions.add(maintenanceList());
    emissions.add(notificationList());
    emissions.add(proxyList());
    emissions.add(dockerHostList());
    emissions.add(apiKeyList());
    emissions.add(remoteBrowserList());
    emissions.add(Emission.of("monitorTypeList", Checks.typeList()));
    emissions.add(statusPageList());
    for (MonitorListView.MonitorRow row :
        componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
      emissions.add(heartbeatList(row.id()));
      emissions.addAll(stats(row.id()));
    }
    return emissions;
  }

  // ---- monitors --------------------------------------------------------------------------------

  public Emission monitorList() {
    Map<String, Object> monitors = new LinkedHashMap<>();
    for (MonitorListView.MonitorRow row :
        componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
      monitors.put(row.id(), monitorJson(row));
    }
    return Emission.of("monitorList", monitors);
  }

  private Map<String, Object> monitorJson(MonitorListView.MonitorRow row) {
    Map<String, Object> json = parse(row.json());
    applySourceShape(json);
    json.put("id", row.id());
    json.put("active", row.active());
    json.put("maintenance", row.underMaintenance());
    json.put("forceInactive", forceInactive(row));
    json.put("path", pathOf(row));
    json.put("pathName", String.join(" / ", pathOf(row)));
    json.put("childrenIDs", childrenOf(row.id()));
    json.put("tags", tagsOf(row.id()));
    return json;
  }

  /** A monitor whose parent is paused cannot beat, whatever its own switch says. */
  private boolean forceInactive(MonitorListView.MonitorRow row) {
    String parent = row.parent();
    int depth = 0;
    while (parent != null && !parent.isEmpty() && depth++ < 32) {
      var found = componentClient.forView().method(MonitorListView::byId).invoke(parent);
      if (found.isEmpty()) {
        return false;
      }
      if (!found.get().active()) {
        return true;
      }
      parent = found.get().parent();
    }
    return false;
  }

  private List<String> pathOf(MonitorListView.MonitorRow row) {
    List<String> path = new ArrayList<>();
    path.add(row.name());
    String parent = row.parent();
    int depth = 0;
    while (parent != null && !parent.isEmpty() && depth++ < 32) {
      var found = componentClient.forView().method(MonitorListView::byId).invoke(parent);
      if (found.isEmpty()) {
        break;
      }
      path.add(0, found.get().name());
      parent = found.get().parent();
    }
    return path;
  }

  private List<String> childrenOf(String id) {
    List<String> children = new ArrayList<>();
    for (MonitorListView.MonitorRow row :
        componentClient.forView().method(MonitorListView::children).invoke(id).monitors()) {
      children.add(row.id());
    }
    return children;
  }

  private Reply getMonitor(String id) {
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    if (!state.created()) {
      return Reply.of(failed("Monitor not found"));
    }
    var row = componentClient.forView().method(MonitorListView::byId).invoke(id);
    Map<String, Object> payload = ok();
    // The list row where it has arrived, and the monitor's own state where it has not — both
    // shaped the same way, because a caller reading a monitor it has just added must not get a
    // different set of keys from one reading a monitor that has been there a while.
    Map<String, Object> monitor = row.isPresent() ? monitorJson(row.get()) : monitorJson(state, id);
    // The path this one call answers with is empty, on both systems. The source builds it from a
    // preloaded row that this handler assembles out of two columns, and the monitor's name is not
    // one of them — so the name it joins is missing and the breadcrumb comes back blank. Reading
    // the same monitor out of the list gives the real path, and that is where the interface draws
    // its breadcrumb from. Copied rather than corrected: the interface is written against this.
    monitor.put("path", java.util.Collections.singletonList(null));
    monitor.put("pathName", "");
    payload.put("monitor", monitor);
    return Reply.of(payload);
  }

  private Reply addMonitor(Map<String, Object> raw, Sessions.Signed session) {
    // Validated before an identifier is taken, because a refused monitor must not consume one:
    // the numbers are visible on every monitor's own page and in every badge URL, so a gap in
    // them is a difference a person sees.
    String refusal = configFrom(raw, "0").validate();
    if (refusal != null) {
      return Reply.of(failed(refusal));
    }
    String id = Ids.next(componentClient, "monitor");
    MonitorConfig config = configFrom(raw, id);
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::create).invoke(config);
    if (config.active()) {
      startMonitor(id);
    }
    Map<String, Object> payload = okI18n("successAdded");
    payload.put("monitorID", id);
    return new Reply(payload, List.of(updateMonitorIntoList(id)));
  }

  private Reply editMonitor(Map<String, Object> raw, Sessions.Signed session) {
    String id = String.valueOf(raw.get("id"));
    MonitorEntity.State existing =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    if (!existing.created()) {
      return Reply.of(failed("Monitor not found"));
    }
    if (isDescendant(id, str(raw.get("parent")))) {
      return Reply.of(failed("Invalid Monitor Group"));
    }
    MonitorConfig config = configFrom(raw, id);
    String refusal = config.validate();
    if (refusal != null) {
      return Reply.of(failed(refusal));
    }
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::create).invoke(config);
    // The cadence may have changed, so the pending beat is replaced rather than left to fire on
    // the old schedule.
    if (config.active()) {
      startMonitor(id);
    } else {
      stopMonitor(id);
    }
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("monitorID", id);
    return new Reply(payload, List.of(updateMonitorIntoList(id)));
  }

  /** Whether making {@code candidate} the parent of {@code id} would close a loop. */
  private boolean isDescendant(String id, String candidate) {
    String current = candidate;
    int depth = 0;
    while (current != null && !current.isEmpty() && depth++ < 32) {
      if (current.equals(id)) {
        return true;
      }
      var row = componentClient.forView().method(MonitorListView::byId).invoke(current);
      if (row.isEmpty()) {
        return false;
      }
      current = row.get().parent();
    }
    return false;
  }

  private Reply deleteMonitor(String id, boolean deleteChildren) {
    List<Emission> emissions = new ArrayList<>();
    List<String> children = childrenOf(id);
    if (deleteChildren) {
      for (String child : children) {
        stopMonitor(child);
        componentClient.forEventSourcedEntity(child).method(MonitorEntity::delete).invoke();
        emissions.add(Emission.of("deleteMonitorFromList", child));
      }
    } else {
      for (String child : children) {
        MonitorEntity.State state =
            componentClient.forEventSourcedEntity(child).method(MonitorEntity::get).invoke();
        componentClient
            .forEventSourcedEntity(child)
            .method(MonitorEntity::create)
            .invoke(state.config().toBuilder().parent(null).build());
        emissions.add(updateMonitorIntoList(child));
      }
    }
    stopMonitor(id);
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::delete).invoke();
    emissions.add(Emission.of("deleteMonitorFromList", id));
    return new Reply(okI18n("successDeleted"), emissions);
  }

  private Reply pauseMonitor(String id) {
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
    stopMonitor(id);
    return new Reply(okI18n("successPaused"), List.of(updateMonitorIntoList(id)));
  }

  private Reply resumeMonitor(String id) {
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::start).invoke();
    startMonitor(id);
    return new Reply(okI18n("successResumed"), List.of(updateMonitorIntoList(id)));
  }

  /**
   * Start a monitor beating.
   *
   * <p>Every kind beats at once except one. A push monitor is checked by *not* hearing from
   * something, so beating the moment it starts records an outage before whatever is meant to push
   * into it has had a chance to — the source waits a whole interval before its first beat for that
   * type alone, and so does this. R103.
   */
  public void startMonitor(String id) {
    componentClient.forEventSourcedEntity(id).method(MonitorEntity::start).invoke();
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    boolean push = "push".equals(state.config().type());
    timers.createSingleTimer(
        MonitorBeat.timerName(id),
        push ? Duration.ofSeconds(state.config().interval()) : Duration.ofMillis(1),
        componentClient
            .forTimedAction()
            .method(MonitorBeat::beat)
            .deferred(new MonitorBeat.Beat(id)));
  }

  public void stopMonitor(String id) {
    timers.delete(MonitorBeat.timerName(id));
  }

  /**
   * One monitor, as the interface reads it.
   *
   * <p>Read from the monitor itself rather than from the list, because the list is built from the
   * write and lags it — a screen told about a monitor it could not yet find in the list would show
   * nothing at all.
   */
  private Emission updateMonitorIntoList(String id) {
    Map<String, Object> one = new LinkedHashMap<>();
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    if (state.created()) {
      one.put(id, monitorJson(state, id));
    }
    return Emission.of("updateMonitorIntoList", one);
  }

  /** The same shape as the list's rows, built from a monitor's own state. */
  private Map<String, Object> monitorJson(MonitorEntity.State state, String id) {
    Map<String, Object> json =
        Json.MAPPER.convertValue(state.config().toBuilder().id(id).build(), LinkedHashMap.class);
    json.put("id", id);
    json.put("active", state.active());
    json.put("maintenance", state.underMaintenance());
    json.put("forceInactive", false);
    json.put("path", List.of(state.config().name() == null ? "" : state.config().name()));
    json.put("pathName", state.config().name() == null ? "" : state.config().name());
    json.put("childrenIDs", childrenOf(id));
    json.put("tags", state.config().tags() == null ? List.of() : state.config().tags());
    applySourceShape(json);
    return json;
  }

  /**
   * The two things the source's own serialiser does to a monitor row on the way out.
   *
   * <p>`customUrl` is a stored column the source never sends: what it does with it is override the
   * address the monitor reports, which is how a monitor watching one address advertises another.
   * And `childrenIDs` is a list on every monitor, empty where there are none.
   */
  private static void applySourceShape(Map<String, Object> json) {
    Object custom = json.remove("customUrl");
    if (custom != null && !String.valueOf(custom).isBlank()) {
      json.put("url", custom);
    }
    if (json.get("childrenIDs") == null) {
      json.put("childrenIDs", List.of());
    }
    // Three columns this port keeps that the source's serialiser does not list, so a monitor
    // payload carrying them is a payload the interface was not written against.
    for (String extra : List.of("manual_status", "rssTitle", "snmp_v3_username")) {
      json.remove(extra);
    }
    // Four fields whose column is NULL until somebody sets one. The source hands the null over
    // as it stands — its own check reads an absent body encoding as JSON, and its three list-
    // valued columns parse to null — where this port's record carries a usable default. The
    // default is right for the code that reads it and wrong for the form that draws it: a
    // monitor nobody configured for Kafka would show an empty broker list as a chosen setting.
    if ("json".equals(json.get("httpBodyEncoding"))) {
      json.put("httpBodyEncoding", null);
    }
    for (String listed : List.of("kafkaProducerBrokers", "rabbitmqNodes")) {
      Object value = json.get(listed);
      if (value instanceof List<?> list && list.isEmpty()) {
        json.put(listed, null);
      }
    }
    Object sasl = json.get("kafkaProducerSaslOptions");
    if (sasl instanceof Map<?, ?> map && map.isEmpty()) {
      json.put("kafkaProducerSaslOptions", null);
    }
  }

  /**
   * A monitor's beats within a window, as rows of the table they are stored in.
   *
   * <p>Two things here are the source's rather than this port's choosing. The answer carries the
   * stored *row* — `monitor_id`, `down_count`, `end_time` and the row's own identifier — where
   * every other call that hands over a beat carries the beat's own shape; the source's query is a
   * `SELECT *` and this is what the interface receives. And a window of zero hours is a window, not
   * a mistake: only a period the caller left out is refused.
   */
  private Reply getMonitorBeats(String monitorId, Double periodHours) {
    if (periodHours == null) {
      return Reply.of(failed("Invalid period."));
    }
    long cutoff = System.currentTimeMillis() - (long) (periodHours * 3600 * 1000);
    List<Object> rows = new ArrayList<>();
    for (Heartbeat beat : allBeats(monitorId)) {
      if (beat.timeEpochMillis() >= cutoff) {
        rows.add(beatRow(beat));
      }
    }
    return Reply.of(okWith("data", rows));
  }

  /** A beat as its stored row, which is what a `SELECT *` hands the interface. */
  private Map<String, Object> beatRow(Heartbeat beat) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", beat.sequence());
    row.put("important", beat.important() ? 1 : 0);
    row.put("monitor_id", beat.monitorId());
    row.put("status", beat.status());
    row.put("msg", beat.msg());
    row.put("time", Notifications.format(beat.timeEpochMillis(), java.time.ZoneId.of("UTC")));
    row.put("ping", beat.ping());
    row.put("duration", beat.duration());
    row.put("down_count", beat.downCount());
    row.put(
        "end_time",
        beat.endTimeEpochMillis() == null
            ? null
            : Notifications.format(beat.endTimeEpochMillis(), java.time.ZoneId.of("UTC")));
    row.put("retries", beat.retries());
    row.put("response", beat.response());
    return row;
  }

  private Reply getMonitorChartData(String monitorId, double periodHours) {
    if (periodHours <= 0) {
      return Reply.of(failed("Invalid period."));
    }
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
    UptimeCalculator stats = state.stats();
    long now = System.currentTimeMillis();
    List<Map<String, Object>> series;
    if (periodHours <= 24) {
      series = stats.getDataArray((int) (periodHours * 60), "minute", now);
    } else if (periodHours <= 720) {
      series = stats.getDataArray((int) periodHours, "hour", now);
    } else {
      series = stats.getDataArray((int) (periodHours / 24), "day", now);
    }
    return Reply.of(okWith("data", series));
  }

  private Reply importantCount(String monitorId) {
    return Reply.of(okWith("count", importantBeats(monitorId).size()));
  }

  private Reply importantPaged(String monitorId, int offset, int count) {
    List<Heartbeat> beats = importantBeats(monitorId);
    List<Object> page = new ArrayList<>();
    for (int i = offset; i < Math.min(offset + count, beats.size()); i++) {
      page.add(beatJson(beats.get(i)));
    }
    return Reply.of(okWith("data", page));
  }

  private List<Heartbeat> importantBeats(String monitorId) {
    HeartbeatFeedView.Beats beats =
        monitorId == null || monitorId.isEmpty() || "null".equals(monitorId)
            ? componentClient.forView().method(HeartbeatFeedView::important).invoke()
            : componentClient.forView().method(HeartbeatFeedView::importantFor).invoke(monitorId);
    return beats.heartbeats();
  }

  /**
   * The beats a monitor itself holds, which is what a clear has to work from.
   *
   * <p>Not the published feed: that is a read side, it lags the beats that fed it, and a caller
   * clearing a monitor it has just watched beat would find nothing there to clear. The monitor's
   * own history is written before the call that reads it returns.
   */
  private List<Heartbeat> recordedBeats(String monitorId) {
    return componentClient
        .forEventSourcedEntity(monitorId)
        .method(MonitorEntity::get)
        .invoke()
        .history();
  }

  private List<Heartbeat> allBeats(String monitorId) {
    return componentClient
        .forView()
        .method(HeartbeatFeedView::replay)
        .invoke(new HeartbeatFeedView.From(monitorId, 0))
        .heartbeats();
  }

  /**
   * Blank every message and every importance flag, keeping the beats. R75.
   *
   * <p>Both halves are needed. The monitor's own history is what the beat loop reads back, and the
   * published feed is what every list the interface draws is read from — clearing one and not the
   * other leaves the events table showing what the caller just asked to have cleared. The source
   * does it in one statement over one table; here it is two writes per beat, which is what a
   * separate read side costs.
   */
  private Reply clearEvents(String monitorId) {
    componentClient
        .forEventSourcedEntity(monitorId)
        .method(MonitorEntity::clearEvents)
        .invoke();
    for (Heartbeat beat : recordedBeats(monitorId)) {
      componentClient
          .forKeyValueEntity(HeartbeatAnnouncementEntity.key(monitorId, beat.sequence()))
          .method(HeartbeatAnnouncementEntity::amend)
          .invoke(beat.cleared());
    }
    return Reply.of(ok());
  }

  /** Drop the beats and the statistics, keeping the monitor. R76. */
  private Reply clearHeartbeats(String monitorId) {
    List<Heartbeat> published = recordedBeats(monitorId);
    componentClient
        .forEventSourcedEntity(monitorId)
        .method(MonitorEntity::clearHeartbeats)
        .invoke();
    for (Heartbeat beat : published) {
      componentClient
          .forKeyValueEntity(HeartbeatAnnouncementEntity.key(monitorId, beat.sequence()))
          .method(HeartbeatAnnouncementEntity::forget)
          .invoke();
    }
    return new Reply(ok(), List.of(heartbeatList(monitorId, true)));
  }

  private Reply clearStatistics() {
    List<Emission> emissions = new ArrayList<>();
    for (MonitorListView.MonitorRow row :
        componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
      componentClient
          .forEventSourcedEntity(row.id())
          .method(MonitorEntity::clearHeartbeats)
          .invoke();
      emissions.add(heartbeatList(row.id(), true));
    }
    return new Reply(ok(), emissions);
  }

  private Reply checkDomain(Map<String, Object> partial) {
    String type = str(partial.get("type"));
    String target =
        switch (type == null ? "" : type) {
          case "grpc-keyword" -> str(partial.get("grpcUrl"));
          case "http", "keyword", "json-query", "real-browser", "websocket-upgrade" ->
              str(partial.get("url"));
          default -> str(partial.get("hostname"));
        };
    MonitorConfig probe =
        MonitorConfig.blank("probe")
            .toBuilder()
            .type(type)
            .url(str(partial.get("url")))
            .hostname(str(partial.get("hostname")))
            .build();
    String reason = io.akka.uptimekuma.application.DomainExpiry.unsupportedReason(probe);
    if (reason != null) {
      Map<String, Object> payload = failedI18n(reason);
      Map<String, Object> meta = new LinkedHashMap<>();
      // The refusal that names something names it: which host was asked about, and which public
      // suffix it was found under — null where the target is an address rather than a name.
      if ("domain_expiry_unsupported_is_icann".equals(reason)) {
        meta.put("domain", io.akka.uptimekuma.application.DomainExpiry.hostOf(target));
        meta.put("publicSuffix", null);
      }
      payload.put("meta", meta);
      return Reply.of(payload);
    }
    String domain = io.akka.uptimekuma.application.DomainExpiry.registrableDomain(target);
    Map<String, Object> payload = ok();
    payload.put("domain", domain);
    payload.put("tld", domain.substring(domain.lastIndexOf('.') + 1));
    return Reply.of(payload);
  }

  public Emission heartbeatList(String monitorId) {
    return heartbeatList(monitorId, false);
  }

  private Emission heartbeatList(String monitorId, boolean overwrite) {
    List<Heartbeat> recent =
        componentClient.forView().method(HeartbeatFeedView::recent).invoke(monitorId).heartbeats();
    List<Object> rows = new ArrayList<>();
    // The query hands them back newest first; the interface draws them oldest first.
    for (int i = recent.size() - 1; i >= 0; i--) {
      rows.add(beatJson(recent.get(i)));
    }
    return Emission.of("heartbeatList", monitorId, rows, overwrite);
  }

  /** The four derived figures the source pushes beside a beat. */
  public List<Emission> stats(String monitorId) {
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
    long now = System.currentTimeMillis();
    List<Emission> emissions = new ArrayList<>();
    UptimeCalculator.Window day = state.stats().get24Hour(now);
    emissions.add(
        Emission.of(
            "avgPing",
            monitorId,
            day.avgPing() == null ? null : Math.round(day.avgPing() * 100.0) / 100.0));
    emissions.add(Emission.of("uptime", monitorId, 24, day.uptime()));
    emissions.add(Emission.of("uptime", monitorId, 720, state.stats().get30Day(now).uptime()));
    emissions.add(Emission.of("uptime", monitorId, "1y", state.stats().get1Year(now).uptime()));
    if (state.tlsInfo() != null && !state.tlsInfo().isEmpty()) {
      emissions.add(Emission.of("certInfo", monitorId, Json.write(state.tlsInfo())));
    }
    if (state.domainInfo() != null && !state.domainInfo().isEmpty()) {
      emissions.add(
          Emission.of(
              "domainInfo",
              monitorId,
              state.domainInfo().get("daysRemaining"),
              state.domainInfo().get("expiry")));
    }
    return emissions;
  }

  public Map<String, Object> beatJson(Heartbeat beat) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("monitorID", beat.monitorId());
    json.put("status", beat.status());
    json.put("time", Notifications.format(beat.timeEpochMillis(), java.time.ZoneId.of("UTC")));
    json.put("msg", beat.msg());
    json.put("ping", beat.ping());
    // Stored as an integer, and the interface reads it as one.
    json.put("important", beat.important() ? 1 : 0);
    json.put("duration", beat.duration());
    json.put("retries", beat.retries());
    json.put("response", beat.response());
    return json;
  }

  // ---- tags ------------------------------------------------------------------------------------

  private Reply getTags() {
    List<Object> tags = new ArrayList<>();
    for (RecordRow row : componentClient.forView().method(TagListView::all).invoke().entries()) {
      tags.add(parse(row.json()));
    }
    return Reply.of(okWith("tags", tags));
  }

  private Reply addTag(Map<String, Object> tag) {
    String id = Ids.next(componentClient, "tag");
    Map<String, Object> fields = new LinkedHashMap<>(tag);
    fields.remove("id");
    componentClient.forKeyValueEntity(id).method(TagEntity::put).invoke(RecordFields.of(fields));
    Map<String, Object> payload = ok();
    fields.put("id", id);
    payload.put("tag", fields);
    return Reply.of(payload);
  }

  private Reply editTag(Map<String, Object> tag) {
    String id = str(tag.get("id"));
    StoredRecord existing =
        componentClient.forKeyValueEntity(id).method(TagEntity::get).invoke();
    if (!existing.exists()) {
      return Reply.of(failedI18n("tagNotFound"));
    }
    Map<String, Object> fields = new LinkedHashMap<>(tag);
    fields.remove("id");
    componentClient.forKeyValueEntity(id).method(TagEntity::put).invoke(RecordFields.of(fields));
    Map<String, Object> payload = okI18n("Saved.");
    fields.put("id", id);
    payload.put("tag", fields);
    return Reply.of(payload);
  }

  private Reply deleteTag(String id) {
    componentClient.forKeyValueEntity(id).method(TagEntity::delete).invoke();
    return Reply.of(okI18n("successDeleted"));
  }

  /**
   * Attach, change or remove one label on one monitor.
   *
   * <p>The attachment is stored on the monitor rather than in a table of its own, because a monitor
   * is the only thing that reads it and the interface sends the whole monitor back on every edit.
   */
  @SuppressWarnings("unchecked")
  private Reply monitorTag(String tagId, String monitorId, String value, String operation) {
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
    if (!state.created()) {
      return Reply.of(failed("Monitor not found"));
    }
    List<Map<String, Object>> tags = new ArrayList<>(state.config().tags());
    tags.removeIf(
        entry ->
            String.valueOf(entry.get("tag_id")).equals(tagId)
                && (!"delete".equals(operation) || String.valueOf(entry.get("value")).equals(value)));
    if (!"delete".equals(operation)) {
      StoredRecord tag = componentClient.forKeyValueEntity(tagId).method(TagEntity::get).invoke();
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("tag_id", tagId);
      entry.put("monitor_id", monitorId);
      entry.put("value", value);
      entry.put("name", tag.str("name"));
      entry.put("color", tag.str("color"));
      tags.add(entry);
    }
    componentClient
        .forEventSourcedEntity(monitorId)
        .method(MonitorEntity::create)
        .invoke(state.config().toBuilder().tags(tags).build());
    String message =
        switch (operation) {
          case "add" -> "successAdded";
          case "edit" -> "successEdited";
          default -> "successDeleted";
        };
    return new Reply(okI18n(message), List.of(updateMonitorIntoList(monitorId)));
  }

  private List<Map<String, Object>> tagsOf(String monitorId) {
    MonitorEntity.State state =
        componentClient.forEventSourcedEntity(monitorId).method(MonitorEntity::get).invoke();
    return state.created() && state.config().tags() != null ? state.config().tags() : List.of();
  }

  // ---- settings --------------------------------------------------------------------------------

  /**
   * The settings a person has actually written, and the server's timezone.
   *
   * <p>Read from what was stored rather than from what the server falls back to, because the
   * interface draws this answer into its own form: a setting nobody has ever set comes back absent
   * on the source, and the form then shows its own placeholder. Answering with the fallback instead
   * shows a value in a field nobody filled in — and then saving the form writes it.
   */
  private Reply getSettings() {
    Map<String, Object> stored = Settings.stored(componentClient);
    Map<String, Object> general = new LinkedHashMap<>();
    for (String key : Settings.GENERAL_KEYS) {
      if (stored.containsKey(key)) {
        general.put(key, stored.get(key));
      }
    }
    // The one value the source fills in when it is missing, because it is the server's own and
    // not a preference: it reads it off the machine rather than leaving the field empty.
    general.putIfAbsent("serverTimezone", Settings.timezone(componentClient));
    return Reply.of(okWith("data", general));
  }

  private Reply setSettings(
      Map<String, Object> data, String currentPassword, Sessions.Signed session) {
    boolean disablingAuth = Boolean.TRUE.equals(data.get("disableAuth"));
    boolean currentlyDisabled = Settings.flag(componentClient, "disableAuth");
    if (disablingAuth && !currentlyDisabled) {
      // Turning authentication off is the one setting that has to be confirmed with a password,
      // because it is the setting that stops passwords being asked for.
      StoredRecord user =
          componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
      if (!Passwords.verify(currentPassword, user.str("password"))) {
        return Reply.of(failed("Incorrect current password"));
      }
    }
    Settings.write(componentClient, data);
    List<Emission> emissions = new ArrayList<>();
    emissions.add(
        Emission.of("info", Settings.publicInfo(componentClient, false, Versions.APP_VERSION)));
    emissions.add(maintenanceList());
    return new Reply(okI18n("Saved."), emissions);
  }

  private Reply changePassword(Map<String, Object> data, Sessions.Signed session) {
    String currentPassword = str(data.get("currentPassword"));
    String newPassword = str(data.get("newPassword"));
    if (newPassword == null || newPassword.isEmpty()) {
      return Reply.of(failed("Invalid new password"));
    }
    if (Passwords.tooWeak(newPassword)) {
      return Reply.of(failedI18n("passwordTooWeak"));
    }
    StoredRecord user =
        componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
    if (!Passwords.verify(currentPassword, user.str("password"))) {
      return Reply.of(failed("Incorrect current password"));
    }
    String hash = Passwords.generate(newPassword);
    componentClient
        .forKeyValueEntity(session.userId())
        .method(UserEntity::patch)
        .invoke(RecordFields.of(Map.of("password", hash)));
    Map<String, Object> payload = okI18n("successAuthChangePassword");
    payload.put("token", Sessions.create(componentClient, user.str("username"), hash));
    return Reply.of(payload);
  }

  // ---- notifications ---------------------------------------------------------------------------

  public Emission notificationList() {
    List<Object> notifications = new ArrayList<>();
    for (RecordRow row :
        componentClient.forView().method(NotificationListView::all).invoke().entries()) {
      Map<String, Object> json = parse(row.json());
      json.put("isDefault", Boolean.TRUE.equals(json.get("isDefault")));
      json.put("active", !Boolean.FALSE.equals(json.get("active")));
      notifications.add(json);
    }
    return Emission.of("notificationList", notifications);
  }

  private Reply addNotification(
      Map<String, Object> notification, String notificationId, Sessions.Signed session) {
    boolean applyExisting = Boolean.TRUE.equals(notification.get("applyExisting"));
    Map<String, Object> fields = new LinkedHashMap<>(notification);
    // One-shot: the flag says what to do now, not what to remember.
    fields.put("applyExisting", false);
    String id =
        notificationId == null || notificationId.isEmpty() || "null".equals(notificationId)
            ? Ids.next(componentClient, "notification")
            : notificationId;
    componentClient.forKeyValueEntity(id).method(NotificationEntity::put).invoke(RecordFields.of(fields));
    if (applyExisting) {
      for (MonitorListView.MonitorRow row :
          componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
        MonitorEntity.State state =
            componentClient.forEventSourcedEntity(row.id()).method(MonitorEntity::get).invoke();
        Map<String, Boolean> attached = new LinkedHashMap<>(state.config().notificationIDList());
        attached.put(id, true);
        componentClient
            .forEventSourcedEntity(row.id())
            .method(MonitorEntity::create)
            .invoke(state.config().toBuilder().notificationIDList(attached).build());
      }
    }
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("id", id);
    return new Reply(payload, List.of(notificationList()));
  }

  private Reply deleteNotification(String id, Sessions.Signed session) {
    componentClient.forKeyValueEntity(id).method(NotificationEntity::delete).invoke();
    return new Reply(okI18n("successDeleted"), List.of(notificationList()));
  }

  private Reply testNotification(Map<String, Object> notification) {
    try {
      String message =
          Providers.send(
              new Config(notification),
              notification.get("name") + " Testing",
              null,
              null,
              new Context(
                  new HttpSender(),
                  Settings.string(componentClient, "primaryBaseURL"),
                  Versions.APP_VERSION));
      return Reply.of(okWith("msg", message));
    } catch (Exception e) {
      return Reply.of(failed(e.getMessage()));
    }
  }

  // ---- proxies, hosts, browsers ------------------------------------------------------------------

  public Emission proxyList() {
    List<Object> proxies = new ArrayList<>();
    for (RecordRow row : componentClient.forView().method(ProxyListView::all).invoke().entries()) {
      Map<String, Object> json = parse(row.json());
      json.put("auth", Boolean.TRUE.equals(json.get("auth")));
      json.put("active", !Boolean.FALSE.equals(json.get("active")));
      json.put("default", Boolean.TRUE.equals(json.get("default")));
      proxies.add(json);
    }
    return Emission.of("proxyList", proxies);
  }

  private Reply addProxy(Map<String, Object> proxy, String proxyId, Sessions.Signed session) {
    boolean applyExisting = Boolean.TRUE.equals(proxy.get("applyExisting"));
    Map<String, Object> fields = new LinkedHashMap<>(proxy);
    fields.remove("applyExisting");
    String id =
        proxyId == null || proxyId.isEmpty() || "null".equals(proxyId)
            ? Ids.next(componentClient, "proxy")
            : proxyId;
    componentClient.forKeyValueEntity(id).method(ProxyEntity::put).invoke(RecordFields.of(fields));
    List<Emission> emissions = new ArrayList<>();
    emissions.add(proxyList());
    if (applyExisting) {
      for (MonitorListView.MonitorRow row :
          componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
        MonitorEntity.State state =
            componentClient.forEventSourcedEntity(row.id()).method(MonitorEntity::get).invoke();
        componentClient
            .forEventSourcedEntity(row.id())
            .method(MonitorEntity::create)
            .invoke(state.config().toBuilder().build());
      }
      emissions.add(monitorList());
    }
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("id", id);
    return new Reply(payload, emissions);
  }

  private Reply deleteProxy(String id) {
    componentClient.forKeyValueEntity(id).method(ProxyEntity::delete).invoke();
    return new Reply(okI18n("successDeleted"), List.of(proxyList()));
  }

  public Emission dockerHostList() {
    List<Object> hosts = new ArrayList<>();
    for (RecordRow row :
        componentClient.forView().method(DockerHostListView::all).invoke().entries()) {
      hosts.add(parse(row.json()));
    }
    return Emission.of("dockerHostList", hosts);
  }

  private Reply addDockerHost(Map<String, Object> host, String hostId) {
    String id =
        hostId == null || hostId.isEmpty() || "null".equals(hostId)
            ? Ids.next(componentClient, "docker-host")
            : hostId;
    componentClient.forKeyValueEntity(id).method(DockerHostEntity::put).invoke(RecordFields.of(host));
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("id", id);
    return new Reply(payload, List.of(dockerHostList()));
  }

  private Reply deleteDockerHost(String id) {
    componentClient.forKeyValueEntity(id).method(DockerHostEntity::delete).invoke();
    return new Reply(okI18n("successDeleted"), List.of(dockerHostList()));
  }

  private Reply testDockerHost(Map<String, Object> host) {
    try {
      String body;
      if ("socket".equals(host.get("dockerType"))) {
        body =
            io.akka.uptimekuma.checks.Probes.dockerContainers(
                String.valueOf(host.get("dockerDaemon")), true);
      } else {
        body =
            io.akka.uptimekuma.checks.Probes.dockerContainers(
                String.valueOf(host.get("dockerDaemon")), false);
      }
      List<?> containers = Json.MAPPER.readValue(body, List.class);
      return Reply.of(
          okWith(
              "msg",
              containers.isEmpty()
                  ? "Connected Successfully, but there are no containers?"
                  : "Connected Successfully. Amount of containers: " + containers.size()));
    } catch (Exception e) {
      // A failure with nothing to say still says which class of failure it was, rather than
      // handing the interface a null to draw.
      return Reply.of(failed(io.akka.uptimekuma.checks.TransportErrors.deepestMessage(e)));
    }
  }

  public Emission remoteBrowserList() {
    List<Object> browsers = new ArrayList<>();
    for (RecordRow row :
        componentClient.forView().method(RemoteBrowserListView::all).invoke().entries()) {
      browsers.add(parse(row.json()));
    }
    return Emission.of("remoteBrowserList", browsers);
  }

  private Reply addRemoteBrowser(Map<String, Object> browser, String browserId) {
    String id =
        browserId == null || browserId.isEmpty() || "null".equals(browserId)
            ? Ids.next(componentClient, "remote-browser")
            : browserId;
    componentClient.forKeyValueEntity(id).method(RemoteBrowserEntity::put).invoke(RecordFields.of(browser));
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("id", id);
    return new Reply(payload, List.of(remoteBrowserList()));
  }

  private Reply deleteRemoteBrowser(String id) {
    componentClient.forKeyValueEntity(id).method(RemoteBrowserEntity::delete).invoke();
    return new Reply(okI18n("successDeleted"), List.of(remoteBrowserList()));
  }

  private Reply testRemoteBrowser(Map<String, Object> browser) {
    try {
      io.akka.uptimekuma.checks.Probes.remoteBrowserVersion(String.valueOf(browser.get("url")));
      return Reply.of(okWith("msg", "Connected Successfully."));
    } catch (Exception e) {
      return Reply.of(failed(e.getMessage()));
    }
  }

  // ---- API keys --------------------------------------------------------------------------------

  public Emission apiKeyList() {
    List<Object> keys = new ArrayList<>();
    for (RecordRow row : componentClient.forView().method(ApiKeyListView::all).invoke().entries()) {
      Map<String, Object> json = parse(row.json());
      // The stored hash is never handed out, only what a person needs to recognise the key.
      json.remove("key");
      keys.add(json);
    }
    return Emission.of("apiKeyList", keys);
  }

  private Reply addApiKey(Map<String, Object> key, Sessions.Signed session) {
    String id = Ids.next(componentClient, "api-key");
    String clear = Totp.nanoid(40);
    Map<String, Object> fields = new LinkedHashMap<>(key);
    fields.put("key", Passwords.generate(clear));
    fields.put("active", true);
    fields.put("createdDate", java.time.Instant.now().toString());
    componentClient.forKeyValueEntity(id).method(ApiKeyEntity::put).invoke(RecordFields.of(fields));
    Settings.write(componentClient, Map.of("apiKeysEnabled", true));
    Map<String, Object> payload = okI18n("successAdded");
    // The only time the key itself is readable: it is stored hashed and cannot be shown again.
    payload.put("key", "uk" + id + "_" + clear);
    payload.put("keyID", id);
    return new Reply(payload, List.of(apiKeyList()));
  }

  private Reply deleteApiKey(String id) {
    componentClient.forKeyValueEntity(id).method(ApiKeyEntity::delete).invoke();
    return new Reply(okI18n("successDeleted"), List.of(apiKeyList()));
  }

  private Reply setApiKeyActive(String id, boolean active) {
    componentClient
        .forKeyValueEntity(id)
        .method(ApiKeyEntity::patch)
        .invoke(RecordFields.of(Map.of("active", active)));
    return new Reply(
        okI18n(active ? "successEnabled" : "successDisabled"), List.of(apiKeyList()));
  }

  // ---- maintenance -----------------------------------------------------------------------------

  public Emission maintenanceList() {
    Map<String, Object> windows = new LinkedHashMap<>();
    String timezone = Settings.timezone(componentClient);
    long now = System.currentTimeMillis();
    for (RecordRow row :
        componentClient.forView().method(MaintenanceListView::all).invoke().entries()) {
      MaintenanceWindow window = Maintenances.parse(row.json());
      if (window != null) {
        windows.put(row.id(), window.toJson(timezone, now));
      }
    }
    return Emission.of("maintenanceList", windows);
  }

  private Reply getMaintenance(String id) {
    StoredRecord record =
        componentClient.forKeyValueEntity(id).method(MaintenanceEntity::get).invoke();
    if (!record.exists()) {
      return Reply.of(failed("Maintenance not found"));
    }
    MaintenanceWindow window =
        Json.MAPPER.convertValue(record.toJson(), MaintenanceWindow.class);
    Map<String, Object> payload = ok();
    payload.put(
        "maintenance",
        window.toJson(Settings.timezone(componentClient), System.currentTimeMillis()));
    return Reply.of(payload);
  }

  private Reply addMaintenance(Map<String, Object> maintenance, Sessions.Signed session) {
    String id = Ids.next(componentClient, "maintenance");
    Map<String, Object> fields = normaliseMaintenance(maintenance, id);
    componentClient.forKeyValueEntity(id).method(MaintenanceEntity::put).invoke(RecordFields.of(fields));
    Map<String, Object> payload = okI18n("successAdded");
    payload.put("maintenanceID", id);
    return new Reply(payload, List.of(maintenanceList()));
  }

  private Reply editMaintenance(Map<String, Object> maintenance, Sessions.Signed session) {
    String id = str(maintenance.get("id"));
    StoredRecord existing =
        componentClient.forKeyValueEntity(id).method(MaintenanceEntity::get).invoke();
    if (!existing.exists()) {
      return Reply.of(failed("Maintenance not found"));
    }
    Map<String, Object> fields = normaliseMaintenance(maintenance, id);
    // The links to monitors and status pages are set by their own calls, so an edit that does not
    // mention them keeps what is there.
    fields.putIfAbsent("monitors", existing.get("monitors"));
    fields.putIfAbsent("statusPages", existing.get("statusPages"));
    componentClient.forKeyValueEntity(id).method(MaintenanceEntity::put).invoke(RecordFields.of(fields));
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("maintenanceID", id);
    return new Reply(payload, List.of(maintenanceList()));
  }

  /**
   * Turn what the interface sends into the fields a window is stored as.
   *
   * <p>Three of them are not stored the way they are sent. The interface carries the window's two
   * instants as a two-element {@code dateRange} and its two clock times as a two-element
   * {@code timeRange} of hour-and-minute objects; a window holds four separate fields. And two
   * fields are the server's to fill in rather than the caller's: the cron pattern a recurring
   * strategy implies, and how long each window lasts.
   */
  private Map<String, Object> normaliseMaintenance(Map<String, Object> raw, String id) {
    Map<String, Object> fields = new LinkedHashMap<>(raw);
    fields.put("id", id);
    fields.putIfAbsent("active", true);

    List<?> dateRange = fields.get("dateRange") instanceof List<?> list ? list : List.of();
    fields.put("startDate", validDate(at(dateRange, 0), "Invalid start date"));
    fields.put("endDate", validDate(at(dateRange, 1), "Invalid end date"));

    String strategy0 = str(fields.get("strategy"));
    // A pattern is a column only the two strategies that generate one ever write. The interface
    // sends an empty string for the others and the source, which maps its payload onto columns
    // one at a time, never copies it — so those windows come back with no pattern rather than a
    // blank one.
    if (strategy0 == null || (!strategy0.startsWith("recurring-") && !"cron".equals(strategy0))) {
      fields.put("cron", null);
    }
    if (strategy0 != null && strategy0.startsWith("recurring-")) {
      List<?> timeRange = fields.get("timeRange") instanceof List<?> list ? list : List.of();
      fields.put("startTime", clockTime(at(timeRange, 0)));
      fields.put("endTime", clockTime(at(timeRange, 1)));
    }

    MaintenanceWindow window = Json.MAPPER.convertValue(fields, MaintenanceWindow.class);
    String strategy = window.strategy();
    if (strategy != null && strategy.startsWith("recurring-")) {
      fields.put("cron", window.effectiveCron());
      if (!"recurring-interval".equals(strategy)) {
        fields.put("duration", window.calculatedDuration());
      } else {
        fields.put("duration", window.calculatedDuration());
      }
    } else if ("cron".equals(strategy)) {
      String complaint = MaintenanceWindow.cronComplaint(str(fields.get("cron")));
      if (complaint != null) {
        throw new IllegalArgumentException(complaint);
      }
      Object minutes = fields.get("durationMinutes");
      if (minutes != null) {
        fields.put("duration", (int) Double.parseDouble(String.valueOf(minutes)) * 60);
      }
    }
    return fields;
  }

  private static Object at(List<?> list, int index) {
    return list.size() > index ? list.get(index) : null;
  }

  /**
   * A window's instant, refused rather than stored when it is not one.
   *
   * <p>The source parses the string and refuses a date it cannot read or one past the year 9999,
   * which is what a text field a person types into can produce.
   */
  private static String validDate(Object value, String complaint) {
    String text = str(value);
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      java.time.LocalDateTime parsed =
          java.time.LocalDateTime.parse(text.trim().replace(' ', 'T'));
      if (parsed.getYear() > 9999) {
        throw new IllegalArgumentException(complaint);
      }
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException(complaint);
    }
    return text;
  }

  /** The interface's {@code {hours, minutes}} written the way a window stores a clock time. */
  private static String clockTime(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    Object hours = map.get("hours");
    Object minutes = map.get("minutes");
    if (hours == null || minutes == null) {
      return null;
    }
    Object seconds = map.get("seconds");
    int second = seconds == null ? 0 : (int) Double.parseDouble(String.valueOf(seconds));
    String time =
        String.format(
            "%02d:%02d",
            (int) Double.parseDouble(String.valueOf(hours)),
            (int) Double.parseDouble(String.valueOf(minutes)));
    // Seconds appear only when there are any, which is what the source's own writer does.
    return second == 0 ? time : time + String.format(":%02d", second);
  }

  private Reply deleteMaintenance(String id) {
    componentClient.forKeyValueEntity(id).method(MaintenanceEntity::delete).invoke();
    return new Reply(okI18n("successDeleted"), List.of(maintenanceList()));
  }

  private Reply setMaintenanceActive(String id, boolean active) {
    componentClient
        .forKeyValueEntity(id)
        .method(MaintenanceEntity::patch)
        .invoke(RecordFields.of(Map.of("active", active)));
    return new Reply(
        okI18n(active ? "successResumed" : "successPaused"), List.of(maintenanceList()));
  }

  private Reply linkMaintenance(String maintenanceId, Object links, String key) {
    componentClient
        .forKeyValueEntity(maintenanceId)
        .method(MaintenanceEntity::patch)
        .invoke(RecordFields.of(Map.of(key, links == null ? List.of() : links)));
    return Reply.of(okI18n("successAdded"));
  }

  /**
   * What a window is attached to.
   *
   * <p>The two answers are not the same shape: a monitor is named by its identifier alone and a
   * status page by its identifier and its title, because the source's two queries select different
   * columns and the interface draws the second as a list of names.
   */
  private Reply maintenanceLinks(String maintenanceId, String key, String replyKey) {
    StoredRecord record =
        componentClient.forKeyValueEntity(maintenanceId).method(MaintenanceEntity::get).invoke();
    Object links = record.get(key);
    Map<String, Object> payload = ok();
    payload.put(
        replyKey,
        "statusPages".equals(key) ? withPageTitles(links) : (links == null ? List.of() : links));
    return Reply.of(payload);
  }

  private List<Object> withPageTitles(Object links) {
    List<Object> out = new ArrayList<>();
    if (!(links instanceof List<?> list)) {
      return out;
    }
    Map<String, String> titles = new LinkedHashMap<>();
    for (RecordRow row :
        componentClient.forView().method(StatusPageListView::all).invoke().entries()) {
      titles.put(row.id(), str(parse(row.json()).get("title")));
    }
    for (Object link : list) {
      String id = link instanceof Map<?, ?> m ? str(m.get("id")) : str(link);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", id);
      entry.put("title", titles.get(id));
      out.add(entry);
    }
    return out;
  }

  // ---- status pages ----------------------------------------------------------------------------

  public Emission statusPageList() {
    Map<String, Object> pages = new LinkedHashMap<>();
    for (RecordRow row :
        componentClient.forView().method(StatusPageListView::all).invoke().entries()) {
      Map<String, Object> json = parse(row.json());
      pages.put(row.id(), StatusPages.toJson(json, true));
    }
    return Emission.of("statusPageList", pages);
  }

  private Reply addStatusPage(String title, String slug) {
    if (title == null || title.isBlank() || slug == null || slug.isBlank()) {
      return Reply.of(failed("Please input all fields"));
    }
    if (!slug.matches("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")) {
      return Reply.of(failed("Invalid Slug"));
    }
    if (StatusPages.bySlug(componentClient, slug) != null) {
      return Reply.of(failed("Invalid Slug"));
    }
    // A page is stored under its own slug, which is what makes reading one a single lookup.
    String id = slug;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("slug", slug);
    fields.put("title", title);
    fields.put("theme", "auto");
    fields.put("icon", "");
    fields.put("autoRefreshInterval", 300);
    fields.put("published", true);
    fields.put("showTags", false);
    fields.put("showPoweredBy", true);
    fields.put("publicGroupList", List.of());
    fields.put("incidents", List.of());
    componentClient.forKeyValueEntity(id).method(StatusPageEntity::put).invoke(RecordFields.of(fields));
    Map<String, Object> payload = okI18n("successAdded");
    payload.put("slug", slug);
    return new Reply(payload, List.of(statusPageList()));
  }

  private Reply getStatusPage(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failed("No slug?"));
    }
    return Reply.of(okWith("config", StatusPages.toJson(page.toJson(), true)));
  }

  private Reply saveStatusPage(
      String slug, Map<String, Object> config, String imgDataUrl, Object publicGroupList) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failed("No slug?"));
    }
    String newSlug = str(config.get("slug"));
    if (newSlug == null) {
      return Reply.of(failed("Slug cannot be empty"));
    }
    if (!newSlug.matches("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")) {
      return Reply.of(failed("Invalid Slug"));
    }
    Object analyticsType = config.get("analyticsType");
    if (analyticsType != null
        && !List.of("google", "umami", "plausible", "matomo", "rybbit")
            .contains(String.valueOf(analyticsType))) {
      return Reply.of(failed("Invalid analytics type"));
    }
    // Only a *data* URL has to be a PNG. Anything else is taken as the address of a logo and
    // stored as it stands, which is how a page is given one that lives somewhere else — refusing
    // every string that is not a PNG data URL makes that impossible.
    if (imgDataUrl != null && imgDataUrl.startsWith("data:")) {
      if (!imgDataUrl.startsWith("data:image/png;base64,")) {
        return Reply.of(failed("Only allowed PNG logo."));
      }
    }
    Map<String, Object> fields = new LinkedHashMap<>(page.fields());
    fields.putAll(config);
    if (imgDataUrl != null && !imgDataUrl.startsWith("data:")) {
      fields.put("logo", imgDataUrl);
    }
    // Group and monitor positions are the order they arrived in, numbered from one.
    fields.put("publicGroupList", StatusPages.numberGroups(publicGroupList));
    componentClient.forKeyValueEntity(newSlug).method(StatusPageEntity::put).invoke(RecordFields.of(fields));
    if (!newSlug.equals(page.id())) {
      // The slug is the identifier, so renaming one moves the page rather than copying it.
      componentClient.forKeyValueEntity(page.id()).method(StatusPageEntity::delete).invoke();
      if (("statusPage-" + slug).equals(Settings.string(componentClient, "entryPage"))) {
        Settings.write(componentClient, Map.of("entryPage", "statusPage-" + newSlug));
      }
    }
    Map<String, Object> payload = ok();
    payload.put("publicGroupList", fields.get("publicGroupList"));
    return new Reply(payload, List.of(statusPageList()));
  }

  private Reply deleteStatusPage(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failed("Status Page is not found"));
    }
    componentClient.forKeyValueEntity(page.id()).method(StatusPageEntity::delete).invoke();
    if (("statusPage-" + slug).equals(Settings.string(componentClient, "entryPage"))) {
      Settings.write(componentClient, Map.of("entryPage", "dashboard"));
    }
    return new Reply(ok(), List.of(statusPageList()));
  }

  private Reply postIncident(String slug, Map<String, Object> incident) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failed("slug is not found"));
    }
    Map<String, Object> stored = StatusPages.upsertIncident(componentClient, page, incident, true);
    // The freshly-posted incident is answered as the record that was just written, which carries
    // no last-edited instant at all. Read back out of the page afterwards it carries one holding
    // null, because that is a stored row with an empty column. The source draws the same
    // distinction and the interface reads both.
    stored.remove("lastUpdatedDate");
    return Reply.of(okWith("incident", stored));
  }

  private Reply editIncident(String slug, String incidentId, Map<String, Object> incident) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failedI18nMessage("slug is not found"));
    }
    if (blank(incident.get("title"))) {
      return Reply.of(failedI18nMessage("Please input title"));
    }
    if (blank(incident.get("content"))) {
      return Reply.of(failedI18nMessage("Please input content"));
    }
    Map<String, Object> withId = new LinkedHashMap<>(incident);
    withId.put("id", incidentId);
    Map<String, Object> stored = StatusPages.upsertIncident(componentClient, page, withId, false);
    if (stored == null) {
      return Reply.of(failedI18nMessage("Incident not found or access denied"));
    }
    Map<String, Object> payload = okI18n("Saved.");
    payload.put("incident", stored);
    return Reply.of(payload);
  }

  private Reply deleteIncident(String slug, String incidentId) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failedI18nMessage("slug is not found"));
    }
    if (!StatusPages.deleteIncident(componentClient, page, incidentId)) {
      return Reply.of(failedI18nMessage("Incident not found or access denied"));
    }
    return Reply.of(okI18n("successDeleted"));
  }

  private Reply resolveIncident(String slug, String incidentId) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failedI18nMessage("slug is not found"));
    }
    Map<String, Object> resolved =
        StatusPages.resolveIncident(componentClient, page, incidentId);
    if (resolved == null) {
      return Reply.of(failedI18nMessage("Incident not found or access denied"));
    }
    Map<String, Object> payload = okI18n("Resolved");
    payload.put("incident", resolved);
    return Reply.of(payload);
  }

  private Reply unpinIncident(String slug) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failed("slug is not found"));
    }
    StatusPages.unpinAll(componentClient, page);
    return Reply.of(ok());
  }

  private Reply incidentHistory(String slug, String cursor) {
    StoredRecord page = StatusPages.bySlug(componentClient, slug);
    if (page == null) {
      return Reply.of(failed("slug is not found"));
    }
    Map<String, Object> payload = ok();
    payload.putAll(StatusPages.incidentHistory(page, cursor));
    return Reply.of(payload);
  }

  // ---- second factor ---------------------------------------------------------------------------

  private Reply prepare2fa(String currentPassword, Sessions.Signed session) {
    StoredRecord user =
        componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
    if (user.flag("twofa_status")) {
      return Reply.of(failedI18n("2faAlreadyEnabled"));
    }
    if (!Passwords.verify(currentPassword, user.str("password"))) {
      return Reply.of(failed("Incorrect current password"));
    }
    String secret = Totp.generateSecret();
    componentClient
        .forKeyValueEntity(session.userId())
        .method(UserEntity::patch)
        .invoke(RecordFields.of(Map.of("twofa_secret", secret)));
    Map<String, Object> payload = ok();
    payload.put("uri", Totp.uri(user.str("username"), secret));
    return Reply.of(payload);
  }

  private Reply save2fa(String currentPassword, Sessions.Signed session) {
    StoredRecord user =
        componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
    if (!Passwords.verify(currentPassword, user.str("password"))) {
      return Reply.of(failed("Incorrect current password"));
    }
    componentClient
        .forKeyValueEntity(session.userId())
        .method(UserEntity::patch)
        .invoke(RecordFields.of(Map.of("twofa_status", true)));
    return Reply.of(okI18n("2faEnabled"));
  }

  private Reply disable2fa(String currentPassword, Sessions.Signed session) {
    StoredRecord user =
        componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
    if (!Passwords.verify(currentPassword, user.str("password"))) {
      return Reply.of(failed("Incorrect current password"));
    }
    componentClient
        .forKeyValueEntity(session.userId())
        .method(UserEntity::patch)
        .invoke(RecordFields.of(Map.of("twofa_status", false, "twofa_secret", null)));
    return Reply.of(okI18n("2faDisabled"));
  }

  private Reply verifyToken(String token, String currentPassword, Sessions.Signed session) {
    StoredRecord user =
        componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
    if (!Passwords.verify(currentPassword, user.str("password"))) {
      return Reply.of(failed("Incorrect current password"));
    }
    boolean valid =
        Totp.verify(token, user.str("twofa_secret"), System.currentTimeMillis())
            && !token.equals(user.str("twofa_last_token"));
    if (!valid) {
      Map<String, Object> payload = failedI18n("authInvalidToken");
      payload.put("valid", false);
      return Reply.of(payload);
    }
    Map<String, Object> payload = ok();
    payload.put("valid", true);
    return Reply.of(payload);
  }

  private Reply twoFaStatus(Sessions.Signed session) {
    StoredRecord user =
        componentClient.forKeyValueEntity(session.userId()).method(UserEntity::get).invoke();
    Map<String, Object> payload = ok();
    payload.put("status", user.flag("twofa_status"));
    return Reply.of(payload);
  }

  // ---- odds and ends ---------------------------------------------------------------------------

  private Reply initServerTimezone(String timezone) {
    Settings.write(componentClient, Map.of("serverTimezone", timezone, "initServerTimezone", true));
    return new Reply(
        null,
        List.of(
            Emission.of(
                "info", Settings.publicInfo(componentClient, false, Versions.APP_VERSION))));
  }

  private Reply testChrome(String executable) {
    try {
      String version =
          io.akka.uptimekuma.checks.Probes.chromeVersion(executable);
      Map<String, Object> payload = ok();
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("key", "foundChromiumVersion");
      message.put("values", List.of(version));
      payload.put("msg", message);
      payload.put("msgi18n", true);
      return Reply.of(payload);
    } catch (Exception e) {
      return Reply.of(failed(e.getMessage()));
    }
  }

  private Reply pushExample(String language) {
    if (language == null || !language.matches("^[a-z-]+$")) {
      return Reply.of(failed("Invalid language"));
    }
    String code = PushExamples.forLanguage(language);
    if (code == null) {
      return Reply.of(failed("Not found"));
    }
    return Reply.of(okWith("code", code));
  }

  /**
   * The tunnel the source can start beside itself.
   *
   * <p>Not run here: it is a second, separate program the source spawns and supervises, which is a
   * process-management feature rather than a monitoring one. The calls answer plainly rather than
   * appearing to work. Declared in the README.
   */
  private Reply cloudflared(String event) {
    return new Reply(
        null,
        List.of(
            Emission.of("cloudflared_installed", false),
            Emission.of("cloudflared_running", false),
            Emission.of(
                "cloudflared_message",
                "This rebuild does not run a tunnel process beside itself.")));
  }

  private long databaseSize() {
    long beats = 0;
    for (MonitorListView.MonitorRow row :
        componentClient.forView().method(MonitorListView::all).invoke().monitors()) {
      beats += allBeats(row.id()).size();
    }
    // An estimate rather than a file size: the store is not a file this service can measure, and
    // the screen that shows this uses it to answer "is my history getting big".
    return beats * 256L;
  }

  /**
   * The titles the interface offers, with the settings each one implies.
   *
   * <p>`options` is not decoration: choosing a title in the interface reads `options.port` off the
   * entry and fills the monitor's port field with it, so a list carrying names and no options gives
   * every game monitor an empty port. The table is `gamedig`'s own, taken from the same place the
   * source's handler takes it.
   */
  /**
   * The public half of this server's web-push signing key, made on the first ask and kept.
   *
   * <p>A VAPID key pair is an ECDSA key on the P-256 curve, and the public half travels as the
   * uncompressed point in URL-safe base64 — which is what the source's own library produces and
   * what a browser's push subscription expects. Making it here rather than answering with an empty
   * string is the difference between a page that can offer push and one whose button does nothing;
   * delivering a push notification is separately out of scope and says so.
   */
  private String webpushPublicKey() {
    String existing = Settings.string(componentClient, "webpushPublicVapidKey");
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    try {
      java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
      generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
      java.security.KeyPair pair = generator.generateKeyPair();
      java.security.interfaces.ECPublicKey publicKey =
          (java.security.interfaces.ECPublicKey) pair.getPublic();
      byte[] point = new byte[65];
      point[0] = 0x04;
      copyFixed(publicKey.getW().getAffineX().toByteArray(), point, 1);
      copyFixed(publicKey.getW().getAffineY().toByteArray(), point, 33);
      String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(point);
      Map<String, Object> keys = new LinkedHashMap<>();
      keys.put("webpushPublicVapidKey", encoded);
      keys.put(
          "webpushPrivateVapidKey",
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(pair.getPrivate().getEncoded()));
      Settings.write(componentClient, keys);
      return encoded;
    } catch (Exception e) {
      throw new IllegalStateException("cannot make a web-push key: " + e.getMessage(), e);
    }
  }

  /**
   * A coordinate written into its fixed 32-byte slot.
   *
   * <p>A big integer's own bytes carry a leading zero when the top bit is set and drop leading
   * zeros otherwise, so neither end of the array can be assumed: the value is right-aligned.
   */
  private static void copyFixed(byte[] value, byte[] into, int at) {
    int length = Math.min(value.length, 32);
    System.arraycopy(value, value.length - length, into, at + 32 - length, length);
  }

  private List<Object> gameList() {
    List<Object> games = new ArrayList<>();
    for (Map<String, Object> game : io.akka.uptimekuma.checks.GameProtocols.catalogue()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("keys", game.get("keys"));
      entry.put("pretty", game.get("pretty"));
      entry.put("options", game.get("options"));
      entry.put("extra", game.get("extra"));
      games.add(entry);
    }
    return games;
  }

  // ---- shapes ----------------------------------------------------------------------------------

  /**
   * The monitor a payload describes, over the defaults its columns carry.
   *
   * <p>The overlay is the point. A record built straight from the payload gives every field the
   * interface left out Java's own zero — false, 0, null — where the source's table gives it the
   * column's default: a ping monitor's packet size is 56 rather than 0, a monitor's weight is 2000
   * rather than 0, and its response limit is 1024 bytes rather than none. Twenty fields differed
   * that way, and every one of them is drawn into the interface's own edit form, so a monitor added
   * through this port came back describing settings nobody chose.
   */
  private MonitorConfig configFrom(Map<String, Object> raw, String id) {
    Map<String, Object> fields =
        Json.MAPPER.convertValue(MonitorConfig.blank(id), LinkedHashMap.class);
    fields.putAll(raw);
    fields.put("id", id);
    // Three keys the interface adds for its own use and the source deletes on arrival.
    fields.remove("humanReadableInterval");
    fields.remove("globalpingdnsresolvetypeoptions");
    fields.remove("responsecheck");
    MonitorConfig parsed = Json.MAPPER.convertValue(fields, MonitorConfig.class);
    // A field the interface left out arrives as null. Four of them are collections the rest of
    // the system iterates, so an absent one is made empty here rather than guarded everywhere.
    return parsed
        .toBuilder()
        .tags(parsed.tags() == null ? List.of() : parsed.tags())
        .conditions(parsed.conditions() == null ? List.of() : parsed.conditions())
        .notificationIDList(
            parsed.notificationIDList() == null ? Map.of() : parsed.notificationIDList())
        .acceptedStatusCodes(
            parsed.accepted_statuscodes() == null
                ? List.of("200-299")
                : parsed.accepted_statuscodes())
        .build();
  }

  public static Map<String, Object> ok() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    return payload;
  }

  public static Map<String, Object> okWith(String key, Object value) {
    Map<String, Object> payload = ok();
    payload.put(key, value);
    return payload;
  }

  public static Map<String, Object> okI18n(String message) {
    Map<String, Object> payload = ok();
    payload.put("msg", message);
    payload.put("msgi18n", true);
    return payload;
  }

  public static Map<String, Object> failed(String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", false);
    payload.put("msg", message);
    return payload;
  }

  public static Map<String, Object> failedI18n(String key) {
    Map<String, Object> payload = failed(key);
    payload.put("msgi18n", true);
    return payload;
  }

  /**
   * A refusal the source marks as translatable even though the words are plain English.
   *
   * <p>Reproduced because the interface looks the message up before showing it, and a message
   * marked one way here and the other way there renders differently.
   */
  public static Map<String, Object> failedI18nMessage(String message) {
    return failedI18n(message);
  }

  private static boolean blank(Object value) {
    return value == null || String.valueOf(value).trim().isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String json) {
    try {
      return Json.MAPPER.readValue(json, LinkedHashMap.class);
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String str(List<Object> args, int index) {
    Object value = index < args.size() ? args.get(index) : null;
    return value == null ? null : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(List<Object> args, int index) {
    Object value = index < args.size() ? args.get(index) : null;
    return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
  }

  private static boolean bool(List<Object> args, int index) {
    Object value = index < args.size() ? args.get(index) : null;
    return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
  }

  /** A number the caller may legitimately have left out, where absent and zero mean different things. */
  private static Double optionalNumber(List<Object> args, int index) {
    Object value = index < args.size() ? args.get(index) : null;
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (Exception e) {
      return null;
    }
  }

  private static double number(List<Object> args, int index) {
    Object value = index < args.size() ? args.get(index) : null;
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (Exception e) {
      return 0;
    }
  }
}
