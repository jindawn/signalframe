// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record Prediction(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID id,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull UUID hypothesisId,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String statement,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="PREDICTION") String type,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull Instant expectedBy,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="OPEN|CONFIRMED|REJECTED|PARTIAL|UNRESOLVED") String status,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=20000) String verificationCriteria,
    @jakarta.validation.Valid String observable,
    @jakarta.validation.Valid String whereToCheck,
     java.util.List<@jakarta.validation.Valid UUID> basisFactRefs
) {}
