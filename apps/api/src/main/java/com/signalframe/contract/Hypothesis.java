// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Hypothesis(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String title,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String description,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull HypothesisStatus status,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String confidenceReason,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant createdAt,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant updatedAt,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull ClaimType type,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String statement,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reasoning,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer confidence,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid SourceRef> sourceRefs,
     java.util.List<@jakarta.validation.Valid UUID> supportingFactRefs,
     java.util.List<@jakarta.validation.Valid UUID> supportingEvidenceRefs,
     java.util.List<@jakarta.validation.Valid UUID> contradictingEvidenceRefs,
     java.util.List<@jakarta.validation.Valid String> assumptions,
     java.util.List<@jakarta.validation.Valid UUID> alternativeHypothesisRefs,
     java.util.List<@jakarta.validation.Valid Statement> falsificationConditions,
    @jakarta.validation.Valid ConfidenceBand confidenceBand
) {}
