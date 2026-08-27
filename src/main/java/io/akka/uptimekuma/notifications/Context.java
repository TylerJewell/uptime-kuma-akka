package io.akka.uptimekuma.notifications;

/**
 * What a provider needs that is neither its own configuration nor the beat.
 *
 * @param sender where a composed request goes
 * @param primaryBaseURL the setting eleven providers put a link to the dashboard together from.
 *     Null when it has never been set, which is the case on a fresh install and is why every one of
 *     those eleven guards on it.
 * @param appVersion the version string one provider reports
 */
public record Context(Sender sender, String primaryBaseURL, String appVersion) {

  /** The dashboard path a monitor's own screen sits at. */
  public static String monitorRelativeUrl(Object monitorId) {
    return "/dashboard/" + monitorId;
  }
}
