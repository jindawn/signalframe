// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record ModelRun(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID jobId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String provider,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String model,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull ModelPurpose purpose,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String promptVersion,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant startedAt,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant completedAt,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Long latencyMs,
    @jakarta.validation.Valid Long inputTokens,
    @jakarta.validation.Valid Long outputTokens,
    @jakarta.validation.Valid Long totalTokens,
    @jakarta.validation.Valid Double estimatedCost,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="SUCCEEDED|FAILED") String status,
    @jakarta.validation.Valid String errorType,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String correlationId
) {}
