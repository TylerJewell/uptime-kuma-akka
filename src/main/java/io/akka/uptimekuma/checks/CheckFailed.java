package io.akka.uptimekuma.checks;

/**
 * What a check raises when the thing it checked is not healthy.
 *
 * <p>Its message becomes the heartbeat's message verbatim, so the wording is behaviour rather than
 * diagnostics: a notification quotes it and the interface shows it in the events table.
 */
public class CheckFailed extends Exception {

  private final Double ping;
  private final String response;

  public CheckFailed(String message) {
    this(message, null, null);
  }

  public CheckFailed(String message, Double ping, String response) {
    super(message);
    this.ping = ping;
    this.response = response;
  }

  public Double ping() {
    return ping;
  }

  public String response() {
    return response;
  }
}
