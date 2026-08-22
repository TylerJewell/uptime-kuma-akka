package io.akka.uptimekuma.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.uptimekuma.domain.NotificationTarget;
import org.junit.jupiter.api.Test;

/**
 * A lookup of a notification that was never registered has to be readable as an absence rather than
 * a failure, because the fan-out has to tell "this target refused" apart from "there is no such
 * target" and carry on either way.
 */
class NotificationEntityTest {

  @Test
  void aTargetThatWasNeverRegisteredReadsAsAbsent() {
    var testKit = KeyValueEntityTestKit.of("never-registered", NotificationEntity::new);

    var lookup = testKit.method(NotificationEntity::get).invoke().getReply();

    assertThat(lookup.target()).isNull();
  }

  @Test
  void aRegisteredTargetReadsBack() {
    var testKit = KeyValueEntityTestKit.of("hook", NotificationEntity::new);
    testKit
        .method(NotificationEntity::put)
        .invoke(new NotificationTarget("hook", "http://example.invalid/hook"));

    var lookup = testKit.method(NotificationEntity::get).invoke().getReply();

    assertThat(lookup.target().name()).isEqualTo("hook");
    assertThat(lookup.target().webhookUrl()).isEqualTo("http://example.invalid/hook");
  }
}
