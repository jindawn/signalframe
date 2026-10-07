// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Topic(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String name,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid UUID> newsIds,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid UUID> hypothesisIds
) {}
