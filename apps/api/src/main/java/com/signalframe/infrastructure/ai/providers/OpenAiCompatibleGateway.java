package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adapter for any provider that speaks the OpenAI chat completions protocol.
 *
 * <p>Endpoint, model identifier, credential and generation settings all come
 * from the configured {@link ModelProfile}; nothing is hardcoded. The adapter
 * performs exactly one transport call: bounded retry, output validation and
 * repair belong to the application layer so that every attempt is audited
 * separately. The provider SDK's own retry is disabled for the same reason.
 *
 * <p>The client is built per call and never cached, so the credential it carries
 * lives only for the duration of that call. That keeps a rotated or revoked key
 * out of long-lived heap state and avoids any key-derived cache key.
 *
 * <h2>Client identity and session correlation</h2>
 * Every request identifies this runtime honestly as SignalFrame through
 * {@code User-Agent}; it never claims to be OpenCode, Claude Code or any other
 * client. Some OpenAI-compatible gateways additionally asked for by this
 * deployment expect a session correlation header so that a retry or a repair of
 * the same analysis is attributed to the same session. That header is
 * deployment configuration, never a provider or model name in business code: the
 * header name and whether it is sent come from {@code ai.client.*}, and its value
 * is derived from the job id, so it is stable across the retry loop, the repair
 * round and every stage of one analysis, and different for a different job.
 */
@Component
public class OpenAiCompatibleGateway implements ModelProviderAdapter {

  static final String PROVIDER = "openai-compatible";

  /** Truthful default client identity. Never a third-party client's name. */
  public static final String DEFAULT_USER_AGENT = "SignalFrame/0.1.0";

  /**
   * Header name the OpenCode Go gateway uses for session correlation. Only the
   * name is shared vocabulary; the value is always ours.
   */
  public static final String DEFAULT_SESSION_HEADER = "x-opencode-session";

  /** Namespace that keeps the derived session id opaque and ours alone. */
  static final String SESSION_NAMESPACE = "signalframe-analysis:";

  private final String userAgent;
  private final String sessionHeaderName;
  private final boolean sessionHeaderEnabled;

  /** Test/default construction: documented defaults, no Spring context. */
  public OpenAiCompatibleGateway() {
    this(DEFAULT_USER_AGENT, DEFAULT_SESSION_HEADER, true);
  }

  @Autowired
  public OpenAiCompatibleGateway(
    @Value("${ai.client.user-agent:" + DEFAULT_USER_AGENT + "}") String userAgent,
    @Value(
      "${ai.client.session-header-name:" + DEFAULT_SESSION_HEADER + "}"
    ) String sessionHeaderName,
    @Value("${ai.client.session-header-enabled:true}") boolean sessionHeaderEnabled
  ) {
    this.userAgent = userAgent == null || userAgent.isBlank()
      ? DEFAULT_USER_AGENT
      : userAgent.trim();
    this.sessionHeaderName = sessionHeaderName == null ? "" : sessionHeaderName.trim();
    this.sessionHeaderEnabled = sessionHeaderEnabled;
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public Set<ModelCapability> capabilities() {
    return Set.of(ModelCapability.STRUCTURED_OUTPUT);
  }

  @Override
  public boolean requiresCredentials() {
    return true;
  }

  @Override
  public ModelResponse call(
    ModelRequest request,
    ModelCredentials credentials
  ) {
    if (credentials == null) throw ModelInvocationException.of(
      ModelFailure.AUTHENTICATION,
      "No credential configured for the selected profile"
    );
    var profile = request.profile();
    try {
      var response = chatModel(
        profile,
        credentials,
        clientHeaders(request)
      ).call(new Prompt(request.prompt()));
      return toResponse(response, profile, credentials);
    } catch (ModelInvocationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ModelInvocationException.of(
        ProviderFailures.classify(e),
        ProviderFailures.describe(e),
        credentials
      );
    }
  }

  /**
   * The headers this runtime adds to a request.
   *
   * <p>Kept separate from the provider transport so that the identity and the
   * session rule are testable against a stub without a live endpoint.
   */
  Map<String, String> clientHeaders(ModelRequest request) {
    var headers = new LinkedHashMap<String, String>();
    headers.put("User-Agent", userAgent);
    if (
      sessionHeaderEnabled &&
      !sessionHeaderName.isBlank() &&
      request != null &&
      request.jobId() != null
    ) headers.put(sessionHeaderName, sessionId(request.jobId()));
    return Map.copyOf(headers);
  }

  /**
   * A stable, opaque session id for one analysis.
   *
   * <p>Derived from the job id rather than generated per call, which is what
   * makes it identical for the first attempt, a transport retry, the single
   * repair round and every staged call of the same analysis, without any state
   * to keep and without leaking the internal job identifier.
   */
  public static String sessionId(UUID jobId) {
    return UUID.nameUUIDFromBytes(
      (SESSION_NAMESPACE + jobId).getBytes(StandardCharsets.UTF_8)
    ).toString();
  }

  private static OpenAiChatModel chatModel(
    ModelProfile profile,
    ModelCredentials credentials,
    Map<String, String> headers
  ) {
    var options = OpenAiChatOptions.builder()
      .baseUrl(profile.baseUrl())
      .apiKey(credentials.value())
      .model(profile.model())
      .temperature(profile.temperature())
      .maxTokens(profile.maxTokens())
      .timeout(Duration.ofSeconds(profile.timeout()))
      .maxRetries(0);
    if (profile.structuredOutput()) {
      var format = new OpenAiChatModel.ResponseFormat();
      format.setType(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
      options.responseFormat(format);
    }
    if (!headers.isEmpty()) options.customHeaders(headers);
    return OpenAiChatModel.builder().options(options.build()).build();
  }

  private static ModelResponse toResponse(
    ChatResponse response,
    ModelProfile profile,
    ModelCredentials credentials
  ) {
    var result = response.getResult();
    String text = result == null || result.getOutput() == null
      ? null
      : result.getOutput().getText();
    if (text == null || text.isBlank()) throw ModelInvocationException.of(
      ModelFailure.PROVIDER_RESPONSE_INVALID,
      "Provider returned an empty completion",
      credentials
    );
    var usage = response.getMetadata() == null
      ? null
      : response.getMetadata().getUsage();
    return new ModelResponse(
      text,
      toUsage(usage),
      PROVIDER,
      profile.model()
    );
  }

  /**
   * Maps reported usage, preserving "unknown" as unknown.
   *
   * <p>A provider that does not report usage at all leaves the SDK metadata
   * empty; the SDK renders that as zeros. Unknown token counts and costs must
   * stay null and must never be fabricated into zeros, so an empty usage record
   * is reported as null on every field. A provider that explicitly reports
   * zero tokens is still reported as zero.
   */
  private static ModelUsage toUsage(
    org.springframework.ai.chat.metadata.Usage usage
  ) {
    if (usage == null || usage instanceof EmptyUsage) return new ModelUsage(
      null,
      null,
      null,
      null
    );
    return new ModelUsage(
      tokens(usage.getPromptTokens()),
      tokens(usage.getCompletionTokens()),
      tokens(usage.getTotalTokens()),
      null
    );
  }

  private static Long tokens(Integer value) {
    return value == null ? null : value.longValue();
  }
}
