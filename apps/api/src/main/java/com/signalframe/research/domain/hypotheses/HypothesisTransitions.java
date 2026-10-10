package com.signalframe.research.domain.hypotheses;

import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.VerificationOutcome;
import java.util.Objects;
import java.util.UUID;

/**
 * Frozen command factories for {@link HypothesisTransitionPort}.
 *
 * <p>TASK-07 (evidence and prediction) uses these instead of assembling the
 * contract record by hand, so the two modules cannot disagree about which
 * references a cause requires. The remaining causes — {@code
 * FALSIFICATION_OBSERVED} and {@code DEADLINE_PASSED} — belong to TASK-06's own
 * transition paths and are built directly from
 * {@link HypothesisTransitionCommand} there.
 *
 * <p>These helpers validate the shape of a command (programmer error), not the
 * state of the hypothesis. Whether the transition is allowed, and whether the
 * references resolve, is decided by the port implementation inside its transaction.
 */
public final class HypothesisTransitions {

  private HypothesisTransitions() {}

  /** A new evidence item now bears on the hypothesis. */
  public static HypothesisTransitionCommand evidenceAdded(
    UUID operationId,
    UUID hypothesisId,
    long expectedVersion,
    UUID evidenceId,
    String reason
  ) {
    return command(
      operationId,
      hypothesisId,
      expectedVersion,
      HypothesisTransitionCause.EVIDENCE_ADDED,
      reason,
      evidenceId,
      null,
      null
    );
  }

  /** An existing evidence item's bearing on the hypothesis changed. */
  public static HypothesisTransitionCommand evidenceChanged(
    UUID operationId,
    UUID hypothesisId,
    long expectedVersion,
    UUID evidenceId,
    String reason
  ) {
    return command(
      operationId,
      hypothesisId,
      expectedVersion,
      HypothesisTransitionCause.EVIDENCE_CHANGED,
      reason,
      evidenceId,
      null,
      null
    );
  }

  /**
   * A prediction's verification outcome was decided against reality.
   *
   * <p>The outcome is an input, not a verdict on the hypothesis: recording
   * {@link VerificationOutcome#CONFIRMED} does not by itself confirm the
   * hypothesis (EPISTEMIC_TYPES §2.3/§2.4).
   */
  public static HypothesisTransitionCommand predictionVerified(
    UUID operationId,
    UUID hypothesisId,
    long expectedVersion,
    UUID predictionId,
    VerificationOutcome outcome,
    String reason
  ) {
    Objects.requireNonNull(outcome, "verification outcome is required");
    return command(
      operationId,
      hypothesisId,
      expectedVersion,
      HypothesisTransitionCause.PREDICTION_VERIFIED,
      reason,
      null,
      predictionId,
      outcome
    );
  }

  private static HypothesisTransitionCommand command(
    UUID operationId,
    UUID hypothesisId,
    long expectedVersion,
    HypothesisTransitionCause cause,
    String reason,
    UUID evidenceRef,
    UUID predictionRef,
    VerificationOutcome outcome
  ) {
    Objects.requireNonNull(operationId, "operationId (idempotency key) is required");
    Objects.requireNonNull(hypothesisId, "hypothesisId is required");
    Objects.requireNonNull(cause, "transition cause is required");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("a transition reason is required");
    }
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
    if (evidenceRef == null && predictionRef == null) {
      throw new IllegalArgumentException(
        "an evidence or prediction reference is required"
      );
    }
    return new HypothesisTransitionCommand(
      operationId,
      expectedVersion,
      cause,
      reason,
      evidenceRef,
      predictionRef,
      outcome
    );
  }
}
