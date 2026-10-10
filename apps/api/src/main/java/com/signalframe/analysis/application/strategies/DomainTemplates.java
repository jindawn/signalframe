package com.signalframe.analysis.application.strategies;

/**
 * Short constructors for the dictionary constants.
 *
 * <p>{@code mechanism} deliberately exposes no support-level parameter: every candidate pattern is
 * built at {@code SPECULATIVE}, so no dictionary can declare a supported mechanism (DS-08).
 */
final class DomainTemplates {

  private DomainTemplates() {}

  static VariableTemplate variable(
    String name,
    String tracks,
    String whyItMatters,
    String directionRule
  ) {
    return new VariableTemplate(name, tracks, whyItMatters, directionRule);
  }

  static MechanismTemplate mechanism(
    String fromConcept,
    String toConcept,
    String explanationPattern,
    String requiredEvidence
  ) {
    return new MechanismTemplate(
      fromConcept,
      toConcept,
      explanationPattern,
      requiredEvidence,
      MechanismTemplate.SPECULATIVE
    );
  }

  static VerificationMetricTemplate metric(
    String whatToCheck,
    String whereToCheck,
    String frequency,
    String supportingResult,
    String contradictingResult
  ) {
    return new VerificationMetricTemplate(
      whatToCheck,
      whereToCheck,
      frequency,
      supportingResult,
      contradictingResult
    );
  }
}
