package com.signalframe.research.evidence;

import com.signalframe.contract.*;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording stand-in for the frozen {@link HypothesisTransitionPort}.
 *
 * <p>TASK-06 owns the port implementation and it lands on a parallel branch, so
 * TASK-07 tests its own <em>calling</em> behaviour against this fake. The fake is
 * deliberately not a hypothesis engine: it owns no database, computes no confidence,
 * appends no timeline and knows nothing about the rubric. It records the commands it
 * receives and answers with a deterministic result, which is exactly what a caller
 * can observe.
 *
 * <p>Two behaviours of the real port are reproduced because TASK-07 depends on them:
 * a command replayed with the same {@code operationId} returns the originally recorded
 * result with {@code applied = false}, and a different {@code operationId} is a new
 * application.
 */
class FakeHypothesisTransitionPort implements HypothesisTransitionPort {

  final AtomicInteger calls = new AtomicInteger();
  final List<HypothesisTransitionCommand> received = new ArrayList<>();
  final Map<UUID, HypothesisTransitionResult> recordedByOperation =
    new ConcurrentHashMap<>();

  /** When set, the next call throws it — used to prove the caller rolls back. */
  volatile RuntimeException failWith;

  /**
   * The hypothesis the result reports. The frozen command carries no hypothesis id —
   * the engine resolves it from the evidence or prediction reference — so the fake
   * echoes whatever the test expects instead of inventing a lookup it does not own.
   */
  volatile UUID hypothesisId = UUID.randomUUID();

  @Override
  public HypothesisTransitionResult transition(
    HypothesisTransitionCommand command
  ) {
    calls.incrementAndGet();
    received.add(command);
    RuntimeException failure = failWith;
    if (failure != null) throw failure;

    HypothesisTransitionResult already = recordedByOperation.get(
      command.operationId()
    );
    if (already != null) return asReplay(already);

    long previousVersion = command.expectedVersion() == null
      ? 0L
      : command.expectedVersion();
    int confidence = command.verificationOutcome() ==
      VerificationOutcome.REJECTED
      ? 20
      : 42;
    var result = new HypothesisTransitionResult(
      command.operationId(),
      hypothesisId,
      true,
      previousVersion,
      previousVersion + 1,
      HypothesisStatus.OPEN,
      HypothesisStatus.OPEN,
      30,
      confidence,
      ConfidenceBand.MEDIUM,
      "0.1",
      UUID.randomUUID(),
      Instant.now()
    );
    recordedByOperation.put(command.operationId(), result);
    return result;
  }

  /** The last command received, for assertions about what TASK-07 submitted. */
  HypothesisTransitionCommand lastCommand() {
    return received.get(received.size() - 1);
  }

  private static HypothesisTransitionResult asReplay(
    HypothesisTransitionResult result
  ) {
    return new HypothesisTransitionResult(
      result.operationId(),
      result.hypothesisId(),
      false,
      result.previousVersion(),
      result.version(),
      result.previousStatus(),
      result.status(),
      result.previousConfidence(),
      result.confidence(),
      result.confidenceBand(),
      result.rubricVersion(),
      result.eventId(),
      result.occurredAt()
    );
  }
}
