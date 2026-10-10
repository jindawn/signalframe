package com.signalframe.analysis.application.strategies;

/**
 * An observable check (STG-15, STG-13).
 *
 * <p>Every field is observable: a check names what to look at, where it is published, how often it
 * appears, and what result would support versus contradict a hypothesis. "Keep monitoring the
 * sector" is not a metric and cannot be expressed here. The supporting and contradicting results
 * must be distinguishable in advance, otherwise the entry is not a verification.
 */
public record VerificationMetricTemplate(
  String whatToCheck,
  String whereToCheck,
  String frequency,
  String supportingResult,
  String contradictingResult
) {
  public VerificationMetricTemplate {
    whatToCheck = SpecRequirements.text(
      whatToCheck,
      "VerificationMetricTemplate.whatToCheck"
    );
    whereToCheck = SpecRequirements.text(
      whereToCheck,
      "VerificationMetricTemplate.whereToCheck"
    );
    frequency = SpecRequirements.text(
      frequency,
      "VerificationMetricTemplate.frequency"
    );
    supportingResult = SpecRequirements.text(
      supportingResult,
      "VerificationMetricTemplate.supportingResult"
    );
    contradictingResult = SpecRequirements.text(
      contradictingResult,
      "VerificationMetricTemplate.contradictingResult"
    );
    if (
      supportingResult.equals(contradictingResult)
    ) throw new IllegalArgumentException(
      "VerificationMetricTemplate must state distinguishable supporting and contradicting results (STG-15)"
    );
  }
}
