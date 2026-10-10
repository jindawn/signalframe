package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ConfidenceInputs.Cap;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The CONFIDENCE_MODEL_V0_1 rubric: five weighted dimensions, integer points,
 * a single minimum over binding caps, then the band.
 *
 * <p>Arithmetic is exactly `points = weight × level / 5` (exact because every
 * weight is divisible by 5), `raw = Σ points`, `score = min(raw, min(caps))`,
 * `band = bandOf(score)`. Caps never raise a score and the order of cap
 * evaluation cannot change the result (CF-05).
 */
@Component
public final class DeterministicConfidenceRubric implements ConfidenceRubric {

  @Override
  public ConfidenceScore score(ConfidenceInputs inputs) {
    if (inputs == null || inputs.source() == null) return ConfidenceScore.failClosed(
      null,
      "RUBRIC " +
      VERSION +
      " | method=MODEL_JUDGMENT | the source assessment is missing, so no dimension can be derived (CF-06)."
    );
    int d1 = inputs.levelD1();
    int d2 = inputs.levelD2();
    int d3 = inputs.levelD3();
    int d4 = inputs.levelD4();
    int d5 = inputs.levelD5();
    var dimensions = List.of(
      dimension("D1", "Source Quality", 20, d1, "input type, publisher, date and completeness"),
      dimension("D2", "Evidence Directness", 25, d2, "how directly the facts carry the claim"),
      dimension("D3", "Independent Corroboration", 20, d3, "independent confirmation of the core fact"),
      dimension("D4", "Mechanism Support", 20, d4, "documented mechanism of the required support level"),
      dimension("D5", "Counter-evidence Resilience", 15, d5, "objections and contradicting evidence handled")
    );
    int raw = dimensions
      .stream()
      .mapToInt(ConfidenceDimension::points)
      .sum();
    List<Cap> caps = inputs.caps();
    int ceiling = caps.isEmpty()
      ? 100
      : caps
          .stream()
          .mapToInt(Cap::ceiling)
          .min()
          .orElse(100);
    int score = Math.min(raw, ceiling);
    var band = ConfidenceBand.of(score);
    boolean capped = score < raw;
    return new ConfidenceScore(
      score,
      band,
      VERSION,
      dimensions,
      reason(score, band, raw, dimensions, caps, capped),
      capped,
      ConfidenceScore.ConfidenceMethod.RUBRIC,
      null,
      caps
    );
  }

  private static ConfidenceDimension dimension(
    String id,
    String name,
    int weight,
    int level,
    String note
  ) {
    return new ConfidenceDimension(
      id,
      name,
      weight,
      level,
      weight * level / 5,
      note
    );
  }

  /**
   * Deterministic rendering of the rubric state. It is stored in the snapshot's
   * free-text reason field because the v0.1 contract has no dimension fields yet;
   * it is a pure function of the rubric inputs, so a recomputation reproduces it.
   */
  private static String reason(
    int score,
    ConfidenceBand band,
    int raw,
    List<ConfidenceDimension> dimensions,
    List<Cap> caps,
    boolean capped
  ) {
    var parts = new ArrayList<String>();
    parts.add("RUBRIC " + VERSION);
    parts.add("method=RUBRIC");
    parts.add(
      "score=" + score + " band=" + band + " raw=" + raw + " capped=" + capped
    );
    for (var d : dimensions) parts.add(
      d.id() + "=" + d.level() + "(" + d.points() + "/" + d.weight() + ")"
    );
    parts.add(
      "caps=" +
      (caps.isEmpty()
          ? "none"
          : String.join(
            ",",
            caps.stream().map(c -> c.id() + "=" + c.ceiling()).toList()
          ))
    );
    parts.add("Confidence Score is not a probability of truth.");
    return String.join(" | ", parts);
  }
}
