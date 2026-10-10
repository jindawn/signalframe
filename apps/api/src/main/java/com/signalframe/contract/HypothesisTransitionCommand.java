// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record HypothesisTransitionCommand(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID operationId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull HypothesisTransitionCause cause,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reason,
    @jakarta.validation.Valid UUID evidenceRef,
    @jakarta.validation.Valid UUID predictionRef,
    @jakarta.validation.Valid VerificationOutcome verificationOutcome
) {}
