package com.signalframe.research.application.evidence;

import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import com.signalframe.shared.ApplicationException;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The only way TASK-07 reaches the hypothesis engine.
 *
 * <p>TASK-07 submits commands and reads results; it never touches
 * {@code hypotheses.confidence}, {@code hypotheses.status},
 * {@code hypotheses.version} or {@code hypothesis_events} (freeze §4.1 invariant 7).
 *
 * <h2>Why the port is resolved lazily</h2>
 * The port <em>signature</em> is frozen in the Wave 2B contract freeze and lives in
 * {@code research/domain/hypotheses}, but its <em>implementation</em> belongs to
 * TASK-06 and lands on a parallel branch. Resolving the port through an
 * {@link ObjectProvider} keeps this revision startable and testable on its own —
 * every other {@code @SpringBootTest} context still boots — while the integrated
 * revision injects TASK-06's bean with no change here.
 *
 * <p>When the port is absent the call fails loudly with {@code 503 UNAVAILABLE}
 * rather than silently skipping the notification: an evidence item or a verification
 * that is stored without its hypothesis transition would be exactly the unattributed
 * confidence movement this port exists to prevent. The failure is raised inside the
 * caller's transaction, so nothing is written.
 */
@Component
public class HypothesisTransitionGateway {

  private final ObjectProvider<HypothesisTransitionPort> ports;

  public HypothesisTransitionGateway(
    ObjectProvider<HypothesisTransitionPort> ports
  ) {
    this.ports = ports;
  }

  /**
   * Applies one transition through the frozen port.
   *
   * @throws ApplicationException {@code 503 UNAVAILABLE} when no implementation is
   *     present in this revision; the port's own {@code 404}/{@code 409}/{@code 422}
   *     outcomes propagate unchanged
   */
  public HypothesisTransitionResult transition(
    HypothesisTransitionCommand command
  ) {
    Objects.requireNonNull(command, "command");
    HypothesisTransitionPort port = ports.getIfAvailable();
    if (port == null) {
      throw new ApplicationException(
        503,
        "UNAVAILABLE",
        "the hypothesis transition port is not available in this revision; " +
        "no evidence or verification change was recorded"
      );
    }
    return port.transition(command);
  }
}
