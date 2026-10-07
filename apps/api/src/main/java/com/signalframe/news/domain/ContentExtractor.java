package com.signalframe.news.domain;

/**
 * Best-effort URL ingestion port.
 *
 * <p>Implementations must never fabricate content: when a page cannot be
 * fetched or no usable text can be extracted they return a failed
 * {@link NormalizedContent} with an explicit category so the caller can fall
 * back to pasted text.
 *
 * <p>This port used to expose a smaller {@code Extraction(text, status,
 * message)} record. It now returns the normalized ingestion result so that URL
 * provenance (domain, canonical URL, fetch time, content type) and failure
 * categories are available without inflating the canonical {@code Source}
 * contract.
 */
public interface ContentExtractor {
  NormalizedContent extract(String url);
}
