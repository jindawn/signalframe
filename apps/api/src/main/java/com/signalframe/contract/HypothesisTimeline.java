// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record HypothesisTimeline(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID hypothesisId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long version,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull HypothesisStatus status,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer confidence,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid HypothesisEvent> items
) {}
