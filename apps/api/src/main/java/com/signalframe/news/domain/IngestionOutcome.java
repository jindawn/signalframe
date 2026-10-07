package com.signalframe.news.domain;

/**
 * Coarse result of one URL ingestion attempt.
 *
 * <p>The persisted {@code Source.extractionStatus} vocabulary stays {@code
 * PASTED|EXTRACTED|NEEDS_TEXT} (canonical contract). This enum describes the
 * ingestion attempt itself so failures can be distinguished without changing
 * the contract.
 */
public enum IngestionOutcome {
  /** HTTP fetch and text extraction both produced usable content. */
  FETCHED,
  /** Destination was refused for security reasons (private/unsafe or site denied access). */
  BLOCKED,
  /** Destination or payload family is not supported (scheme, content type, size). */
  UNSUPPORTED,
  /** Fetch or extraction failed for an operational reason (timeout, network, too little text). */
  EXTRACTION_FAILED
}
