// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Statement(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull ClaimType type,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String statement,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String reasoning,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer confidence,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid SourceRef> sourceRefs,
     java.util.List<@jakarta.validation.Valid UUID> factRefs,
     java.util.List<@jakarta.validation.Valid UUID> derivedFromRefs,
    @jakarta.validation.Valid UUID targetHypothesisRef,
    @jakarta.validation.Valid UUID rivalsHypothesisRef
) {}
