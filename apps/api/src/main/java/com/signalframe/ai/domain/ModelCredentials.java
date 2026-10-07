package com.signalframe.ai.domain;

/**
 * Short-lived credential handle passed to a provider adapter for one call.
 *
 * <p>The value is never persisted, never added to an audit record and never
 * printed: {@link #toString()} is redacted so accidental logging is harmless.
 * The audit table keeps only the name of the environment variable, never the
 * value.
 */
public record ModelCredentials(String value) {

  public ModelCredentials {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(
      "Credential value is required"
    );
  }

  /** Redacted rendering; safe for logs and error messages. */
  @Override
  public String toString() {
    return "ModelCredentials[REDACTED]";
  }
}
