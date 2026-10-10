package com.signalframe.analysis.application.strategies;

/**
 * A candidate causal pattern (STG-05) with the evidence it would require.
 *
 * <p>The support level of a template is always {@code SPECULATIVE} (DS-08): a strategy may never
 * assert that a mechanism is supported. Only the analysis stage may raise the level, and only with
 * fact references. The record field is kept because the contract exposes it, and the canonical
 * constructor rejects every other value, so a strategy cannot emit {@code SUPPORTED}.
 */
public record MechanismTemplate(
  String fromConcept,
  String toConcept,
  String explanationPattern,
  String requiredEvidence,
  String startingSupportLevel
) {
  public static final String SPECULATIVE = "SPECULATIVE";

  public MechanismTemplate {
    fromConcept = SpecRequirements.text(
      fromConcept,
      "MechanismTemplate.fromConcept"
    );
    toConcept = SpecRequirements.text(toConcept, "MechanismTemplate.toConcept");
    explanationPattern = SpecRequirements.text(
      explanationPattern,
      "MechanismTemplate.explanationPattern"
    );
    requiredEvidence = SpecRequirements.text(
      requiredEvidence,
      "MechanismTemplate.requiredEvidence"
    );
    if (
      !SPECULATIVE.equals(startingSupportLevel)
    ) throw new IllegalArgumentException(
      "MechanismTemplate.startingSupportLevel must be " +
        SPECULATIVE +
        " (DS-08), was: " +
        startingSupportLevel
    );
    startingSupportLevel = SPECULATIVE;
  }
}
