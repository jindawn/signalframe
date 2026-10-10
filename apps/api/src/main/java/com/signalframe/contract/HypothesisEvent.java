// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record HypothesisEvent(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID hypothesisId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="CREATED|EVIDENCE_ADDED|CONFIDENCE_CHANGED|PREDICTION_VERIFIED|STATUS_CHANGED") String eventType,
    @jakarta.validation.Valid @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer previousConfidence,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer confidence,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reason,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant createdAt,
    @jakarta.validation.Valid HypothesisStatus previousStatus,
    @jakarta.validation.Valid HypothesisStatus status
) {}
