package io.akka.uptimekuma;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.uptimekuma.application.ClearOldData;
import io.akka.uptimekuma.application.Settings;
import io.akka.uptimekuma.application.Versions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What has to be running before the first request arrives.
 *
 * <p>One thing: the daily job that forgets old beats. Every other timer in this service is armed
 * by something a person did — adding a monitor, resuming one — and so arms itself again after a
 * restart. This one is nobody's doing, so nothing would ever start it.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public Bootstrap(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  @Override
  public void onStartup() {
    long now = System.currentTimeMillis();
    String timezone = Settings.timezone(componentClient);
    ClearOldData.arm(componentClient, timers, now, timezone);
    log.info("uptime-kuma {} starting, retention job armed in {}", Versions.APP_VERSION, timezone);
  }
}
