package com.signalframe.shared.confidence;

import com.signalframe.contract.ConfidenceDimension;
import com.signalframe.contract.ConfidenceMethod;
import com.signalframe.shared.confidence.ConfidenceInputs.Cap;
import java.util.ArrayList;
import java.util.List;

/**
 * The CONFIDENCE_MODEL_V0_1 rubric: five weighted dimensions, integer points,
 * a single minimum over binding caps, then the band.
 *
 * <p>Arithmetic is exactly `points = weight × level / 5` (exact because every
 * weight is divisible by 5), `raw = Σ points`, `score = min(raw, min(caps))`,
 * `band = bandOf(score)`. Caps never raise a score and the order of cap
 * evaluation cannot change the result (CF-05).
 *
 * <p>Deliberately not a Spring bean: the rubric is pure, and the container wiring
 * lives in the module that consumes it.
 */
public final class DeterministicConfidenceRubric implements ConfidenceRubric {

  @Override
  public ConfidenceScore score(ConfidenceInputs inputs) {
    if (inputs == null || inputs.source() == null) return ConfidenceScore.failClosed(
      null,
      "RUBRIC " +
      VERSION +
      " | method=MODEL_JUDGMENT | the source assessment is missing, so no dimension can be derived (CF-06)."
    );
    var dimensions = List.of(
      RubricDimensions.D1.at(
        inputs.levelD1(),
        "input type, publisher, date and completeness"
      ),
      RubricDimensions.D2.at(
        inputs.levelD2(),
        "how directly the facts carry the claim"
      ),
      RubricDimensions.D3.at(
        inputs.levelD3(),
        "independent confirmation of the core fact"
      ),
      RubricDimensions.D4.at(
        inputs.levelD4(),
        "documented mechanism of the required support level"
      ),
      RubricDimensions.D5.at(
        inputs.levelD5(),
        "objections and contradicting evidence handled"
      )
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
    var band = ConfidenceBands.of(score);
    boolean capped = score < raw;
    return new ConfidenceScore(
      score,
      band,
      VERSION,
      dimensions,
      reason(score, band, raw, dimensions, caps, capped),
      capped,
      ConfidenceMethod.RUBRIC,
      null,
      caps
    );
  }

  /**
   * Deterministic rendering of the rubric state. Because SCH-09 gives the snapshot
   * structured {@code band}, {@code method}, {@code rubricVersion} and
   * {@code dimensions} fields, this text is human audit copy only: it is a pure
   * function of the rubric inputs, so a recomputation reproduces it, but no reader
   * is required to parse it.
   */
  private static String reason(
    int score,
    com.signalframe.contract.ConfidenceBand band,
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
      d.dimension() +
      "=" +
      d.level() +
      "(" +
      d.points() +
      "/" +
      RubricDimensions.weightOf(d.dimension()) +
      ")"
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
