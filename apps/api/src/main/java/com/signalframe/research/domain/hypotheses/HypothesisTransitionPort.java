package com.signalframe.research.domain.hypotheses;

import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;

/**
 * The frozen cross-module port for hypothesis state change (Wave 2B).
 *
 * <p>This is the only way another module may change a hypothesis. TASK-06 owns the
 * port and its implementation; TASK-07 submits evidence changes and prediction
 * verification outcomes through it and never writes {@code hypotheses.confidence},
 * {@code hypotheses.status} or {@code hypotheses.version} itself.
 *
 * <h2>Why a port and not a repository</h2>
 * A transition is not a row update. It must change the status, recompute the
 * confidence with the deterministic rubric, append an immutable timeline event and
 * bump the optimistic-concurrency version <em>as one transaction</em>. Letting a
 * second module update the table directly would allow a confidence number with no
 * reason, a status change with no event, and a lost update between two concurrent
 * evidence submissions.
 *
 * <h2>Frozen invariants (the implementation must honour them)</h2>
 * <ol>
 *   <li><b>Reason required.</b> Every applied transition records a non-blank
 *       {@code reason} and names what moved; a confidence number may never change
 *       without it (CONFIDENCE_MODEL §10 Phase 2).
 *   <li><b>Deterministic confidence.</b> The new confidence is recomputed by
 *       {@code com.signalframe.shared.confidence.ConfidenceRubric} from the
 *       hypothesis-scope inputs after the referenced evidence/prediction is
 *       recorded. It is never a model number and never an arbitrary delta.
 *   <li><b>Append-only timeline.</b> The result carries the {@code eventId} of the
 *       appended event. Stored events and previous confidence values are never
 *       rewritten.
 *   <li><b>Optimistic concurrency.</b> {@code expectedVersion} must equal the
 *       stored {@code hypotheses.version}, otherwise nothing is written and a
 *       version conflict is reported (HTTP 409 on the API surface). On success the
 *       stored version increases by exactly one.
 *   <li><b>Idempotency.</b> {@code operationId} identifies the attempt. Replaying
 *       the same command returns the recorded result with {@code applied = false}
 *       and creates no second event; the same {@code operationId} with a different
 *       request fingerprint is a conflict, never a silent second application.
 *   <li><b>No automatic truth.</b> A {@code CONFIRMED} prediction never sets the
 *       whole hypothesis to {@code CONFIRMED} by itself. The transition records the
 *       verification outcome; the status is a deterministic function of evidence
 *       deltas and band movement (CONFIDENCE_MODEL §10 Phase 2, EPISTEMIC_TYPES
 *       §2.3: {@code CONFIRMED} requires a verification record, and still never
 *       means "true").
 * </ol>
 *
 * <h2>Transaction boundary</h2>
 * Status change, confidence change, version bump and timeline append commit
 * together or not at all. A caller that sees a thrown failure must observe an
 * unchanged hypothesis, version and timeline.
 *
 * <p>Both parameter and result are generated contract records, so the port, the
 * HTTP payload and the persisted event describe the same immutable vocabulary with
 * no second DTO to drift.
 */
public interface HypothesisTransitionPort {
  /**
   * Applies one transition.
   *
   * @param command the immutable command; {@code operationId}, {@code hypothesisId},
   *                {@code expectedVersion}, {@code cause} and {@code reason} are
   *                required, and {@code evidenceRef} / {@code predictionRef} /
   *                {@code verificationOutcome} carry the cause-specific reference
   * @return the applied (or replayed) transition result
   * @throws com.signalframe.shared.ApplicationException with status {@code 404}
   *     when the hypothesis or the referenced evidence/prediction does not exist,
   *     {@code 409} on a version conflict or an idempotency-key mismatch, and
   *     {@code 422} when the command is well formed but the transition is not
   *     allowed for the current state
   */
  HypothesisTransitionResult transition(HypothesisTransitionCommand command);
}
