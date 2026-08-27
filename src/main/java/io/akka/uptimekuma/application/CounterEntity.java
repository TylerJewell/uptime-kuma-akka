package io.akka.uptimekuma.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * The next identifier for one kind of row.
 *
 * <p>Rows are numbered from one, per kind, the way the source's tables number them. It would be
 * simpler to give each row a random identifier, but the number is visible — a monitor's own screen
 * shows it, a badge URL contains it, and a status page links to it — so a rebuild that used
 * something else would differ everywhere those appear.
 */
@Component(id = "counter")
public class CounterEntity extends KeyValueEntity<Long> {

  @Override
  public Long emptyState() {
    return 0L;
  }

  public Effect<Long> next() {
    long allocated = currentState() + 1;
    return effects().updateState(allocated).thenReply(allocated);
  }

  public ReadOnlyEffect<Long> current() {
    return effects().reply(currentState());
  }

  /** Move the counter up so an identifier that already exists is never handed out again. */
  public Effect<Long> atLeast(Long floor) {
    if (currentState() >= floor) {
      return effects().reply(currentState());
    }
    return effects().updateState(floor).thenReply(floor);
  }
}
