package com.signalframe.shared.confidence;

import com.signalframe.contract.ConfidenceBand;

/**
 * Band boundaries of the confidence model (CONFIDENCE_MODEL_V0_1 §4).
 *
 * <p>{@link ConfidenceBand} itself is the generated contract vocabulary (SCH-06);
 * this class only supplies the deterministic score-to-band function, so the enum
 * is never duplicated outside {@code com.signalframe.contract}.
 */
public final class ConfidenceBands {

  private ConfidenceBands() {}

  /**
   * Bands are ordinal judgments about how well a snapshot is supported, derived
   * from the final score <em>after</em> caps. They are never probabilities and are
   * not comparable across rubric versions.
   */
  public static ConfidenceBand of(int score) {
    if (score < 0 || score > 100) throw new IllegalArgumentException(
      "score out of range: " + score
    );
    if (score <= 29) return ConfidenceBand.VERY_LOW;
    if (score <= 49) return ConfidenceBand.LOW;
    if (score <= 69) return ConfidenceBand.MEDIUM;
    if (score <= 84) return ConfidenceBand.HIGH;
    return ConfidenceBand.VERY_HIGH;
  }
}
