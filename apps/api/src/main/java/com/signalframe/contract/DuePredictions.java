// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record DuePredictions(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant asOf,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Prediction> predictions
) {}
