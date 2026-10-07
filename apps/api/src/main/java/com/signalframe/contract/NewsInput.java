// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record NewsInput(
    @jakarta.validation.Valid @jakarta.validation.constraints.Size(max=2048) String url,
    @jakarta.validation.Valid @jakarta.validation.constraints.Size(max=100000) String text,
    @jakarta.validation.Valid @jakarta.validation.constraints.Size(max=240) String title
) {}
