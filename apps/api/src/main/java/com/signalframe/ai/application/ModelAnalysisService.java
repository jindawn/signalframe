package com.signalframe.ai.application;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.shared.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Application service that turns a news item into a validated analysis result
 * through the {@link ModelGateway} port.
 *
 * <p>Two bounded loops live here, not in the adapter, so that every attempt is
 * audited on its own: a transport retry loop that only repeats
 * {@link ModelFailure#retryable()} failures, and a repair loop that re-prompts
 * once when a response arrived but failed schema or provenance validation.
 * Malformed output therefore either recovers under control or fails with a
 * single explicit error; it is never accepted silently.
 */
@Service
public class ModelAnalysisService {

  private static final String OUTPUT_FAILED = "MODEL_OUTPUT_FAILED";

  private final ModelRouter router;
  private final ModelGateway gateway;
  private final ModelRunRepository runs;
  private final AnalysisResultValidator validator;
  private final PromptCatalog prompts;
  private final JsonCodec json;
  private final ModelRetryPolicy retryPolicy;
  private final String schema;

  @Autowired
  public ModelAnalysisService(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    AnalysisResultValidator validator,
    PromptCatalog prompts,
    JsonCodec json,
    @Value("${ai.retry.max-attempts:2}") int maxAttempts,
    @Value("${ai.retry.max-repair-attempts:1}") int maxRepairAttempts,
    @Value("${ai.retry.backoff-ms:250}") long backoffMs
  ) {
    this(
      router,
      gateway,
      runs,
      validator,
      prompts,
      json,
      new ModelRetryPolicy(
        maxAttempts,
        maxRepairAttempts,
        Duration.ofMillis(Math.max(0L, backoffMs))
      )
    );
  }

  public ModelAnalysisService(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    AnalysisResultValidator validator,
    PromptCatalog prompts,
    JsonCodec json
  ) {
    this(router, gateway, runs, validator, prompts, json, ModelRetryPolicy.DEFAULT);
  }

  public ModelAnalysisService(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    AnalysisResultValidator validator,
    PromptCatalog prompts,
    JsonCodec json,
    ModelRetryPolicy retryPolicy
  ) {
    this.router = router;
    this.gateway = gateway;
    this.runs = runs;
    this.validator = validator;
    this.prompts = prompts;
    this.json = json;
    this.retryPolicy = retryPolicy;
    this.schema = readSchema();
  }

  /** Bounds currently in force; exposed for diagnostics and tests. */
  public ModelRetryPolicy retryPolicy() {
    return retryPolicy;
  }

  public AnalysisResult synthesize(
    UUID jobId,
    String correlationId,
    NewsItem news,
    String guidance
  ) {
    var profile = router.route(ModelPurpose.SYNTHESIS);
    var prompt = prompts.synthesis();
    String repairHint = null;
    for (
      int round = 0;
      round <= retryPolicy.maxRepairAttempts();
      round++
    ) {
      var attempt = invoke(
        jobId,
        correlationId,
        news,
        guidance,
        prompt,
        profile,
        round > 0,
        repairHint
      );
      if (attempt.result() != null) return attempt.result();
      repairHint = attempt.repairHint();
    }
    throw new ApplicationException(
      503,
      OUTPUT_FAILED,
      "模型输出未通过验证，请重试。"
    );
  }

  private record Attempt(AnalysisResult result, String repairHint) {}

  private Attempt invoke(
    UUID jobId,
    String correlationId,
    NewsItem news,
    String guidance,
    PromptCatalog.VersionedPrompt prompt,
    ModelProfile profile,
    boolean repair,
    String repairHint
  ) {
    for (
      int attempt = 1;
      attempt <= retryPolicy.maxAttempts();
      attempt++
    ) {
      Instant startedAt = Instant.now();
      ModelResponse response = null;
      AnalysisResult result = null;
      String failureCode = null;
      String hint = null;
      try {
        response = gateway.call(
          request(
            jobId,
            prompt,
            guidance,
            news,
            profile,
            repair,
            repairHint
          )
        );
        try {
          result = validator.parse(response.content(), news);
        } catch (
          IllegalArgumentException
          | tools.jackson.core.JacksonException invalid
        ) {
          failureCode = ModelFailure.OUTPUT_INVALID.code();
          hint = hintOf(invalid);
        }
      } catch (ModelInvocationException failure) {
        audit(
          jobId,
          correlationId,
          profile,
          null,
          prompt.version(),
          startedAt,
          "FAILED",
          failure.code()
        );
        if (
          !failure.retryable() || attempt == retryPolicy.maxAttempts()
        ) throw toApplicationException(failure);
        pause();
        continue;
      } catch (RuntimeException unexpected) {
        audit(
          jobId,
          correlationId,
          profile,
          null,
          prompt.version(),
          startedAt,
          "FAILED",
          ModelFailure.UNKNOWN.code()
        );
        throw new ApplicationException(
          502,
          "MODEL_ERROR",
          "模型调用失败，请稍后重试。"
        );
      }
      audit(
        jobId,
        correlationId,
        profile,
        response,
        prompt.version(),
        startedAt,
        result == null ? "FAILED" : "SUCCEEDED",
        result == null ? failureCode : null
      );
      if (result != null) return new Attempt(result, null);
      return new Attempt(null, hint);
    }
    throw new IllegalStateException("Retry loop must return or throw");
  }

  private ModelRequest request(
    UUID jobId,
    PromptCatalog.VersionedPrompt prompt,
    String guidance,
    NewsItem news,
    ModelProfile profile,
    boolean repair,
    String repairHint
  ) {
    var text = new StringBuilder(prompt.text())
      .append('\n')
      .append(guidance)
      .append("\nSchema:\n")
      .append(schema)
      .append("\nUNTRUSTED SOURCE:\n")
      .append(json.write(news));
    if (repair) {
      text.append("\nPrevious output failed schema/provenance validation");
      if (repairHint != null) text.append(" (").append(repairHint).append(')');
      text.append(". Return corrected JSON only.");
    }
    return new ModelRequest(
      jobId,
      ModelPurpose.SYNTHESIS,
      prompt.version(),
      text.toString(),
      news,
      profile,
      repair
    );
  }

  /**
   * Persists one attempt. No prompt text, request body or credential is ever
   * written: the record keeps the identifiers, the prompt version, latency,
   * usage, status and the failure code only.
   */
  private void audit(
    UUID jobId,
    String correlationId,
    ModelProfile profile,
    ModelResponse response,
    String promptVersion,
    Instant startedAt,
    String status,
    String errorCode
  ) {
    Instant completedAt = Instant.now();
    var usage = response == null || response.usage() == null
      ? new ModelUsage(null, null, null, null)
      : response.usage();
    runs.save(
      new ModelRun(
        UUID.randomUUID(),
        jobId,
        response == null ? profile.provider() : response.provider(),
        response == null ? profile.model() : response.model(),
        ModelPurpose.SYNTHESIS,
        promptVersion,
        startedAt,
        completedAt,
        Duration.between(startedAt, completedAt).toMillis(),
        usage.inputTokens(),
        usage.outputTokens(),
        usage.totalTokens(),
        usage.estimatedCost(),
        status,
        errorCode,
        correlationId
      )
    );
  }

  private void pause() {
    var backoff = retryPolicy.backoff();
    if (backoff.isZero()) return;
    try {
      Thread.sleep(backoff.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ApplicationException(
        503,
        "MODEL_INTERRUPTED",
        "模型调用被中断。"
      );
    }
  }

  private static String hintOf(RuntimeException invalid) {
    String message = invalid.getMessage();
    if (message == null || message.isBlank()) return "schema or provenance mismatch";
    String compact = message.replaceAll("\\s+", " ").trim();
    return compact.length() <= 200 ? compact : compact.substring(0, 200);
  }

  private static ApplicationException toApplicationException(
    ModelInvocationException failure
  ) {
    int status = switch (failure.failure()) {
      case TIMEOUT, RATE_LIMITED, PROVIDER_UNAVAILABLE, TRANSPORT -> 503;
      default -> 502;
    };
    return new ApplicationException(
      status,
      failure.code(),
      switch (failure.failure()) {
        case AUTHENTICATION, PERMISSION_DENIED -> "模型凭据无效或缺失，请检查配置。";
        case CONFIGURATION, CAPABILITY -> "模型配置不可用，请检查 Profile 设置。";
        case TIMEOUT -> "模型调用超时，请稍后重试。";
        case RATE_LIMITED, PROVIDER_UNAVAILABLE, TRANSPORT -> "模型服务暂时不可用，请稍后重试。";
        default -> "模型调用失败，请稍后重试。";
      }
    );
  }

  private static String readSchema() {
    try {
      return new org.springframework.core.io.ClassPathResource(
        "analysis-result.schema.json"
      ).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Analysis schema resource missing", e);
    }
  }
}
