// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Analysis(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID newsId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID jobId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull DomainType domain,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull NewsValueScore score,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull AnalysisResult result,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant createdAt
) {}
