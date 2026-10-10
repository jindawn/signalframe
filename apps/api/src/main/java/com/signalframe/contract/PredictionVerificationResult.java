// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record PredictionVerificationResult(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Prediction prediction,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean applied,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID verificationId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull HypothesisTransitionResult hypothesisTransition
) {}
