// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record HypothesisTransitionResult(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID operationId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID hypothesisId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean applied,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long previousVersion,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long version,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull HypothesisStatus previousStatus,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull HypothesisStatus status,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer previousConfidence,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer confidence,
    @jakarta.validation.Valid ConfidenceBand confidenceBand,
    @jakarta.validation.Valid String rubricVersion,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID eventId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant occurredAt
) {}
