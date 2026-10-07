// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record NewsItem(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String title,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Source source,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull DomainType domain,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant createdAt
) {}
