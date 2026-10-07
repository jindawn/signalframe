// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Source(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid String url,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String text,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="PASTED|EXTRACTED|NEEDS_TEXT") String extractionStatus,
    @jakarta.validation.Valid String message,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant createdAt
) {}
