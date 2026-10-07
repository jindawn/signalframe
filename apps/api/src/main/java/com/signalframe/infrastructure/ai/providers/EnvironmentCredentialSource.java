package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.ModelCredentialSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves secrets from the process environment by variable name.
 *
 * <p>The variable name comes from the model profile; the value is read on
 * demand, handed to the adapter for one call and never stored, logged or
 * audited. Values are not read from application.yml so that the durable
 * configuration file can never contain a real key.
 */
@Component
public class EnvironmentCredentialSource implements ModelCredentialSource {

  @Override
  public Optional<String> resolve(String envVariableName) {
    if (envVariableName == null || envVariableName.isBlank()) return Optional.empty();
    return Optional
      .ofNullable(System.getenv(envVariableName))
      .filter(value -> !value.isBlank());
  }
}
