package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Selects a registered provider adapter for the profile chosen by
 * {@link com.signalframe.ai.application.ModelRouter}.
 *
 * <p>Routing is driven entirely by the adapters present in the Spring context,
 * so adding a provider means adding an adapter: no routing code changes and no
 * vendor name appears above this package. When the selected profile needs a
 * credential that is not configured, the credential-free adapter is used
 * instead so the pipeline keeps working offline.
 */
@Component
@Primary
public class RoutingModelGateway implements ModelGateway {

  private final ModelCredentialSource credentials;
  private final Map<String, ModelProviderAdapter> adapters;
  private final ModelProviderAdapter fallback;

  @Autowired
  public RoutingModelGateway(
    List<ModelProviderAdapter> adapters,
    ModelCredentialSource credentials
  ) {
    this.credentials = credentials;
    this.adapters = index(adapters);
    this.fallback = adapters
      .stream()
      .filter(adapter -> !adapter.requiresCredentials())
      .findFirst()
      .orElseThrow(() ->
        new IllegalStateException(
          "No credential-free provider adapter is registered"
        )
      );
  }

  @Override
  public ModelResponse call(ModelRequest request) {
    var profile = request.profile();
    var adapter = adapter(profile.provider());
    requireCapabilities(adapter, profile);
    if (!adapter.requiresCredentials()) return adapter.call(request, null);
    var secret = credentials
      .resolve(profile.apiKeyEnv())
      .filter(value -> !value.isBlank());
    if (secret.isEmpty()) return fallback.call(request, null);
    return adapter.call(request, new ModelCredentials(secret.get()));
  }

  /** Provider identifiers reachable in this runtime, sorted. */
  public List<String> providers() {
    return adapters.keySet().stream().sorted().toList();
  }

  private ModelProviderAdapter adapter(String provider) {
    var adapter = adapters.get(provider);
    if (adapter == null) throw ModelInvocationException.of(
      ModelFailure.CONFIGURATION,
      "No adapter registered for configured provider"
    );
    return adapter;
  }

  private static void requireCapabilities(
    ModelProviderAdapter adapter,
    ModelProfile profile
  ) {
    require(
      adapter,
      ModelCapability.STRUCTURED_OUTPUT,
      profile.structuredOutput(),
      "structured output"
    );
    require(
      adapter,
      ModelCapability.TOOL_CALLING,
      profile.toolCalling(),
      "tool calling"
    );
  }

  private static void require(
    ModelProviderAdapter adapter,
    ModelCapability capability,
    boolean requested,
    String label
  ) {
    if (requested && !adapter.capabilities().contains(capability)) throw ModelInvocationException.of(
      ModelFailure.CAPABILITY,
      "Selected profile requires " +
      label +
      " but the adapter does not provide it"
    );
  }

  private static Map<String, ModelProviderAdapter> index(
    List<ModelProviderAdapter> adapters
  ) {
    var indexed = new LinkedHashMap<String, ModelProviderAdapter>();
    for (var adapter : adapters) {
      var duplicate = indexed.put(adapter.provider(), adapter);
      if (duplicate != null) throw new IllegalStateException(
        "Duplicate provider adapter registered"
      );
    }
    if (indexed.isEmpty()) throw new IllegalStateException(
      "No provider adapter is registered"
    );
    return Map.copyOf(indexed);
  }
}
