package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import java.time.Duration;
import java.util.Set;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
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
 */
@Component
public class OpenAiCompatibleGateway implements ModelProviderAdapter {

  static final String PROVIDER = "openai-compatible";

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
      var response = chatModel(profile, credentials).call(
        new Prompt(request.prompt())
      );
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

  private static OpenAiChatModel chatModel(
    ModelProfile profile,
    ModelCredentials credentials
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
      new ModelUsage(
        tokens(usage == null ? null : usage.getPromptTokens()),
        tokens(usage == null ? null : usage.getCompletionTokens()),
        tokens(usage == null ? null : usage.getTotalTokens()),
        null
      ),
      PROVIDER,
      profile.model()
    );
  }

  private static Long tokens(Integer value) {
    return value == null ? null : value.longValue();
  }
}
