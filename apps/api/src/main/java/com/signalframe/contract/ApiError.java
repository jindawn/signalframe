// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record ApiError(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String code,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String message,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String requestId
) {}
