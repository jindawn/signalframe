// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Provenance(
    @jakarta.validation.Valid String protocolVersion,
    @jakarta.validation.Valid String domainStrategyId,
    @jakarta.validation.Valid String rubricVersion,
     java.util.List<@jakarta.validation.Valid String> promptVersions,
     java.util.List<@jakarta.validation.Valid UUID> modelRunIds
) {}
