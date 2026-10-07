// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record AnalysisResult(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String summary,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Fact> facts,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Variable> variables,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid CausalLink> mechanisms,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid StakeholderImpact> stakeholders,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> firstOrderEffects,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> secondOrderEffects,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Hypothesis> hypotheses,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> alternativeExplanations,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> counterArguments,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> falsificationConditions,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> corroboratingSignals,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Indicator> verificationIndicators,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> unknowns,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull ConfidenceAssessment confidenceAssessment,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Statement> upcomingObservations,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean modifiesExistingHypotheses,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean demo
) {}
