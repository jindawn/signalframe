package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import java.time.Duration;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleGateway implements ModelGateway {

  public ModelResponse call(ModelRequest request) {
    var p = request.profile();
    var builder = OpenAiChatOptions.builder()
      .baseUrl(p.baseUrl())
      .apiKey(System.getenv(p.apiKeyEnv()))
      .model(p.model())
      .temperature(p.temperature())
      .maxTokens(p.maxTokens())
      .timeout(Duration.ofSeconds(p.timeout()))
      .maxRetries(0);
    if (p.structuredOutput()) {
      var format = new OpenAiChatModel.ResponseFormat();
      format.setType(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
      builder.responseFormat(format);
    }
    var model = OpenAiChatModel.builder().options(builder.build()).build();
    var response = model.call(new Prompt(request.prompt()));
    var usage = response.getMetadata().getUsage();
    return new ModelResponse(
      response.getResult().getOutput().getText(),
      new ModelUsage(
        usage == null ? null : usage.getPromptTokens().longValue(),
        usage == null ? null : usage.getCompletionTokens().longValue(),
        usage == null ? null : usage.getTotalTokens().longValue(),
        null
      ),
      p.provider(),
      p.model()
    );
  }
}
