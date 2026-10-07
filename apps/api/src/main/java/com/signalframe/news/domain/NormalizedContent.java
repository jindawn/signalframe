package com.signalframe.news.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Normalized result of one URL ingestion attempt.
 *
 * <p>This is the ingestion-internal representation: it carries provenance and
 * failure detail that the canonical {@code Source} contract cannot express
 * (the contract only has url/text/extractionStatus/message/createdAt).
 * {@code NewsService} maps it onto the contract vocabulary.
 *
 * <ul>
 *   <li>{@code mainText} is normalized visible text, never raw HTML.
 *   <li>{@code fetchedAt} is null when nothing was fetched; unknown values are
 *       never fabricated.
 *   <li>{@code failure} is null exactly when {@code outcome} is
 *       {@link IngestionOutcome#FETCHED}.
 * </ul>
 */
public record NormalizedContent(
  String title,
  String mainText,
  String originalUrl,
  String canonicalUrl,
  String domain,
  Instant fetchedAt,
  String contentType,
  IngestionOutcome outcome,
  IngestionFailure failure,
  String message,
  Map<String, String> metadata
) {
  public NormalizedContent {
    mainText = mainText == null ? "" : mainText;
    message = message == null ? "" : message;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    outcome = outcome == null ? IngestionOutcome.EXTRACTION_FAILED : outcome;
  }

  public boolean hasText() {
    return !mainText.isBlank();
  }

  /** True only when usable text was extracted from the fetched page. */
  public boolean fetched() {
    return outcome == IngestionOutcome.FETCHED && hasText();
  }

  /** Failure result: no usable text, explicit category, actionable message. */
  public static NormalizedContent failed(
    String originalUrl,
    String domain,
    IngestionFailure failure
  ) {
    return new NormalizedContent(
      null,
      "",
      originalUrl,
      null,
      domain,
      null,
      null,
      failure.outcome(),
      failure,
      failure.persistedMessage(),
      Map.of()
    );
  }
}
