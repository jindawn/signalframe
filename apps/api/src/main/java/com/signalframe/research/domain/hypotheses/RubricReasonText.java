package com.signalframe.research.domain.hypotheses;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the deterministic dimension breakdown back out of a stored rubric
 * rendering.
 *
 * <p>A transition must name the dimension(s) that moved (CONFIDENCE_MODEL §10
 * Phase 2, freeze §4.1 invariant 1), which requires comparing the new score's
 * dimensions with the ones the hypothesis was last scored at. The frozen
 * {@code Hypothesis} carries no dimension list — only {@code confidence} and the
 * rendered {@code confidenceReason} — so the previous breakdown is read back from
 * that rendering.
 *
 * <p>The grammar is the one {@code DeterministicConfidenceRubric.reason()} emits
 * and is a pure function of the rubric inputs, so a recomputation reproduces it
 * byte for byte (CF-02/CF-03):
 *
 * <pre>{@code RUBRIC 0.1 | method=RUBRIC | score=20 band=VERY_LOW raw=20 capped=false
 *  | D1_SOURCE_QUALITY=1(4/20) | D2_EVIDENCE_DIRECTNESS=2(10/25) | ...}</pre>
 *
 * <p>A rendering that carries no dimension tokens — a legacy payload, or a
 * CF-06 fail-closed {@code MODEL_JUDGMENT} score — yields an empty list, and the
 * transition then names no moved dimension because the score did not come from the
 * rubric. That is the honest answer, not a failure.
 */
public final class RubricReasonText {

  /**
   * One `<id>=<level>(<points>/<weight>)` token. The id vocabulary is the SCH-09
   * {@code ConfidenceDimension} pattern and the level is bounded to 0-5, so a
   * token is a dimension of this rubric profile and nothing else — a stray
   * `D1_OTHER=9(...)` is not read as a dimension.
   */
  private static final Pattern DIMENSION = Pattern.compile(
    "(D\\d_[A-Z_]+)=([0-5])\\((\\d+)/(\\d+)\\)"
  );

  private RubricReasonText() {}

  /** The dimension breakdown of a stored rendering, empty when it has none. */
  public static List<TransitionEventText.Dimension> dimensions(String reason) {
    var out = new ArrayList<TransitionEventText.Dimension>();
    if (reason == null || reason.isBlank()) return out;
    var matcher = DIMENSION.matcher(reason);
    while (matcher.find()) {
      out.add(
        new TransitionEventText.Dimension(
          matcher.group(1),
          Integer.parseInt(matcher.group(2)),
          Integer.parseInt(matcher.group(3))
        )
      );
    }
    return out;
  }

  /**
   * The rendering's declared method, or {@code null} when it declares none.
   * {@code MODEL_JUDGMENT} is the CF-06 fail-closed reading.
   */
  public static String method(String reason) {
    if (reason == null) return null;
    if (reason.contains("method=RUBRIC")) return "RUBRIC";
    if (reason.contains("method=MODEL_JUDGMENT")) return "MODEL_JUDGMENT";
    return null;
  }
}
