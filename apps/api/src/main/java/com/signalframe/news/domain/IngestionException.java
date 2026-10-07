package com.signalframe.news.domain;

/**
 * Internal ingestion failure. Carries only a stable category; the message is
 * the failure code, never exception detail, a full URL or credentials.
 */
public class IngestionException extends RuntimeException {

  private final IngestionFailure failure;

  public IngestionException(IngestionFailure failure) {
    this(failure, null);
  }

  public IngestionException(IngestionFailure failure, Throwable cause) {
    super(failure.code(), cause);
    this.failure = failure;
  }

  public IngestionFailure failure() {
    return failure;
  }
}
