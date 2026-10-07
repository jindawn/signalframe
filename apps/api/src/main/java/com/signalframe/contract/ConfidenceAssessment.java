// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record ConfidenceAssessment(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer score,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reason,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.AssertFalse Boolean isProbability
) {}
