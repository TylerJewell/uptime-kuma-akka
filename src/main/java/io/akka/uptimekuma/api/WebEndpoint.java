package io.akka.uptimekuma.api;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.uptimekuma.application.Settings;

/**
 * The interface itself: its files, and the paths it routes in the browser.
 *
 * <p>The routes are named one by one rather than caught by a single wildcard at the root. A
 * wildcard there swallows the runtime's own paths as well as the other endpoints', and the runtime
 * refuses a bare wildcard sitting beside any named route in the same endpoint. So the list below is
 * the interface's own route table, from its {@code router.js}, plus the files its entry document
 * asks for.
 *
 * <p>A path in that table names no file, so it is answered with the entry document: the interface
 * routes in the browser, and a reload on {@code /dashboard/3} has to reach it rather than a refusal.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class WebEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public WebEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** Where a visitor arriving at the root is sent, which is a setting. */
  @Get("/")
  public HttpResponse root() {
    String entry = Settings.string(componentClient, "entryPage");
    if (entry != null && entry.startsWith("statusPage-")) {
      return redirect("/status/" + entry.substring("statusPage-".length()));
    }
    return redirect("/dashboard");
  }

  /** Whether a crawler should index this server, which is a setting too. */
  @Get("/robots.txt")
  public HttpResponse robots() {
    boolean indexable = Settings.flag(componentClient, "searchEngineIndex");
    return HttpResponses.ok("User-agent: *\nDisallow:" + (indexable ? "" : " /"));
  }

  // ---- the files the interface is built from ---------------------------------------------------

  @Get("/assets/**")
  public HttpResponse assets(HttpRequest request) {
    return file(request);
  }

  @Get("/upload/**")
  public HttpResponse uploads(HttpRequest request) {
    return file(request);
  }

  @Get("/screenshots/**")
  public HttpResponse screenshots(HttpRequest request) {
    return file(request);
  }

  @Get("/icon.svg")
  public HttpResponse iconSvg(HttpRequest request) {
    return file(request);
  }

  @Get("/icon.png")
  public HttpResponse iconPng(HttpRequest request) {
    return file(request);
  }

  @Get("/icon-192x192.png")
  public HttpResponse iconSmall(HttpRequest request) {
    return file(request);
  }

  @Get("/icon-512x512.png")
  public HttpResponse iconLarge(HttpRequest request) {
    return file(request);
  }

  @Get("/favicon.ico")
  public HttpResponse favicon(HttpRequest request) {
    return file(request);
  }

  @Get("/apple-touch-icon.png")
  public HttpResponse appleIcon(HttpRequest request) {
    return file(request);
  }

  @Get("/apple-touch-icon-precomposed.png")
  public HttpResponse appleIconPrecomposed(HttpRequest request) {
    return file(request);
  }

  @Get("/manifest.json")
  public HttpResponse manifest(HttpRequest request) {
    return file(request);
  }

  @Get("/serviceWorker.js")
  public HttpResponse serviceWorker(HttpRequest request) {
    return file(request);
  }

  // ---- the paths the interface routes in the browser --------------------------------------------

  @Get("/dashboard")
  public HttpResponse dashboard() {
    return entryDocument();
  }

  @Get("/dashboard/**")
  public HttpResponse dashboardChild() {
    return entryDocument();
  }

  @Get("/list")
  public HttpResponse list() {
    return entryDocument();
  }

  @Get("/add")
  public HttpResponse add() {
    return entryDocument();
  }

  @Get("/clone/**")
  public HttpResponse cloneMonitor() {
    return entryDocument();
  }

  @Get("/edit/**")
  public HttpResponse edit() {
    return entryDocument();
  }

  @Get("/settings")
  public HttpResponse settings() {
    return entryDocument();
  }

  @Get("/settings/**")
  public HttpResponse settingsSection() {
    return entryDocument();
  }

  @Get("/manage-status-page")
  public HttpResponse manageStatusPages() {
    return entryDocument();
  }

  @Get("/add-status-page")
  public HttpResponse addStatusPage() {
    return entryDocument();
  }

  @Get("/maintenance")
  public HttpResponse maintenance() {
    return entryDocument();
  }

  @Get("/maintenance/**")
  public HttpResponse maintenanceChild() {
    return entryDocument();
  }

  @Get("/add-maintenance")
  public HttpResponse addMaintenance() {
    return entryDocument();
  }

  @Get("/setup")
  public HttpResponse setup() {
    return entryDocument();
  }

  @Get("/setup-database")
  public HttpResponse setupDatabase() {
    return entryDocument();
  }

  @Get("/status")
  public HttpResponse status() {
    return entryDocument();
  }

  /**
   * Everything under a status page's own path is the interface, with one exception.
   *
   * <p>{@code /status/<slug>/rss} is a feed rather than a page: the source serves XML there, and a
   * reader subscribed to it is not running the interface. It cannot be a route of its own —
   * {@code /status/{slug}/rss} and {@code /status/**} are two wildcards over the same path and the
   * runtime refuses a service that declares both — so the one catch-all reads the path it was
   * given and answers whichever of the two the caller asked for.
   */
  @Get("/status/**")
  public HttpResponse statusPage(HttpRequest request) {
    String path = request.getUri().path();
    if (path.endsWith("/rss")) {
      String slug = path.substring("/status/".length(), path.length() - "/rss".length());
      if (!slug.isEmpty() && !slug.contains("/")) {
        return StatusPageFeed.respond(componentClient, slug.toLowerCase(java.util.Locale.ROOT));
      }
    }
    return entryDocument();
  }

  @Get("/status-page")
  public HttpResponse statusPageAlias() {
    return entryDocument();
  }

  @Get("/empty")
  public HttpResponse empty() {
    return entryDocument();
  }

  private HttpResponse entryDocument() {
    return HttpResponses.staticResource("index.html");
  }

  private HttpResponse file(HttpRequest request) {
    return HttpResponses.staticResource(request, "/");
  }

  private static HttpResponse redirect(String location) {
    return HttpResponse.create()
        .withStatus(StatusCodes.FOUND)
        .addHeader(akka.http.javadsl.model.headers.Location.create(location));
  }
}
