package io.akka.uptimekuma.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import akka.stream.javadsl.Source;
import io.akka.uptimekuma.application.HeartbeatFeedView;
import io.akka.uptimekuma.application.MonitorBeat;
import io.akka.uptimekuma.application.MonitorEntity;
import io.akka.uptimekuma.application.MonitorSummaryView;
import io.akka.uptimekuma.application.NotificationEntity;
import io.akka.uptimekuma.domain.Heartbeat;
import io.akka.uptimekuma.domain.HeartbeatAnnouncement;
import io.akka.uptimekuma.domain.MonitorConfig;
import io.akka.uptimekuma.domain.NotificationTarget;
import java.time.Duration;
import java.util.List;

/**
 * The port's own surface: everything the capability does is reachable from here — including,
 * per RENDERING.md R7, the state a GUI client subscribes to rather than polls for.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/monitors")
public class MonitorEndpoint extends AbstractHttpEndpoint {

  public record MonitorView(
      String id,
      MonitorConfig config,
      boolean active,
      boolean underMaintenance,
      List<Heartbeat> heartbeats) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public MonitorEndpoint(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  /** Every monitor's configuration — the GUI's initial, non-streamed render. RENDERING.md R1.4. */
  @Get("/")
  public HttpResponse list() {
    return HttpResponses.ok(componentClient.forView().method(MonitorSummaryView::list).invoke());
  }

  /**
   * What a monitor has done since {@code ?since=} (default: the beginning) — the gap a
   * reconnecting GUI client fills before its live stream takes over.
   */
  @Get("/{id}/heartbeats")
  public HttpResponse heartbeatsSince(String id) {
    long from = requestContext().queryParams().getLong("since").orElse(0L);
    var announcements =
        componentClient
            .forView()
            .method(HeartbeatFeedView::replay)
            .invoke(new HeartbeatFeedView.From(id, from));
    return HttpResponses.ok(announcements);
  }

  /**
   * One monitor's heartbeats, pushed — RENDERING.md R1. Resumes from {@code ?since=} or, on a
   * reconnect the browser makes itself, the {@code Last-Event-ID} header, so nothing is missed
   * across a dropped connection (R1.3).
   */
  @Get("/{id}/heartbeats/stream")
  public HttpResponse heartbeatStream(String id) {
    long since = resumeFrom();
    var missed =
        componentClient
            .forView()
            .method(HeartbeatFeedView::replay)
            .invoke(new HeartbeatFeedView.From(id, since))
            .items();
    long caughtUpAt = missed.isEmpty() ? since : missed.get(missed.size() - 1).sequence();
    var source =
        Source.from(missed)
            .concat(
                componentClient
                    .forView()
                    .stream(HeartbeatFeedView::stream)
                    .source(new HeartbeatFeedView.From(id, caughtUpAt)));
    // Replayed and live frames are the same shape, and a client needs to tell them apart:
    // a beat that already happened must not raise the alert a beat happening now does.
    // Everything at or below the catch-up point came out of the replay by construction.
    return HttpResponses.serverSentEvents(
        source,
        a -> Long.toString(a.sequence()),
        a -> a.sequence() <= caughtUpAt ? "history" : "heartbeat");
  }

  /**
   * Every monitor's heartbeats, pushed — what the dashboard's monitor list subscribes to instead
   * of polling. Not resumable by design: a client reattaching here re-renders from {@link #list}
   * and {@link #heartbeatsSince} first, the same way the initial render does.
   */
  @Get("/stream")
  public HttpResponse dashboardStream() {
    Source<HeartbeatAnnouncement, ?> source =
        componentClient.forView().stream(HeartbeatFeedView::streamAll).source();
    return HttpResponses.serverSentEvents(
        source, a -> Long.toString(a.sequence()), a -> "heartbeat");
  }

  private long resumeFrom() {
    return requestContext()
        .lastSeenSseEventId()
        .or(() -> requestContext().queryParams().getString("since"))
        .map(Long::parseLong)
        .orElse(0L);
  }

  @Put("/{id}")
  public HttpResponse create(String id, MonitorConfig config) {
    var reply =
        componentClient.forEventSourcedEntity(id).method(MonitorEntity::create).invoke(config);
    return HttpResponses.created(reply);
  }

  @Get("/{id}")
  public HttpResponse get(String id) {
    var state = componentClient.forEventSourcedEntity(id).method(MonitorEntity::get).invoke();
    // An entity that was never created has empty state rather than none, so without this a
    // monitor nobody made answers 200 with every field null — which a caller reads as a
    // monitor that exists and knows nothing about itself.
    if (!state.created()) {
      return HttpResponses.notFound();
    }
    return HttpResponses.ok(
        new MonitorView(
            id, state.config(), state.active(), state.underMaintenance(), state.history()));
  }

  @Post("/{id}/start")
  public HttpResponse start(String id) {
    var reply = componentClient.forEventSourcedEntity(id).method(MonitorEntity::start).invoke();
    // Only a monitor that was actually stopped gets a beat armed. Arming replaces whatever
    // is pending, so arming again for a monitor that is already running would push its next
    // beat out by a whole interval every time somebody asked it to start.
    if ("started".equals(reply)) {
      timers.createSingleTimer(
          MonitorBeat.timerName(id),
          Duration.ofMillis(1),
          componentClient
              .forTimedAction()
              .method(MonitorBeat::beat)
              .deferred(new MonitorBeat.Beat(id)));
    }
    return HttpResponses.ok(reply);
  }

  @Post("/{id}/stop")
  public HttpResponse stop(String id) {
    var reply = componentClient.forEventSourcedEntity(id).method(MonitorEntity::stop).invoke();
    // Silent when nothing is pending, which is the case for a monitor stopped before its
    // first beat was ever armed.
    timers.delete(MonitorBeat.timerName(id));
    return HttpResponses.ok(reply);
  }

  @Post("/{id}/maintenance/{under}")
  public HttpResponse maintenance(String id, Boolean under) {
    return HttpResponses.ok(
        componentClient
            .forEventSourcedEntity(id)
            .method(MonitorEntity::setMaintenance)
            .invoke(under));
  }

  @Put("/notifications/{notificationId}")
  public HttpResponse putNotification(String notificationId, NotificationTarget target) {
    return HttpResponses.created(
        componentClient
            .forKeyValueEntity(notificationId)
            .method(NotificationEntity::put)
            .invoke(target));
  }
}
