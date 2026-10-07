package com.signalframe.ai.domain;

import java.util.Optional;

/**
 * Resolves a secret from the local runtime by the environment variable name
 * stored in the model profile. Domain and application code never read the
 * process environment directly, so the resolution strategy stays replaceable
 * and testable.
 */
@FunctionalInterface
public interface ModelCredentialSource {
  /** @return the secret value when configured and non-blank. */
  Optional<String> resolve(String envVariableName);
}
