package com.signalframe.analysis.application.steps;

/**
 * Ordinal confidence bands from CONFIDENCE_MODEL_V0_1 §4.
 *
 * <p>The band is derived from the final score <em>after</em> caps. Bands are
 * ordinal judgments about how well the snapshot is supported, never probabilities
 * and never comparable across rubric versions.
 */
public enum ConfidenceBand {
  VERY_LOW,
  LOW,
  MEDIUM,
  HIGH,
  VERY_HIGH;

  public static ConfidenceBand of(int score) {
    if (score < 0 || score > 100) throw new IllegalArgumentException(
      "score out of range: " + score
    );
    if (score <= 29) return VERY_LOW;
    if (score <= 49) return LOW;
    if (score <= 69) return MEDIUM;
    if (score <= 84) return HIGH;
    return VERY_HIGH;
  }
}
