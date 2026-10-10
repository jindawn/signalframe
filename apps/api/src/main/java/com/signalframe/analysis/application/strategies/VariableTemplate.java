package com.signalframe.analysis.application.strategies;

/**
 * A recommended variable to look for (STG-04).
 *
 * <p>It is a recommendation, not a claim: it carries no direction, no magnitude and no prior
 * state. {@code directionRule} says how a direction would be decided from evidence; the direction
 * itself is an evidence-derived output of the analysis stage and starts as UNKNOWN.
 */
public record VariableTemplate(
  String name,
  String tracks,
  String whyItMatters,
  String directionRule
) {
  public VariableTemplate {
    name = SpecRequirements.text(name, "VariableTemplate.name");
    tracks = SpecRequirements.text(tracks, "VariableTemplate.tracks");
    whyItMatters = SpecRequirements.text(
      whyItMatters,
      "VariableTemplate.whyItMatters"
    );
    directionRule = SpecRequirements.text(
      directionRule,
      "VariableTemplate.directionRule"
    );
  }
}
