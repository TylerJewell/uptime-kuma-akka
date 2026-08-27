package io.akka.uptimekuma.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import akka.stream.javadsl.Source;
import io.akka.uptimekuma.application.HeartbeatFeedView;
import io.akka.uptimekuma.application.Ids;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.Versions;
import io.akka.uptimekuma.domain.Heartbeat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the interface talks to instead of a socket.
 *
 * <p>The source's front end and its server hold one long-lived connection over which calls and
 * pushes both travel. This port replaces that transport and nothing else: a call is a request whose
 * answer carries both the callback's payload and any messages the source would have pushed as a
 * consequence, and the messages nobody asked for arrive on a stream — which is what RENDERING.md R1
 * requires, and it resumes from where it left off rather than starting again.
 *
 * <p>Nothing about the interface changes to talk to this. One module in it — the one that used to
 * open the socket — talks here instead, and every component, route, style and asset is the
 * source's.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/socket")
public class SocketBridgeEndpoint extends AbstractHttpEndpoint {

  /**
   * @param args the call's arguments, in the order the interface passes them
   * @param token the session token, or absent for a call that does not need one
   */
  public record Call(List<Object> args, String token) {}

  /**
   * @param result what the interface's callback is handed
   * @param emit the messages to dispatch to the interface's own handlers
   */
  public record Answer(Object result, List<Emission> emit) {}

  private final ComponentClient componentClient;
  private final SocketHandlers handlers;

  public SocketBridgeEndpoint(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.handlers = new SocketHandlers(componentClient, timers);
  }

  @Post("/{event}")
  public HttpResponse call(String event, Call body) {
    List<Object> args = body == null || body.args() == null ? List.of() : body.args();
    Sessions.Signed session = null;
    if (!handlers.isPublic(event)) {
      session = authorise(body);
      if (session == null) {
        return HttpResponses.ok(
            new Answer(SocketHandlers.failed("You are not logged in."), List.of()));
      }
    }
    try {
      SocketHandlers.Reply reply = handlers.handle(event, args, session);
      return HttpResponses.ok(new Answer(reply.payload(), reply.emissions()));
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return HttpResponses.ok(new Answer(SocketHandlers.failed(message), List.of()));
    }
  }

  /**
   * What the interface is told the moment it connects.
   *
   * <p>The source pushes this over the socket on connection; here it is a separate read, so a
   * client that reconnects gets it again without the stream having to carry it.
   */
  @Get("/hello")
  public HttpResponse hello() {
    List<Emission> emissions = new ArrayList<>();
    emissions.add(
        Emission.of("info", Settings.publicInfo(componentClient, true, Versions.APP_VERSION)));
    if (Sessions.needsSetup(componentClient)) {
      emissions.add(Emission.of("setup"));
    } else if (Settings.flag(componentClient, "disableAuth")) {
      emissions.add(Emission.of("autoLogin"));
    } else {
      emissions.add(Emission.of("loginRequired"));
    }
    return HttpResponses.ok(new Answer(null, emissions));
  }

  /**
   * Everything the interface would have been pushed after signing in.
   *
   * <p>Reached when authentication is switched off, where the source signs a client in without it
   * ever having called {@code login} and pushes the same sequence.
   */
  @Get("/after-login")
  public HttpResponse afterLogin() {
    if (!Settings.flag(componentClient, "disableAuth") && authorise(null) == null) {
      return HttpResponses.ok(
          new Answer(SocketHandlers.failed("You are not logged in."), List.of()));
    }
    return HttpResponses.ok(new Answer(null, handlers.afterLogin()));
  }

  /**
   * The messages nobody asked for.
   *
   * <p>One connection carries every monitor's beats. A beat arriving also means the derived
   * figures beside it have moved, so those are sent with it rather than being polled for — which is
   * the whole point of the stream.
   *
   * <p>Resumes from {@code Last-Event-ID}, which a browser sends by itself when it reconnects, so a
   * dropped connection refills its own gap without the page asking.
   */
  @Get("/stream")
  public HttpResponse stream() {
    // A client that has never held this stream carries no resume point, and it does not need one:
    // signing in already handed it every monitor's beats as a list, and the bar on a monitor's page
    // draws that list and then appends whatever arrives here. So a first connection starts at the
    // feed's current end and is sent nothing it already has; a client that lost the stream comes
    // back with the id of the last frame it saw and is sent the gap.
    long tip = Ids.current(componentClient, "feed");
    long since = resumeFrom().orElse(tip);

    List<HeartbeatFeedView.HeartbeatRow> missed =
        componentClient
            .forView()
            .method(HeartbeatFeedView::replayFeed)
            .invoke(new HeartbeatFeedView.Since(since))
            .items();

    Source<HeartbeatFeedView.HeartbeatRow, ?> live =
        componentClient
            .forView()
            .stream(HeartbeatFeedView::streamAll)
            .source(new HeartbeatFeedView.Since(Math.max(since, tip)));

    Source<HeartbeatFeedView.HeartbeatRow, ?> all = Source.from(missed).concat(live);
    Source<Frame, ?> frames = all.map(row -> frame(row, row.feedSequence() <= tip));

    return HttpResponses.serverSentEvents(
        frames, f -> Long.toString(f.sequence()), f -> f.replayed() ? "history" : "live");
  }

  /**
   * @param sequence the beat's place in the feed as a whole, which is what a client resumes from. A
   *     beat's own sequence counts only that monitor's beats, so two monitors beating at the same
   *     moment would give two frames the same id and a resume would then start in the wrong place.
   * @param replayed whether this beat is one the client missed rather than one happening now. A
   *     replayed beat must not raise the alert a live one does.
   */
  public record Frame(long sequence, boolean replayed, List<Emission> emit) {}

  private Frame frame(HeartbeatFeedView.HeartbeatRow row, boolean replayed) {
    Heartbeat beat = row.toHeartbeat();
    List<Emission> emissions = new ArrayList<>();
    emissions.add(Emission.of("heartbeat", handlers.beatJson(beat)));
    if (!replayed) {
      // The figures beside a beat move when the beat lands, so they travel with it rather than
      // being asked for afterwards.
      emissions.addAll(handlers.stats(beat.monitorId()));
    }
    return new Frame(row.feedSequence(), replayed, emissions);
  }

  /**
   * Where a stream resumes, or empty for a client that has never held it.
   *
   * <p>Zero is a real resume point — it means "everything from the first beat" — so a fresh client
   * cannot be given it as a default. The two are told apart because the browser supplies
   * {@code Last-Event-ID} only on a reconnect.
   */
  private java.util.Optional<Long> resumeFrom() {
    return requestContext()
        .lastSeenSseEventId()
        .or(() -> requestContext().queryParams().getString("since"))
        .map(Long::parseLong);
  }

  private Sessions.Signed authorise(Call body) {
    if (Settings.flag(componentClient, "disableAuth")) {
      var user = Sessions.firstUser(componentClient);
      return user == null ? null : new Sessions.Signed(user.str("username"), user.id());
    }
    String token = body == null ? null : body.token();
    if (token == null) {
      token =
          requestContext()
              .requestHeader("Authorization")
              .map(header -> header.value().replaceFirst("(?i)^Bearer ", ""))
              .orElse(null);
    }
    if (token == null) {
      token = requestContext().queryParams().getString("token").orElse(null);
    }
    return Sessions.verify(componentClient, token);
  }
}
