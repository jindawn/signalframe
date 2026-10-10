// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record EvidenceCreateRequest(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID sourceId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="SUPPORTS|CONTRADICTS|NEUTRAL") String stance,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer strength,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reason,
     java.util.List<@jakarta.validation.Valid UUID> factRefs,
    @jakarta.validation.Valid UUID analysisId
) {}
