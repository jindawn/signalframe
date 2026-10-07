package com.signalframe.ai.application;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.shared.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ModelAnalysisService {

  private final ModelRouter router;
  private final ModelGateway gateway;
  private final ModelRunRepository runs;
  private final AnalysisResultValidator validator;
  private final PromptCatalog prompts;
  private final JsonCodec json;

  public ModelAnalysisService(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    AnalysisResultValidator validator,
    PromptCatalog prompts,
    JsonCodec json
  ) {
    this.router = router;
    this.gateway = gateway;
    this.runs = runs;
    this.validator = validator;
    this.prompts = prompts;
    this.json = json;
  }

  public AnalysisResult synthesize(
    UUID jobId,
    String correlationId,
    NewsItem news,
    String guidance
  ) {
    var profile = router.route(ModelPurpose.SYNTHESIS);
    var prompt = prompts.synthesis();
    String schema;
    try {
      schema = new org.springframework.core.io.ClassPathResource(
        "analysis-result.schema.json"
      ).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      Instant start = Instant.now();
      ModelResponse response = null;
      String status = "FAILED",
        error = null;
      try {
        var request = new ModelRequest(
          jobId,
          ModelPurpose.SYNTHESIS,
          prompt.version(),
          prompt.text() +
            "\n" +
            guidance +
            "\nSchema:\n" +
            schema +
            "\nUNTRUSTED SOURCE:\n" +
            json.write(news) +
            (attempt == 1
              ? "\nPrevious output failed schema/provenance validation. Return corrected JSON only."
              : ""),
          news,
          profile,
          attempt == 1
        );
        response = gateway.call(request);
        var result = validator.parse(response.content(), news);
        status = "SUCCEEDED";
        return result;
      } catch (Exception e) {
        error =
          e instanceof IllegalArgumentException ||
          e instanceof tools.jackson.core.JacksonException
            ? "SCHEMA_VALIDATION"
            : "PROVIDER_ERROR";
        if (attempt == 1) throw new ApplicationException(
          503,
          "MODEL_OUTPUT_FAILED",
          "模型输出未通过验证，请重试。"
        );
      } finally {
        Instant end = Instant.now();
        var usage =
          response == null
            ? new ModelUsage(null, null, null, null)
            : response.usage();
        runs.save(
          new ModelRun(
            UUID.randomUUID(),
            jobId,
            response == null ? profile.provider() : response.provider(),
            response == null ? profile.model() : response.model(),
            ModelPurpose.SYNTHESIS,
            prompt.version(),
            start,
            end,
            java.time.Duration.between(start, end).toMillis(),
            usage.inputTokens(),
            usage.outputTokens(),
            usage.totalTokens(),
            usage.estimatedCost(),
            status,
            error,
            correlationId
          )
        );
      }
    }
    throw new IllegalStateException("Unreachable");
  }
}
