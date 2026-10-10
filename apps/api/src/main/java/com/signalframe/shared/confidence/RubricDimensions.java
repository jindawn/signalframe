package com.signalframe.shared.confidence;

import com.signalframe.contract.ConfidenceDimension;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dimension metadata for the rubric profiles (CONFIDENCE_MODEL_V0_1 §3 and §8).
 *
 * <p>{@link ConfidenceDimension} is the generated contract record (SCH-09); it
 * carries {@code dimension, level, points, note}, not the weight, because the
 * weight is a property of the rubric profile rather than of a stored snapshot.
 * Keeping the weights here means a stored dimension row stays readable even if a
 * future rubric version changes them — historical snapshots keep their own
 * {@code rubricVersion} and points.
 *
 * <p>Points are exact integers because every weight is divisible by 5, so Gate D
 * can recompute {@code points} and fail on any mismatch (CF-03/CF-08).
 */
public final class RubricDimensions {

  /**
   * One weighted dimension of a profile.
   *
   * @param id     canonical contract dimension id (SCH-09 vocabulary)
   * @param name   human-readable name used in the rendered reason only
   * @param weight points carried at level 5
   */
  public record Spec(String id, String name, int weight) {
    /** Builds the contract dimension row for a level; points are exact. */
    public ConfidenceDimension at(int level, String note) {
      if (level < 0 || level > 5) throw new IllegalArgumentException(
        "level out of range: " + level
      );
      return new ConfidenceDimension(id, level, weight * level / 5, note);
    }
  }

  public static final Spec D1 = new Spec(
    "D1_SOURCE_QUALITY",
    "Source Quality",
    20
  );
  public static final Spec D2 = new Spec(
    "D2_EVIDENCE_DIRECTNESS",
    "Evidence Directness",
    25
  );
  public static final Spec D3 = new Spec(
    "D3_INDEPENDENT_CORROBORATION",
    "Independent Corroboration",
    20
  );
  public static final Spec D4 = new Spec(
    "D4_MECHANISM_SUPPORT",
    "Mechanism Support",
    20
  );
  public static final Spec D5 = new Spec(
    "D5_COUNTER_EVIDENCE_RESILIENCE",
    "Counter-evidence Resilience",
    15
  );

  /**
   * The normative snapshot profile, in contract dimension order. Item profiles
   * from §8 are not implemented in Wave 2; they reuse these level definitions.
   */
  public static final java.util.List<Spec> SNAPSHOT = java.util.List.of(
    D1,
    D2,
    D3,
    D4,
    D5
  );

  private static final Map<String, Spec> BY_ID = index();

  private static Map<String, Spec> index() {
    var map = new LinkedHashMap<String, Spec>();
    for (var spec : SNAPSHOT) map.put(spec.id(), spec);
    return Map.copyOf(map);
  }

  /** Weight of a stored dimension id, or 0 for a dimension outside the profile. */
  public static int weightOf(String id) {
    var spec = BY_ID.get(id);
    return spec == null ? 0 : spec.weight();
  }

  /** Name of a stored dimension id, or the id itself when unknown. */
  public static String nameOf(String id) {
    var spec = BY_ID.get(id);
    return spec == null ? id : spec.name();
  }

  private RubricDimensions() {}
}
