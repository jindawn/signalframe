package com.signalframe.research.domain.evidence;

import com.signalframe.contract.Prediction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for dated predictions (TASK-07).
 *
 * <p>Prediction text is immutable after creation. {@link #resolve} changes only the
 * status and the verification timestamp; every text field of the stored
 * {@link Prediction} is carried across verbatim, so an outcome is recorded rather
 * than a judgement rewritten (STG-14.3, PR-17).
 */
public interface PredictionRepository {
  /** Inserts one prediction, necessarily {@code OPEN}, and returns it as persisted. */
  Prediction save(Prediction prediction);

  /** The prediction with this id, when it exists. */
  Optional<Prediction> findPrediction(UUID predictionId);

  /** Every prediction attached to a hypothesis, earliest deadline first. */
  List<Prediction> predictionsFor(UUID hypothesisId);

  /**
   * The predictions that are still {@code OPEN} and whose deadline has passed.
   *
   * <p>Read-only by construction: a passed deadline is reported, never turned into a
   * failure automatically (§6, freeze §6).
   */
  List<Prediction> dueOpen(Instant asOf, int limit);

  /**
   * Records a verification outcome on an {@code OPEN} prediction.
   *
   * @return the resolved prediction, or empty when it was not {@code OPEN} (already
   *     decided, or decided concurrently)
   */
  Optional<Prediction> resolve(
    UUID predictionId,
    String status,
    Instant verifiedAt
  );
}
