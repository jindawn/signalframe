// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record HypothesisDetail(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Hypothesis hypothesis,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid HypothesisEvent> timeline,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Evidence> evidence,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid Prediction> predictions
) {}
