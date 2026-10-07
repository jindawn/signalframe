// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record JobEvent(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Long sequence,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull JobStatus status,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String step,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String message,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant at
) {}
