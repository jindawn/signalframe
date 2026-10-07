// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record ModelProfile(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank String provider,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank String model,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull String baseUrl,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="^[A-Z][A-Z0-9_]*$") String apiKeyEnv,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("2") Double temperature,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(128) @jakarta.validation.constraints.Max(32000) Integer maxTokens,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer timeout,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean structuredOutput,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean toolCalling,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Boolean enabled
) {}
