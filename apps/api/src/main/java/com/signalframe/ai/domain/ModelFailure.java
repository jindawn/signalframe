package com.signalframe.ai.domain;

/**
 * Vendor-neutral classification of a single model invocation failure.
 *
 * <p>Provider adapters translate transport- and vendor-specific exceptions into
 * this taxonomy so that retry and reporting policy never inspects provider or
 * model names. {@link #retryable()} is the only signal the retry loop uses.
 */
public enum ModelFailure {
  /** The provider did not answer inside the configured timeout. */
  TIMEOUT(true),
  /** The provider throttled the caller (HTTP 429 or equivalent). */
  RATE_LIMITED(true),
  /** The provider answered with a transient server-side failure (HTTP 5xx). */
  PROVIDER_UNAVAILABLE(true),
  /** Connection, DNS, TLS or other transport-level failure. */
  TRANSPORT(true),
  /** Credentials are missing, rejected or expired. */
  AUTHENTICATION(false),
  /** Credentials are valid but not allowed to use the model. */
  PERMISSION_DENIED(false),
  /** The provider rejected the request as malformed. */
  INVALID_REQUEST(false),
  /**
   * The credential is valid but its account has no remaining quota or balance
   * (HTTP 402 or equivalent). Retrying cannot succeed: the operator has to fix
   * billing or quota.
   */
  QUOTA_EXCEEDED(false),
  /** The configured model identifier does not exist at the provider. */
  MODEL_NOT_FOUND(false),
  /** The selected profile requires a capability the adapter does not offer. */
  CAPABILITY(false),
  /** The provider answered with a body that could not be decoded. */
  PROVIDER_RESPONSE_INVALID(false),
  /** The response arrived but failed local schema or provenance validation. */
  OUTPUT_INVALID(false),
  /** The runtime or profile configuration is unusable. */
  CONFIGURATION(false),
  /** Failure could not be classified; treated as non-retryable. */
  UNKNOWN(false);

  private final boolean retryable;

  ModelFailure(boolean retryable) {
    this.retryable = retryable;
  }

  /** Whether a bounded retry of the same request may succeed. */
  public boolean retryable() {
    return retryable;
  }

  /** Stable identifier persisted in the audit record. */
  public String code() {
    return name();
  }
}
