package io.akka.uptimekuma.notifications;

import java.util.Map;

/**
 * One notification target, and the request it makes.
 *
 * <p>Every provider in the source is a class with a name and one method that composes an outbound
 * call, and that is what is reproduced: the name is the primary key — it is the {@code type} stored
 * on a notification row, the key the interface's settings form is selected by, and the key this
 * registry is built on — and the method is judged by the request it composes.
 *
 * <p>A provider does not perform its own I/O. It is handed a {@link Sender}, so what it composed
 * can be captured and compared against what the source composes for the same configuration, which
 * is the only comparison available for a call to somebody else's service.
 */
public interface Provider {

  /** The exact {@code name} literal the source's class carries. */
  String name();

  /**
   * @param config the notification's stored configuration, parsed from its JSON blob
   * @param msg the message text, already assembled by the caller
   * @param monitorJson the monitor as the source serialises it for a provider, or null on a test
   * @param heartbeatJson the beat, with the timezone, local time and last-down time the caller
   *     added, or null on a test
   * @return the success message the source returns, which the interface shows verbatim
   * @throws Exception with the message the source raises
   */
  String send(
      Config config,
      String msg,
      Map<String, Object> monitorJson,
      Map<String, Object> heartbeatJson,
      Context context)
      throws Exception;
}
