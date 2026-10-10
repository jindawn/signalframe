package com.signalframe.analysis.application.steps;

import com.signalframe.ai.application.ModelRouter;
import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Performs one protocol stage's model call under the existing AI abstraction.
 *
 * <p>This is deliberately a thin composition over {@link ModelGateway} and
 * {@link ModelRouter}: the purpose selects the profile, never a provider or
 * model name, so no {@code if provider == ...} branch exists anywhere in the
 * pipeline. The two bounded loops of the foundation runtime are reused —
 * transport retry for {@link ModelFailure#retryable()} failures and exactly one
 * repair round when a response decoded but failed the stage's typed validation —
 * and every attempt is audited on its own as a {@link ModelRun}, including the
 * prompt version that produced it (protocol PR-15/PR-18).
 *
 * <p>Recoverable outcome: an {@link StageOutputException} from the parser is
 * turned into an output failure; an unrecoverable one is a {@link StageFailure}
 * with a classification the job records. Raw prompt text and raw model output are
 * never written to the audit record.
 */
@Component
public class StageModelClient {

  private static final Logger log = LoggerFactory.getLogger(
    StageModelClient.class
  );

  /** Parser for one stage's typed artifact; throws {@link StageOutputException}. */
  @FunctionalInterface
  public interface StageParser<T> {
    T parse(String content);
  }

  /** One stage model call: identity, routing purpose, prompt version and text. */
  public record StageCall(
    String stage,
    UUID jobId,
    String correlationId,
    NewsItem news,
    ModelPurpose purpose,
    String promptVersion,
    String prompt
  ) {}

  private static final String OUTPUT_INVALID = "OUTPUT_INVALID";

  private final ModelRouter router;
  private final ModelGateway gateway;
  private final ModelRunRepository runs;
  private final ModelRetryPolicy retryPolicy;

  @Autowired
  public StageModelClient(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    @Value("${ai.retry.max-attempts:2}") int maxAttempts,
    @Value("${ai.retry.max-repair-attempts:1}") int maxRepairAttempts,
    @Value("${ai.retry.backoff-ms:250}") long backoffMs
  ) {
    this(
      router,
      gateway,
      runs,
      new ModelRetryPolicy(
        maxAttempts,
        maxRepairAttempts,
        Duration.ofMillis(Math.max(0L, backoffMs))
      )
    );
  }

  public StageModelClient(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    ModelRetryPolicy retryPolicy
  ) {
    this.router = router;
    this.gateway = gateway;
    this.runs = runs;
    this.retryPolicy = retryPolicy;
  }

  public ModelRetryPolicy retryPolicy() {
    return retryPolicy;
  }

  public <T> T invoke(StageCall call, StageParser<T> parser) {
    return invoke(router.route(call.purpose()), call, parser);
  }

  /**
   * The same call against an explicit profile.
   *
   * <p>Routing by purpose is unchanged and remains the default. This overload
   * exists so a caller that owns a per-stage budget can pass a profile derived
   * from the routed one, without the adapter or the gateway learning anything
   * about providers, models or stages.
   */
  public <T> T invoke(
    ModelProfile profile,
    StageCall call,
    StageParser<T> parser
  ) {
    StageFailure.Kind lastKind = StageFailure.Kind.MALFORMED_OUTPUT;
    String lastHint = null;
    for (
      int round = 0;
      round <= retryPolicy.maxRepairAttempts();
      round++
    ) {
      String repairHint = round == 0 ? null : lastHint;
      for (
        int attempt = 1;
        attempt <= retryPolicy.maxAttempts();
        attempt++
      ) {
        Instant startedAt = Instant.now();
        ModelResponse response = null;
        try {
          response = gateway.call(
            new ModelRequest(
              call.jobId(),
              call.purpose(),
              call.promptVersion(),
              prompt(call, repairHint),
              call.news(),
              profile,
              round > 0
            )
          );
        } catch (ModelInvocationException failure) {
          audit(call, profile, null, startedAt, "FAILED", failure.code());
          if (
            failure.retryable() && attempt < retryPolicy.maxAttempts()
          ) {
            pause();
            continue;
          }
          // The redacted provider detail travels with the failure: the audit row
          // keeps the frozen shape and the classification only, so without it a
          // failed paid attempt cannot be told apart from any other failure with
          // the same code.
          log.warn(
            "Stage call failed stage={} code={} retryable={} detail={}",
            call.stage(),
            failure.code(),
            failure.retryable(),
            failure.detail()
          );
          throw new StageFailure(
            call.stage(),
            failure.failure() == ModelFailure.TIMEOUT
              ? StageFailure.Kind.TIMEOUT
              : StageFailure.Kind.PROVIDER_FAILURE,
            failure.detail().isBlank()
              ? failure.code()
              : failure.code() + ": " + failure.detail()
          );
        } catch (RuntimeException unexpected) {
          audit(
            call,
            profile,
            null,
            startedAt,
            "FAILED",
            ModelFailure.UNKNOWN.code()
          );
          throw new StageFailure(
            call.stage(),
            StageFailure.Kind.PROVIDER_FAILURE,
            "unclassified adapter failure"
          );
        }
        try {
          var parsed = parser.parse(response.content());
          audit(call, profile, response, startedAt, "SUCCEEDED", null);
          return parsed;
        } catch (StageOutputException invalid) {
          lastKind = invalid.kind();
          lastHint = invalid.hint();
          audit(
            call,
            profile,
            response,
            startedAt,
            "FAILED",
            OUTPUT_INVALID
          );
          break;
        }
      }
    }
    throw new StageFailure(call.stage(), lastKind, lastHint);
  }

  private static String prompt(StageCall call, String repairHint) {
    if (repairHint == null) return call.prompt();
    return (
      call.prompt() +
      "\n\nPrevious output was rejected by protocol validation: " +
      repairHint +
      "\nReturn corrected JSON only, with no prose, matching the required object exactly."
    );
  }

  /**
   * Persists one attempt. No prompt text, no response body and no credential is
   * ever written: identifiers, prompt version, latency, usage and failure code
   * only.
   */
  private void audit(
    StageCall call,
    ModelProfile profile,
    ModelResponse response,
    Instant startedAt,
    String status,
    String errorCode
  ) {
    Instant completedAt = Instant.now();
    ModelUsage usage = response == null || response.usage() == null
      ? new ModelUsage(null, null, null, null)
      : response.usage();
    runs.save(
      new ModelRun(
        UUID.randomUUID(),
        call.jobId(),
        response == null ? profile.provider() : response.provider(),
        response == null ? profile.model() : response.model(),
        call.purpose(),
        call.promptVersion(),
        startedAt,
        completedAt,
        Duration.between(startedAt, completedAt).toMillis(),
        usage.inputTokens(),
        usage.outputTokens(),
        usage.totalTokens(),
        usage.estimatedCost(),
        status,
        errorCode,
        call.correlationId()
      )
    );
  }

  private void pause() {
    Duration backoff = retryPolicy.backoff();
    if (backoff.isZero()) return;
    try {
      Thread.sleep(backoff.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new StageFailure(
        "ModelCall",
        StageFailure.Kind.PROVIDER_FAILURE,
        "interrupted"
      );
    }
  }
}
