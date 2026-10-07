// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record NewsValueScore(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull NewsGrade grade,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer importance,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer relevance,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer novelty,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer variableImpact,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer verifiability,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reason
) {}
