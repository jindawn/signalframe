// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record NamedModelProfile(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String name,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull ModelProfile profile,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="MOCK|LIVE|DISABLED") String effectiveMode
) {}
