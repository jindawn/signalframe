package com.signalframe.ai.domain;

import java.util.Set;

/**
 * SPI implemented by every concrete provider integration.
 *
 * <p>Adapters are discovered from the Spring context and selected by the
 * provider identifier configured in the model profile. Nothing above this
 * boundary knows a vendor name: routing, retry and audit only use the values
 * declared here.
 */
public interface ModelProviderAdapter {
  /** Configured provider identifier, for example the value used in a profile. */
  String provider();

  /** Capabilities this adapter can honour. Unknown capabilities are rejected. */
  default Set<ModelCapability> capabilities() {
    return Set.of();
  }

  /**
   * Whether a secret must be configured before this adapter can be used.
   * Adapters that answer without credentials can serve as the offline
   * fallback when no key is present.
   */
  default boolean requiresCredentials() {
    return true;
  }

  /**
   * Performs one model invocation.
   *
   * @param credentials resolved secret, or null when none is configured
   * @throws ModelInvocationException with a vendor-neutral failure code
   */
  ModelResponse call(ModelRequest request, ModelCredentials credentials);
}
