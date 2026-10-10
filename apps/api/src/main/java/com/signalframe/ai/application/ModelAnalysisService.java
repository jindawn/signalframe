package com.signalframe.ai.application;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.shared.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  /** Assumed when the deployment does not state a prompt budget. */
  static final long DEFAULT_PROMPT_WARN_CHARS = 60_000L;

  private static final Logger log = LoggerFactory.getLogger(
    ModelAnalysisService.class
  );

  private final ModelRouter router;
  private final ModelGateway gateway;
  private final ModelRunRepository runs;
  private final AnalysisResultValidator validator;
  private final PromptCatalog prompts;
  private final JsonCodec json;
  private final ModelRetryPolicy retryPolicy;
  private final long promptWarnChars;
  /** The projected AnalysisResult schema embedded in every synthesis prompt. */
  private final String outputSchema;

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
    @Value("${ai.retry.backoff-ms:250}") long backoffMs,
    @Value(
      "${ai.prompt.warn-chars:" +
      DEFAULT_PROMPT_WARN_CHARS +
      "}"
    ) long promptWarnChars
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
      ),
      promptWarnChars
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
    this(
      router,
      gateway,
      runs,
      validator,
      prompts,
      json,
      retryPolicy,
      DEFAULT_PROMPT_WARN_CHARS
    );
  }

  ModelAnalysisService(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    AnalysisResultValidator validator,
    PromptCatalog prompts,
    JsonCodec json,
    ModelRetryPolicy retryPolicy,
    long promptWarnChars
  ) {
    this.router = router;
    this.gateway = gateway;
    this.runs = runs;
    this.validator = validator;
    this.prompts = prompts;
    this.json = json;
    this.retryPolicy = retryPolicy;
    this.promptWarnChars = promptWarnChars;
    this.outputSchema = AnalysisOutputSchema.render(json);
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
    var framed = AnalysisPromptFrame.frame(
      prompt,
      guidance,
      outputSchema,
      news,
      repair,
      repairHint,
      json
    );
    var sections = framed.sections();
    log.info(
      "Synthesis prompt assembled provider={} model={} promptVersion={} {}",
      profile.provider(),
      profile.model(),
      prompt.version(),
      sections
    );
    if (sections.total() > promptWarnChars) {
      // Not an error: the request is still valid and the provider may accept it.
      // It is a warning because a provider whose context window is smaller than
      // this prompt will truncate silently, and the resulting output failure looks
      // like a model defect. `ai.prompt.warn-chars` is the operator's statement of
      // what this deployment expects its provider to accept.
      log.warn(
        "Synthesis prompt is {} characters, above ai.prompt.warn-chars={}: provider={} model={} maxTokens={}. " +
        "A provider with a smaller context window will truncate this prompt; compare the audited inputTokens of the ModelRun against the expected prompt size.",
        sections.total(),
        promptWarnChars,
        profile.provider(),
        profile.model(),
        profile.maxTokens()
      );
    }
    return new ModelRequest(
      jobId,
      ModelPurpose.SYNTHESIS,
      prompt.version(),
      framed.text(),
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
        case QUOTA_EXCEEDED -> "模型账户额度不足，请检查服务商计费与配额。";
        case CONFIGURATION, CAPABILITY -> "模型配置不可用，请检查 Profile 设置。";
        case TIMEOUT -> "模型调用超时，请稍后重试。";
        case RATE_LIMITED, PROVIDER_UNAVAILABLE, TRANSPORT -> "模型服务暂时不可用，请稍后重试。";
        default -> "模型调用失败，请稍后重试。";
      }
    );
  }
}
