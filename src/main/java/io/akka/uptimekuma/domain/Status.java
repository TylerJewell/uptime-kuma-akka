package io.akka.uptimekuma.domain;

/** The four values a check can leave a monitor in. */
public enum Status {
  DOWN,
  UP,
  PENDING,
  MAINTENANCE
}
