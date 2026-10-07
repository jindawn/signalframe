// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record SourceRef(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID sourceId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String quote,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Integer startOffset,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) Integer endOffset
) {}
