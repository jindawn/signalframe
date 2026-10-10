package com.signalframe.analysis.application.steps;

import java.util.List;

/**
 * Deterministic rubric output (CONFIDENCE_MODEL_V0_1 §9 plus CF-06/SCH-09).
 *
 * <p>{@code method} is `RUBRIC` when the score was computed by the rubric and
 * `MODEL_JUDGMENT` when the projection could not be derived and the scorer failed
 * closed (CF-06). {@code advisoryScore} is a model-supplied number kept for
 * diagnostics only: it is never the stored score and no reader may use it.
 */
public record ConfidenceScore(
  int score,
  ConfidenceBand band,
  String rubricVersion,
  List<ConfidenceDimension> dimensions,
  String reason,
  boolean capped,
  ConfidenceMethod method,
  Integer advisoryScore,
  List<ConfidenceInputs.Cap> caps
) {

  /** How a confidence number was obtained. */
  public enum ConfidenceMethod {
    RUBRIC,
    MODEL_JUDGMENT,
  }

  public ConfidenceScore {
    if (method == null) method = ConfidenceMethod.MODEL_JUDGMENT;
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    caps = caps == null ? List.of() : List.copyOf(caps);
  }

  /** CF-06 fail-closed: no rubric dimensions, advisory score only. */
  public static ConfidenceScore failClosed(Integer advisoryScore, String reason) {
    return new ConfidenceScore(
      advisoryScore == null ? 0 : Math.max(0, Math.min(100, advisoryScore)),
      ConfidenceBand.of(advisoryScore == null ? 0 : Math.max(0, Math.min(100, advisoryScore))),
      null,
      List.of(),
      reason,
      false,
      ConfidenceMethod.MODEL_JUDGMENT,
      advisoryScore,
      List.of()
    );
  }

  public boolean isRubric() {
    return method == ConfidenceMethod.RUBRIC;
  }
}
