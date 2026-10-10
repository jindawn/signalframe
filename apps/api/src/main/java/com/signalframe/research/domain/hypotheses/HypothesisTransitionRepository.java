package com.signalframe.research.domain.hypotheses;

import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port behind {@link HypothesisTransitionPort}.
 *
 * <p>It exists so the transition rules stay in domain/application code while the
 * SQL lives in {@code infrastructure/persistence}, as FOUNDATION.md requires
 * ("domain/application code cannot import infrastructure; persistence implements
 * ports"). The methods are deliberately narrow: they express one row lock, one
 * event append and one conditional update, which is exactly the write set a
 * transition needs to commit atomically.
 *
 * <h2>The lock is the concurrency control</h2>
 * {@link #lockById(UUID)} issues {@code SELECT ... FOR UPDATE}, so two concurrent
 * transitions on the same hypothesis serialize: the second observes the version
 * the first committed and reports a conflict instead of overwriting a confidence
 * event. Every read a decision depends on happens after that lock.
 */
public interface HypothesisTransitionRepository {

  /**
   * Locks the hypothesis row for the duration of the transaction and returns its
   * current state. The only way a transition may read the state it is about to
   * change.
   */
  Optional<StoredHypothesis> lockById(UUID hypothesisId);

  /** Unlocked read, for the timeline query. */
  Optional<StoredHypothesis> findById(UUID hypothesisId);

  /**
   * The event a previous application of {@code operationId} appended, if any.
   * The idempotency lookup of freeze §2.2.
   */
  Optional<HypothesisEvent> event(UUID eventId);

  /** The append-only timeline, oldest first. */
  List<HypothesisEvent> timeline(UUID hypothesisId);

  /**
   * The snapshot the hypothesis was produced by, read from
   * {@code analyses.payload} through {@code hypotheses.analysis_id}. Empty when
   * the analysis is missing or its payload is not a readable snapshot — a legacy
   * row, for instance, which reads as CF-06 fail-closed rather than as an error.
   */
  Optional<HypothesisSnapshot> snapshot(UUID hypothesisId);

  /** Every evidence row that bears on the hypothesis. */
  List<StoredEvidence> evidenceFor(UUID hypothesisId);

  /** One evidence row anywhere, for resolving a command's identity. */
  Optional<StoredEvidence> evidence(UUID evidenceId);

  /** One prediction row anywhere, for resolving a command's identity. */
  Optional<StoredPrediction> prediction(UUID predictionId);

  /** Every prediction of the hypothesis. */
  List<StoredPrediction> predictionsFor(UUID hypothesisId);

  /**
   * The {@code created_at} of the newest event, or empty when the hypothesis has
   * none. Used to keep the timeline strictly ordered.
   */
  Optional<Instant> latestEventAt(UUID hypothesisId);

  /**
   * Appends one immutable event. A constraint violation propagates so the
   * enclosing transaction rolls back rather than half-writing a transition.
   */
  void append(HypothesisEvent event);

  /**
   * Writes the new payload, confidence and {@code updatedAt}, bumping
   * {@code version} by exactly one — but only while the stored version still
   * equals {@code expectedVersion}.
   *
   * @return true when the row was updated; false means the version moved under
   *     the caller, which under {@link #lockById(UUID)} indicates a programming
   *     error rather than a race
   */
  boolean update(
    UUID hypothesisId,
    Hypothesis payload,
    int confidence,
    Instant updatedAt,
    long expectedVersion
  );
}
