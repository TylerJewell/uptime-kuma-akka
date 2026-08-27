package io.akka.uptimekuma.notifications;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every notification target, by name.
 *
 * <p>The name is the primary key in three places at once — the {@code type} on a stored
 * notification, the key the interface picks a settings form by, and the key here — so a name that
 * does not match is a target whose form never renders and whose messages never send. The registry
 * refuses a target with no name and refuses two targets sharing one, which is what the source does
 * when it builds the same map.
 */
public final class Providers {

  private Providers() {}

  private static final Map<String, Provider> BY_NAME = build();

  private static Map<String, Provider> build() {
    List<Provider> all = new ArrayList<>();
    all.addAll(SmsProviders.all());
    all.addAll(ChatProviders.all());
    all.addAll(IncidentProviders.all());
    all.addAll(PushProviders.all());
    all.addAll(EmailProviders.all());

    Map<String, Provider> byName = new LinkedHashMap<>();
    for (Provider provider : all) {
      if (provider.name() == null || provider.name().isBlank()) {
        throw new IllegalStateException("Notification provider without name");
      }
      if (byName.put(provider.name(), provider) != null) {
        throw new IllegalStateException("Duplicate notification provider name");
      }
    }
    return byName;
  }

  public static List<String> names() {
    return List.copyOf(BY_NAME.keySet());
  }

  public static Provider byName(String name) {
    return BY_NAME.get(name);
  }

  /**
   * Send one notification.
   *
   * @return the provider's own success message
   * @throws Exception "Notification type is not supported" for a name nothing registers, or
   *     whatever the provider raised
   */
  public static String send(
      Config config,
      String msg,
      Map<String, Object> monitorJson,
      Map<String, Object> heartbeatJson,
      Context context)
      throws Exception {
    Provider provider = BY_NAME.get(config.type());
    if (provider == null) {
      throw new Exception("Notification type is not supported");
    }
    return provider.send(config, msg, monitorJson, heartbeatJson, context);
  }
}
