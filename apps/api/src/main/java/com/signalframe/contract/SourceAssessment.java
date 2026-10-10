// Generated from contracts/openapi.yaml; do not edit.
package com.signalframe.contract;
import java.util.UUID;
import java.time.Instant;

public record SourceAssessment(
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="PRIMARY_DOCUMENT|OFFICIAL_STATEMENT|COMPANY_DISCLOSURE|PRESS_RELEASE|NEWS_REPORT|WIRE_REPUBLICATION|OPINION_ANALYSIS|SOCIAL_POST|UNKNOWN") String sourceType,
    @jakarta.validation.Valid String publisher,
    @jakarta.validation.Valid Instant publishedAt,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="PRIMARY|SECONDARY|UNKNOWN") String primaryOrSecondary,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="SINGLE_SOURCE|SAME_PUBLISHER_DUPLICATE|INDEPENDENT_SET|UNKNOWN") String independence,
    @jakarta.validation.Valid String independenceKey,
    @jakarta.validation.Valid @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="COMPLETE|PARTIAL|TRUNCATED|METADATA_ONLY|UNKNOWN") String contentCompleteness,
    @jakarta.validation.constraints.NotNull java.util.List<@jakarta.validation.Valid String> notes
) {}
