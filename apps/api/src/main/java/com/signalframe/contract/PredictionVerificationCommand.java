// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record PredictionVerificationCommand(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID operationId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull VerificationOutcome result,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reason,
    @jakarta.validation.Valid UUID evidenceRef,
    @jakarta.validation.Valid Instant verifiedAt
) {}
