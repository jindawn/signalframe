// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record ConfidenceDimension(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="D1_SOURCE_QUALITY|D2_EVIDENCE_DIRECTNESS|D3_INDEPENDENT_CORROBORATION|D4_MECHANISM_SUPPORT|D5_COUNTER_EVIDENCE_RESILIENCE") String dimension,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(5) Integer level,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer points,
    @jakarta.validation.Valid String note
) {}
