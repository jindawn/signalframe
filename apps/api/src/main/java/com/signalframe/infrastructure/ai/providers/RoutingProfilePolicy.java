package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import com.signalframe.shared.ApplicationException;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Provider capability and activation policy.
 *
 * <p>The set of usable providers is derived from the registered adapters, so a
 * new adapter is immediately configurable. {@link #effectiveMode} reports
 * whether a profile will really reach a remote provider, which is what the
 * settings API exposes; the credential value itself is never returned.
 */
@Component
public class RoutingProfilePolicy implements ModelProfilePolicy {

  private static final String MODE_MOCK = "MOCK";
  private static final String MODE_LIVE = "LIVE";
  private static final String MODE_DISABLED = "DISABLED";

  /**
   * Provider identifiers reachable without a Spring context. Used when the
   * policy is constructed directly, for example by a focused unit test.
   */
  private static final Map<String, Boolean> BUILT_IN = Map.of(
    "mock",
    false,
    "openai-compatible",
    true
  );

  /** Provider identifier to whether it needs a configured credential. */
  private final Map<String, Boolean> providers;
  private final ModelCredentialSource credentials;

  public RoutingProfilePolicy() {
    this(new EnvironmentCredentialSource(), BUILT_IN);
  }

  @Autowired
  public RoutingProfilePolicy(
    ModelCredentialSource credentials,
    List<ModelProviderAdapter> adapters
  ) {
    this(credentials, index(adapters));
  }

  RoutingProfilePolicy(
    ModelCredentialSource credentials,
    Map<String, Boolean> providers
  ) {
    this.credentials = credentials;
    this.providers = Map.copyOf(providers);
  }

  @Override
  public void validate(ModelProfile profile) {
    if (!providers.containsKey(profile.provider())) throw new ApplicationException(
      400,
      "UNSUPPORTED_PROVIDER",
      "Available providers: " + String.join(", ", sorted(providers.keySet()))
    );
    if (profile.toolCalling()) throw new ApplicationException(
      400,
      "UNSUPPORTED_CAPABILITY",
      "Tool calling is reserved for a later adapter"
    );
    if (Boolean.TRUE.equals(providers.get(profile.provider()))) requireSafeEndpoint(
      profile.baseUrl()
    );
  }

  @Override
  public String effectiveMode(ModelProfile profile) {
    if (!profile.enabled()) return MODE_DISABLED;
    if (!Boolean.TRUE.equals(providers.get(profile.provider()))) return MODE_MOCK;
    return credentials.resolve(profile.apiKeyEnv()).isPresent()
      ? MODE_LIVE
      : MODE_MOCK;
  }

  /**
   * A credential-bearing endpoint must be TLS, must not embed credentials and
   * must not carry a query string. Plain HTTP is accepted only for a literal
   * loopback host, so a local OpenAI-compatible server (Ollama, vLLM, LM Studio)
   * can be used without sending the key off the machine. The loopback test is a
   * literal check with no DNS resolution, so a name such as
   * {@code 127.0.0.1.example.com} is rejected.
   */
  private static void requireSafeEndpoint(String baseUrl) {
    boolean accepted;
    try {
      var uri = URI.create(baseUrl);
      accepted =
        uri.getHost() != null &&
        uri.getUserInfo() == null &&
        uri.getQuery() == null &&
        ("https".equals(uri.getScheme()) ||
          ("http".equals(uri.getScheme()) && isLoopbackHost(uri.getHost())));
    } catch (RuntimeException e) {
      accepted = false;
    }
    if (!accepted) throw new ApplicationException(
      400,
      "INVALID_PROFILE",
      "Model base URL must be HTTPS, or plain HTTP only on a loopback host, without embedded credentials or query parameters"
    );
  }

  private static boolean isLoopbackHost(String host) {
    String value = host;
    if (value.startsWith("[") && value.endsWith("]")) value = value.substring(
      1,
      value.length() - 1
    );
    if ("localhost".equalsIgnoreCase(value) || "::1".equals(value)) return true;
    if (value.indexOf(':') >= 0) return false;
    var parts = value.split("\\.", -1);
    if (parts.length != 4) return false;
    for (var part : parts) {
      if (part.isEmpty() || part.length() > 3) return false;
      for (int i = 0; i < part.length(); i++) if (
        !Character.isDigit(part.charAt(i))
      ) return false;
      if (Integer.parseInt(part) > 255) return false;
    }
    return "127".equals(parts[0]);
  }

  private static List<String> sorted(Collection<String> values) {
    return values.stream().sorted().toList();
  }

  private static Map<String, Boolean> index(
    List<ModelProviderAdapter> adapters
  ) {
    var indexed = new LinkedHashMap<String, Boolean>();
    for (var adapter : adapters) indexed.put(
      adapter.provider(),
      adapter.requiresCredentials()
    );
    return indexed;
  }
}
