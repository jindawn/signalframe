package com.signalframe.analysis.application.steps;

/**
 * Deterministic confidence scoring (CONFIDENCE_MODEL_V0_1 §9).
 *
 * <p>Pure and framework-free: {@code score} is a function of
 * {@link ConfidenceInputs} and the rubric version only. It has no clock, no
 * network, no model and no randomness (CF-02/CF-04), so Gate D can recompute a
 * stored score and fail on any mismatch (CF-03/CF-08).
 */
public interface ConfidenceRubric {
  /** Frozen rubric version; any weight/level/cap change increments it (CF-07). */
  String VERSION = "0.1";

  ConfidenceScore score(ConfidenceInputs inputs);

  /**
   * Gate D recomputation: the stored score, band, dimensions and points must be
   * reproduced exactly from the snapshot projection.
   */
  default boolean matchesStored(ConfidenceScore stored, ConfidenceInputs inputs) {
    if (stored == null || !stored.isRubric()) return false;
    ConfidenceScore recomputed = score(inputs);
    if (!recomputed.isRubric()) return false;
    if (recomputed.score() != stored.score()) return false;
    if (recomputed.band() != stored.band()) return false;
    if (!VERSION.equals(stored.rubricVersion())) return false;
    if (recomputed.dimensions().size() != stored.dimensions().size()) return false;
    for (int i = 0; i < recomputed.dimensions().size(); i++) {
      var a = recomputed.dimensions().get(i);
      var b = stored.dimensions().get(i);
      if (
        !a.id().equals(b.id()) ||
        a.level() != b.level() ||
        a.points() != b.points() ||
        a.weight() != b.weight()
      ) return false;
    }
    return true;
  }

  /** Canonical rubric implementation. */
  static ConfidenceRubric deterministic() {
    return new DeterministicConfidenceRubric();
  }
}
