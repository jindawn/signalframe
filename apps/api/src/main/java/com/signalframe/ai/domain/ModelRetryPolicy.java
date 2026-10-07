package com.signalframe.ai.domain;

import java.time.Duration;

/**
 * Bounds for the two distinct loops around one model invocation.
 *
 * <p>{@code maxAttempts} is the total number of transport attempts for one
 * request, so {@code 1} means "no retry" and {@code 2} means one retry. It only
 * applies to {@link ModelFailure#retryable()} failures.
 * {@code maxRepairAttempts} is the number of extra rounds after a response
 * failed local validation, so {@code 0} disables repair and {@code 1} allows a
 * single re-prompt. Every attempt, retry and repair gets its own audit record.
 */
public record ModelRetryPolicy(
  int maxAttempts,
  int maxRepairAttempts,
  Duration backoff
) {

  public static final ModelRetryPolicy DEFAULT = new ModelRetryPolicy(
    2,
    1,
    Duration.ofMillis(250)
  );

  public ModelRetryPolicy {
    if (maxAttempts < 1) throw new IllegalArgumentException(
      "maxAttempts must be at least 1"
    );
    if (maxRepairAttempts < 0) throw new IllegalArgumentException(
      "maxRepairAttempts must not be negative"
    );
    if (backoff == null || backoff.isNegative()) throw new IllegalArgumentException(
      "backoff must not be negative"
    );
  }
}
