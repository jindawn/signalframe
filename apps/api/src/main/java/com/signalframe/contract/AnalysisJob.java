// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record AnalysisJob(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID newsId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull JobStatus status,
    @jakarta.validation.Valid UUID analysisId,
    @jakarta.validation.Valid String error,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String correlationId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant createdAt,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant updatedAt,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid JobEvent> events
) {}
