package com.signalframe.ai.domain;

/**
 * Vendor-neutral entry point for every model invocation.
 *
 * <p>Business code depends only on this port and on {@code ModelPurpose}. No
 * provider, model identifier, endpoint or credential appears in this signature:
 * the implementation selects a provider adapter from configuration. Failures
 * are reported as {@link ModelInvocationException} carrying a
 * {@link ModelFailure} code, never a vendor exception type.
 */
public interface ModelGateway {
  /**
   * Performs one model call. Implementations must not retry internally; bounded
   * retry and audit belong to the caller so that every attempt is recorded.
   */
  ModelResponse call(ModelRequest request);
}
