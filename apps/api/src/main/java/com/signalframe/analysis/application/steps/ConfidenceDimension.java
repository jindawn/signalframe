package com.signalframe.analysis.application.steps;

/**
 * One rubric dimension's level, points and derivation note (CONFIDENCE_MODEL §5).
 *
 * <p>Points are exact integers because every weight is divisible by 5, so Gate D
 * can recompute `points` and fail on any mismatch (CF-03/CF-08).
 */
public record ConfidenceDimension(
  String id,
  String name,
  int weight,
  int level,
  int points,
  String note
) {
  public ConfidenceDimension {
    if (level < 0 || level > 5) throw new IllegalArgumentException(
      "level out of range: " + level
    );
    if (points != weight * level / 5) throw new IllegalArgumentException(
      "points must equal weight*level/5"
    );
  }
}
