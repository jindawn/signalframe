package com.signalframe.analysis.application.steps;

import com.signalframe.ai.application.AnalysisOutputSchema;
import com.signalframe.ai.application.AnalysisResultValidator;
import com.signalframe.ai.application.ModelRouter;
import com.signalframe.ai.domain.*;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Two-stage synthesis: two smaller, separately validated model contracts assembled
 * deterministically into one snapshot.
 *
 * <p>It replaces exactly one thing — how the run's draft snapshot is obtained. The
 * 14 durable pipeline steps, the artifact extraction stages, Gate A–E, the
 * deterministic rubric, persistence and the {@code ModelRun} audit are untouched,
 * so this is a second way to produce the draft, not a second pipeline.
 *
 * <pre>
 *  stage 1  facts + provenance   small schema,  facts stage envelope, own prompt version
 *  stage 2  analytical reasoning  small schema,  own prompt version, fact ids from stage 1
 *  assemble deterministic         SnapshotProjection + the single rubric + AnalysisResultValidator
 * </pre>
 *
 * <p>Both calls go through the existing {@link ModelGateway} by
 * {@link ModelPurpose} and the existing {@link StageModelClient}, which already
 * gives each stage its own bounded transport retry, its own single repair round and
 * its own per-attempt {@code ModelRun} audit rows. No provider or model name
 * appears here, and no stage may proceed on an unvalidated predecessor:
 * stage 2 is not called at all when stage 1 fails, and nothing is persisted unless
 * both stages and the assembler pass.
 */
@Component
public class TwoStageSynthesisService {

  public static final String FACTS_STAGE = "TwoStageFacts";
  public static final String REASONING_STAGE = "TwoStageReasoning";

  /** Prompt directories; each carries its own version file (PR-15). */
  static final String FACTS_PROMPT_DIR = "two-stage-facts";
  static final String REASONING_PROMPT_DIR = "two-stage-reasoning";

  /** Roots of each stage's prompt schema, projected from the one contract. */
  static final Set<String> FACTS_SCHEMA_ROOTS = Set.of(
    "SourceAssessment",
    "Fact"
  );

  static final Set<String> REASONING_SCHEMA_ROOTS = Set.of(
    "Statement",
    "Variable",
    "CausalLink",
    "StakeholderImpact",
    "Hypothesis",
    "CorroboratingSignal",
    "Indicator",
    "Prediction"
  );

  private final ModelAnalysisDeps deps;

  /** Small holder so the constructor stays readable. */
  record ModelAnalysisDeps(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    StageResponseReader reader,
    PromptLibrary prompts,
    JsonCodec json,
    AnalysisResultValidator validator,
    ConfidenceRubric rubric,
    List<DomainAnalysisStrategy> strategies
  ) {}

  /** Bounds mirrored from the generated ModelProfile contract, checked at startup. */
  static final int MIN_MAX_TOKENS = 128;
  static final int MAX_MAX_TOKENS = 32_000;
  static final int MIN_TIMEOUT = 1;
  static final int MAX_TIMEOUT = 120;

  private final ModelRetryPolicy factsPolicy;
  private final ModelRetryPolicy reasoningPolicy;
  private final String factsSchema;
  private final String reasoningSchema;
  private final TwoStageAssembly assembly;
  private final int factsMaxTokensOverride;
  private final int factsTimeoutOverride;
  private final int reasoningMaxTokensOverride;
  private final int reasoningTimeoutOverride;
  private final int maxProviderCalls;
  private final long totalTimeoutMs;

  public TwoStageSynthesisService(
    ModelRouter router,
    ModelGateway gateway,
    ModelRunRepository runs,
    StageResponseReader reader,
    PromptLibrary prompts,
    JsonCodec json,
    AnalysisResultValidator validator,
    ConfidenceRubric rubric,
    List<DomainAnalysisStrategy> strategies,
    @Value(
      "${ai.two-stage.facts.max-attempts:${ai.retry.max-attempts:2}}"
    ) int factsMaxAttempts,
    @Value(
      "${ai.two-stage.facts.max-repair-attempts:${ai.retry.max-repair-attempts:1}}"
    ) int factsMaxRepair,
    @Value("${ai.two-stage.facts.backoff-ms:${ai.retry.backoff-ms:250}}") long factsBackoff,
    @Value(
      "${ai.two-stage.reasoning.max-attempts:${ai.retry.max-attempts:2}}"
    ) int reasoningMaxAttempts,
    @Value(
      "${ai.two-stage.reasoning.max-repair-attempts:${ai.retry.max-repair-attempts:1}}"
    ) int reasoningMaxRepair,
    @Value(
      "${ai.two-stage.reasoning.backoff-ms:${ai.retry.backoff-ms:250}}"
    ) long reasoningBackoff,
    @Value("${ai.two-stage.facts.max-tokens:0}") int factsMaxTokensOverride,
    @Value("${ai.two-stage.facts.timeout:0}") int factsTimeoutOverride,
    @Value("${ai.two-stage.reasoning.max-tokens:0}") int reasoningMaxTokensOverride,
    @Value("${ai.two-stage.reasoning.timeout:0}") int reasoningTimeoutOverride,
    @Value("${ai.two-stage.max-provider-calls:4}") int maxProviderCalls,
    @Value("${ai.two-stage.total-timeout-ms:0}") long totalTimeoutMs
  ) {
    this.deps = new ModelAnalysisDeps(
      router,
      gateway,
      runs,
      reader,
      prompts,
      json,
      validator,
      rubric,
      strategies
    );
    this.factsPolicy = new ModelRetryPolicy(
      factsMaxAttempts,
      factsMaxRepair,
      Duration.ofMillis(Math.max(0L, factsBackoff))
    );
    this.reasoningPolicy = new ModelRetryPolicy(
      reasoningMaxAttempts,
      reasoningMaxRepair,
      Duration.ofMillis(Math.max(0L, reasoningBackoff))
    );
    // Each stage prompt is shown only the definitions it may produce, projected
    // from the generated contract so it can never drift from it.
    this.factsSchema = AnalysisOutputSchema.renderFor(json, FACTS_SCHEMA_ROOTS);
    this.reasoningSchema = AnalysisOutputSchema.renderFor(
      json,
      REASONING_SCHEMA_ROOTS
    );
    this.assembly = new TwoStageAssembly(validator, json, rubric, strategies);
    // A stage budget is deployment configuration, so an out-of-contract value is a
    // configuration error at startup rather than a failed paid call later.
    this.factsMaxTokensOverride = requireRange(
      "ai.two-stage.facts.max-tokens",
      factsMaxTokensOverride,
      0,
      MIN_MAX_TOKENS,
      MAX_MAX_TOKENS
    );
    this.factsTimeoutOverride = requireRange(
      "ai.two-stage.facts.timeout",
      factsTimeoutOverride,
      0,
      MIN_TIMEOUT,
      MAX_TIMEOUT
    );
    this.reasoningMaxTokensOverride = requireRange(
      "ai.two-stage.reasoning.max-tokens",
      reasoningMaxTokensOverride,
      0,
      MIN_MAX_TOKENS,
      MAX_MAX_TOKENS
    );
    this.reasoningTimeoutOverride = requireRange(
      "ai.two-stage.reasoning.timeout",
      reasoningTimeoutOverride,
      0,
      MIN_TIMEOUT,
      MAX_TIMEOUT
    );
    if (maxProviderCalls < 1) throw new IllegalStateException(
      "ai.two-stage.max-provider-calls must be at least 1"
    );
    if (totalTimeoutMs < 0) throw new IllegalStateException(
      "ai.two-stage.total-timeout-ms must not be negative"
    );
    this.maxProviderCalls = maxProviderCalls;
    this.totalTimeoutMs = totalTimeoutMs;
  }

  /** 0 means "inherit the routed profile"; anything else must be in contract range. */
  private static int requireRange(
    String key,
    int value,
    int disabled,
    int min,
    int max
  ) {
    if (value == disabled) return value;
    if (value < min || value > max) throw new IllegalStateException(
      key +
      " must be 0 (inherit the routed profile) or between " +
      min +
      " and " +
      max +
      ", got " +
      value
    );
    return value;
  }

  /** The provider-call ceiling in force for one analysis. */
  public int maxProviderCalls() {
    return maxProviderCalls;
  }

  /** The total analysis deadline in force, or 0 when no total budget is set. */
  public long totalTimeoutMs() {
    return totalTimeoutMs;
  }

  /** The profile a stage will actually call, with its own budget applied. */
  public ModelProfile stageProfile(ModelPurpose purpose, int maxTokens, int timeout) {
    var routed = deps.router().route(purpose);
    if (maxTokens == 0 && timeout == 0) return routed;
    return new ModelProfile(
      routed.provider(),
      routed.model(),
      routed.baseUrl(),
      routed.apiKeyEnv(),
      routed.temperature(),
      maxTokens == 0 ? routed.maxTokens() : maxTokens,
      timeout == 0 ? routed.timeout() : timeout,
      routed.structuredOutput(),
      routed.toolCalling(),
      routed.enabled()
    );
  }

  /** The retry policy in force for each stage; exposed for diagnostics and tests. */
  public ModelRetryPolicy factsRetryPolicy() {
    return factsPolicy;
  }

  public ModelRetryPolicy reasoningRetryPolicy() {
    return reasoningPolicy;
  }

  /** Stage prompt sizes, for the offline budget report. */
  public Map<String, Integer> promptSizes() {
    return Map.of(
      FACTS_STAGE,
      factsPrompt().text().length() + factsSchema.length(),
      REASONING_STAGE,
      reasoningPrompt().text().length() + reasoningSchema.length()
    );
  }

  /** The assembled snapshot plus the prompt version each stage ran with (PR-15). */
  public record TwoStageResult(
    AnalysisResult snapshot,
    String factsPromptVersion,
    String reasoningPromptVersion
  ) {}

  /**
   * Runs both stages and returns a snapshot the full validator has accepted.
   *
   * <p>Failure is always a {@link StageFailure} naming the stage that failed, with
   * a classification the job records. Nothing partial escapes: a failed stage 1
   * stops before stage 2 is called, and a failed stage 2 or assembler stops before
   * anything is returned.
   */
  public TwoStageResult analyze(
    UUID jobId,
    String correlationId,
    PipelineState state,
    String guidance
  ) {
    // One budget for the whole analysis: a hard ceiling on provider requests and,
    // when configured, a deadline that stops the second stage from starting once
    // the budget is spent. Both are per-analysis, so no stage can spend more than
    // its share and the job cannot spend more than the deployment allows.
    var budget = new ProviderCallBudget(deps.gateway(), maxProviderCalls);
    var factsClient = new StageModelClient(
      deps.router(),
      budget,
      deps.runs(),
      factsPolicy
    );
    var reasoningClient = new StageModelClient(
      deps.router(),
      budget,
      deps.runs(),
      reasoningPolicy
    );
    var deadline = totalTimeoutMs == 0L ? null : Instant.now().plusMillis(totalTimeoutMs);

    requireNotCancelled(FACTS_STAGE);
    var factsPrompt = factsPrompt();
    var stageOne = factsClient.invoke(
      stageProfile(
        ModelPurpose.FACT_EXTRACTION,
        factsMaxTokensOverride,
        factsTimeoutOverride
      ),
      new StageModelClient.StageCall(
        FACTS_STAGE,
        jobId,
        correlationId,
        state.news(),
        ModelPurpose.FACT_EXTRACTION,
        factsPrompt.version(),
        factsPromptText(factsPrompt, state)
      ),
      raw -> validateFacts(raw, state)
    );

    var withFacts = state.withFacts(stageOne.value());
    requireWithinDeadline(deadline, REASONING_STAGE);
    requireNotCancelled(REASONING_STAGE);
    var reasoningPrompt = reasoningPrompt();
    var reasoning = reasoningClient.invoke(
      stageProfile(
        ModelPurpose.SYNTHESIS,
        reasoningMaxTokensOverride,
        reasoningTimeoutOverride
      ),
      new StageModelClient.StageCall(
        REASONING_STAGE,
        jobId,
        correlationId,
        state.news(),
        ModelPurpose.SYNTHESIS,
        reasoningPrompt.version(),
        reasoningPromptText(reasoningPrompt, withFacts, guidance)
      ),
      raw -> validateReasoning(raw, withFacts)
    );

    // The assembled draft is itself a complete snapshot, so it records the two
    // stage versions as executed stages; the caller also marks them on the outer
    // pipeline state so the persisted provenance names them (PR-06/PR-15).
    var assembledState = applyReasoning(
      withFacts,
      reasoning,
      stageOne.demo() || reasoning.demo()
    )
      .executed(FACTS_STAGE, factsPrompt.version())
      .executed(REASONING_STAGE, reasoningPrompt.version());
    requireWithinDeadline(deadline, "TwoStageAssembly");
    return new TwoStageResult(
      assembly.assemble(assembledState, "TwoStageAssembly"),
      factsPrompt.version(),
      reasoningPrompt.version()
    );
  }

  /** Interruption is the only cancellation this in-process queue supports. */
  private static void requireNotCancelled(String stage) {
    if (Thread.currentThread().isInterrupted()) throw new StageFailure(
      stage,
      StageFailure.Kind.PROVIDER_FAILURE,
      "the analysis was cancelled before this stage started"
    );
  }

  private static void requireWithinDeadline(Instant deadline, String stage) {
    if (deadline != null && Instant.now().isAfter(deadline)) {
      throw new StageFailure(
        stage,
        StageFailure.Kind.TIMEOUT,
        "the analysis exceeded ai.two-stage.total-timeout-ms before " +
        stage +
        " could start; no further provider call was made"
      );
    }
  }

  /**
   * A per-analysis ceiling on provider requests.
   *
   * <p>It is a {@link ModelGateway} decorator, so it needs no change to the gateway
   * or the adapter and applies to every attempt of both stages, including the
   * bounded retry and the repair round. Exceeding it is a configuration-class,
   * non-retryable failure: the retry loop stops immediately instead of spending
   * more of a metered budget.
   */
  static final class ProviderCallBudget implements ModelGateway {

    private final ModelGateway delegate;
    private final int limit;
    private final java.util.concurrent.atomic.AtomicInteger used =
      new java.util.concurrent.atomic.AtomicInteger();

    ProviderCallBudget(ModelGateway delegate, int limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    int used() {
      return used.get();
    }

    @Override
    public ModelResponse call(ModelRequest request) {
      if (used.incrementAndGet() > limit) throw ModelInvocationException.of(
        ModelFailure.CONFIGURATION,
        "provider call budget exhausted: this analysis already made " +
        limit +
        " request(s), the ceiling set by ai.two-stage.max-provider-calls"
      );
      return delegate.call(request);
    }
  }

  /**
   * Stage 1 alone.
   *
   * <p>Runs the fact contract and nothing else: no stage 2, no assembly, no pipeline.
   * It exists so the fact contract can be probed against a real provider without the
   * rest of an analysis, and it reuses the same prompt assembly, the same
   * {@link StageModelClient}, the same retry policy and the same per-analysis call
   * ceiling as the full path — there is no second adapter and no second code path.
   */
  public FactsProbe factsOnly(
    UUID jobId,
    String correlationId,
    PipelineState state
  ) {
    var budget = new ProviderCallBudget(deps.gateway(), maxProviderCalls);
    var client = new StageModelClient(
      deps.router(),
      budget,
      deps.runs(),
      factsPolicy
    );
    var prompt = factsPrompt();
    var artifacts = client.invoke(
      stageProfile(
        ModelPurpose.FACT_EXTRACTION,
        factsMaxTokensOverride,
        factsTimeoutOverride
      ),
      new StageModelClient.StageCall(
        FACTS_STAGE,
        jobId,
        correlationId,
        state.news(),
        ModelPurpose.FACT_EXTRACTION,
        prompt.version(),
        factsPromptText(prompt, state)
      ),
      raw -> validateFacts(raw, state)
    );
    return new FactsProbe(
      artifacts.value(),
      artifacts.unknowns(),
      artifacts.demo(),
      prompt.version(),
      budget.used(),
      stageProfile(
        ModelPurpose.FACT_EXTRACTION,
        factsMaxTokensOverride,
        factsTimeoutOverride
      )
    );
  }

  /** The outcome of a stage-1-only run, for the probe report. */
  public record FactsProbe(
    List<ProtocolModel.Fact> facts,
    List<ProtocolModel.Unknown> unknowns,
    boolean demo,
    String promptVersion,
    int providerCalls,
    ModelProfile profile
  ) {}

  // ---- stage 1 -------------------------------------------------------------

  /**
   * Stage 1 is validated by the existing facts reader: non-empty source refs, every
   * quote a verbatim span of the exact source text with in-range UTF-16 offsets,
   * duplicate spans collapsed, an empty fact set rejected, and CORROBORATED refused
   * without an independent source. Nothing about provenance is re-decided here.
   */
  StageResponseReader.Artifacts<ProtocolModel.Fact> validateFacts(
    String raw,
    PipelineState state
  ) {
    var artifacts = deps.reader().facts(raw, state);
    if (artifacts.value().isEmpty()) throw StageOutputException.schema(
      "stage 1: no fact survived validation, so stage 2 has nothing to reason over (STG-03)"
    );
    return artifacts;
  }

  // ---- stage 2 -------------------------------------------------------------

  /**
   * Stage 2's validated artifacts. Either shape the rest of the codebase already
   * accepts is supported, for the same reason every other reader supports both: the
   * offline reference fixture answers every purpose with a complete snapshot.
   */
  record ValidatedReasoning(
    String summary,
    List<ProtocolModel.Variable> variables,
    List<ProtocolModel.Mechanism> mechanisms,
    List<ProtocolModel.Stakeholder> stakeholders,
    List<ProtocolModel.Effect> firstOrderEffects,
    List<ProtocolModel.Effect> secondOrderEffects,
    List<ProtocolModel.Hypothesis> hypotheses,
    List<ProtocolModel.Alternative> alternatives,
    List<ProtocolModel.CounterArgument> counterArguments,
    List<ProtocolModel.FalsificationCondition> falsificationConditions,
    List<ProtocolModel.Signal> signals,
    List<ProtocolModel.PlanItem> plan,
    List<ProtocolModel.Prediction> predictions,
    List<ProtocolModel.Unknown> unknowns,
    boolean demo
  ) {}

  /**
   * Stage 2 is parsed into its own narrowed contract and then handed to the
   * existing readers one array at a time, in the shape each of them already
   * validates. A missing array is a schema failure rather than an empty result, so
   * an omitted artifact can never read as "evaluated, nothing found" (PR-05).
   */
  ValidatedReasoning validateReasoning(String raw, PipelineState state) {
    RuntimeException narrowFailure;
    try {
      return validateNarrowReasoning(
        deps.json().read(raw, TwoStageModel.Reasoning.class),
        state
      );
    } catch (StageOutputException rejected) {
      throw rejected;
    } catch (RuntimeException notTheNarrowShape) {
      narrowFailure = notTheNarrowShape;
    }
    return validateSnapshotReasoning(raw, state, narrowFailure);
  }

  private ValidatedReasoning validateNarrowReasoning(
    TwoStageModel.Reasoning parsed,
    PipelineState state
  ) {
    require(parsed.summary(), "summary");
    require(parsed.variables(), "variables");
    require(parsed.mechanisms(), "mechanisms");
    require(parsed.stakeholders(), "stakeholders");
    require(parsed.firstOrderEffects(), "firstOrderEffects");
    require(parsed.secondOrderEffects(), "secondOrderEffects");
    require(parsed.hypotheses(), "hypotheses");
    require(parsed.alternativeExplanations(), "alternativeExplanations");
    require(parsed.counterArguments(), "counterArguments");
    require(parsed.falsificationConditions(), "falsificationConditions");
    require(parsed.corroboratingSignals(), "corroboratingSignals");
    require(parsed.verificationIndicators(), "verificationIndicators");
    require(parsed.predictions(), "predictions");

    var unknowns = parsed.unknowns() == null
      ? List.<ProtocolModel.Unknown>of()
      : parsed.unknowns();
    // Every array is validated by the reader that already owns its rules, and the
    // reasoning references are checked against the stage-1 fact ids.
    var variables = deps
      .reader()
      .variables(envelope("variables", parsed.variables(), List.of()), state)
      .value();
    var mechanisms = deps
      .reader()
      .mechanisms(envelope("mechanisms", parsed.mechanisms(), List.of()), state)
      .value();
    var stakeholders = deps
      .reader()
      .stakeholders(envelope("stakeholders", parsed.stakeholders(), List.of()), state)
      .value();
    var firstOrder = deps
      .reader()
      .firstOrderEffects(
        envelope("effects", parsed.firstOrderEffects(), List.of()),
        state
      )
      .value();
    var secondOrder = deps
      .reader()
      .secondOrderEffects(
        envelope("effects", parsed.secondOrderEffects(), List.of()),
        state
      )
      .value();
    // One reply carries artifacts that reference each other, so the state has to
    // grow in dependency order exactly as the staged topology does across calls:
    // hypotheses first, then everything whose refs point at them. Validating a
    // hypothesis reference before the hypothesis exists would reject a legitimate
    // reply for a reason the model cannot act on (PR-09).
    var hypotheses = deps
      .reader()
      .hypotheses(envelope("hypotheses", parsed.hypotheses(), List.of()), state)
      .value();
    var withHypotheses = state.withHypotheses(hypotheses);
    var alternatives = deps
      .reader()
      .alternativeExplanations(
        envelope("alternatives", parsed.alternativeExplanations(), List.of()),
        withHypotheses
      )
      .value();
    var counterArguments = deps
      .reader()
      .counterArguments(
        envelope("counterArguments", parsed.counterArguments(), List.of()),
        withHypotheses
      )
      .value();
    var falsification = deps
      .reader()
      .falsificationConditions(
        envelope("falsificationConditions", parsed.falsificationConditions(), List.of()),
        withHypotheses
      )
      .value();
    var signals = deps
      .reader()
      .corroboratingSignals(
        envelope("signals", parsed.corroboratingSignals(), List.of()),
        withHypotheses
      )
      .value();
    var predictions = deps
      .reader()
      .predictions(
        envelope("predictions", parsed.predictions(), List.of()),
        withHypotheses
      )
      .value();
    // The plan read carries the unknowns, so they are validated exactly once, and
    // it sees the predictions it is supposed to serve (STG-15.1).
    var plan = deps
      .reader()
      .verificationPlan(
        envelope("plan", parsed.verificationIndicators(), unknowns),
        withHypotheses.withPredictions(predictions)
      )
      .value();
    return new ValidatedReasoning(
      parsed.summary(),
      variables,
      mechanisms,
      stakeholders,
      firstOrder,
      secondOrder,
      hypotheses,
      alternatives,
      counterArguments,
      falsification,
      signals,
      plan,
      predictions,
      unknowns,
      false
    );
  }

  private ValidatedReasoning validateSnapshotReasoning(
    String raw,
    PipelineState state,
    RuntimeException narrowFailure
  ) {
    var snapshot = SnapshotEnvelope.tryParse(raw, deps.json());
    if (snapshot.isEmpty()) throw StageOutputException.schema(
      "stage 2: the reply is neither the reasoning contract nor a readable AnalysisResult snapshot" +
      (narrowFailure == null || narrowFailure.getMessage() == null
          ? ""
          : " (reasoning contract: " + narrowFailure.getMessage() + ")")
    );
    var envelope = snapshot.get();
    var unknowns = envelope.unknowns();
    return new ValidatedReasoning(
      envelope.summary(),
      deps.reader().variables(raw, state).value(),
      deps.reader().mechanisms(raw, state).value(),
      deps.reader().stakeholders(raw, state).value(),
      deps.reader().firstOrderEffects(raw, state).value(),
      deps.reader().secondOrderEffects(raw, state).value(),
      deps.reader().hypotheses(raw, state).value(),
      deps.reader().alternativeExplanations(raw, state).value(),
      deps.reader().counterArguments(raw, state).value(),
      deps.reader().falsificationConditions(raw, state).value(),
      deps.reader().corroboratingSignals(raw, state).value(),
      deps.reader().verificationPlan(raw, state).value(),
      deps.reader().predictions(raw, state).value(),
      unknowns,
      envelope.demo()
    );
  }

  /** The narrowed envelope shape the existing reader for {@code key} expects. */
  private String envelope(String key, List<?> values, List<ProtocolModel.Unknown> unknowns) {
    var body = new LinkedHashMap<String, Object>();
    body.put(key, values);
    body.put("unknowns", unknowns);
    return deps.json().write(body);
  }

  private static void require(Object value, String field) {
    if (value == null) throw StageOutputException.schema(
      "stage 2: required artifact '" +
      field +
      "' is absent; an unevaluated stage must emit an explicit UNKNOWN rather than omit the field (PR-05)"
    );
  }

  // ---- folding a validated stage 2 into the state ---------------------------

  private PipelineState applyReasoning(
    PipelineState state,
    ValidatedReasoning reasoning,
    boolean demo
  ) {
    return state
      .withSummary(reasoning.summary())
      .withVariables(reasoning.variables())
      .withMechanisms(reasoning.mechanisms())
      .withStakeholders(reasoning.stakeholders())
      .withFirstOrderEffects(reasoning.firstOrderEffects())
      .withSecondOrderEffects(reasoning.secondOrderEffects())
      .withHypotheses(reasoning.hypotheses())
      .withAlternativeExplanations(reasoning.alternatives())
      .withCounterArguments(reasoning.counterArguments())
      .withFalsificationConditions(reasoning.falsificationConditions())
      .withCorroboratingSignals(reasoning.signals())
      .withVerificationPlan(reasoning.plan())
      .withPredictions(reasoning.predictions())
      .withUnknowns(reasoning.unknowns())
      .withDemo(demo);
  }

  // ---- prompts -------------------------------------------------------------

  PromptLibrary.VersionedPrompt factsPrompt() {
    return deps.prompts().load(FACTS_PROMPT_DIR);
  }

  PromptLibrary.VersionedPrompt reasoningPrompt() {
    return deps.prompts().load(REASONING_PROMPT_DIR);
  }

  /**
   * Stage 1 prompt: the untrusted source fenced as data, the deterministic source
   * assessment, and the facts schema. The output contract is last so it is the most
   * recent thing read.
   */
  String factsPromptText(PromptLibrary.VersionedPrompt prompt, PipelineState state) {
    var text = new StringBuilder(prompt.text().trim()).append('\n');
    if (state.sourceAssessment() != null) {
      text
        .append("\nDeterministic source assessment (already decided; use it, do not restate it):\n")
        .append(deps.json().write(state.sourceAssessment()))
        .append('\n');
    }
    text
      .append("\nJSON Schema of the object your reply must match:\n")
      .append(factsSchema)
      .append('\n')
      .append("\n<untrusted_source>\n")
      .append(state.news().source().text())
      .append("\n</untrusted_source>\n")
      .append("\nOUTPUT CONTRACT (read last): reply with one JSON object and nothing else, shaped {\"facts\":[...],\"unknowns\":[...]}.")
      .append(" facts is the top level array of reported claims; no wrapper key, no markdown, no prose.")
      .append(" Every fact statement must be copied character for character from the source text,")
      .append(" and its sourceRefs[0].sourceId must be the source UUID ")
      .append(state.news().source().id())
      .append(" with startOffset/endOffset as UTF-16 code units of that exact text.");
    return text.toString();
  }

  /**
   * Stage 2 prompt: the source, the validated stage-1 facts with their ids and
   * spans, domain guidance, and the reasoning schema. The fact ids shown are the
   * only ones the reply may cite.
   */
  String reasoningPromptText(
    PromptLibrary.VersionedPrompt prompt,
    PipelineState state,
    String guidance
  ) {
    var text = new StringBuilder(prompt.text().trim()).append('\n');
    if (guidance != null && !guidance.isBlank()) {
      text.append('\n').append(guidance.trim()).append('\n');
    }
    text
      .append("\nJSON Schema of the object your reply must match:\n")
      .append(reasoningSchema)
      .append('\n')
      .append("\n<untrusted_source>\n")
      .append(state.news().source().text())
      .append("\n</untrusted_source>\n")
      .append("\nVALIDATED FACTS from stage 1 (these ids are the only fact references you may cite):\n");
    for (var fact : state.facts()) {
      var span = fact.sourceRefs().isEmpty() ? null : fact.sourceRefs().getFirst();
      text
        .append("- id=")
        .append(fact.id())
        .append(" span=")
        .append(
          span == null ? "n/a" : span.startOffset() + "-" + span.endOffset()
        )
        .append(" statement=")
        .append(fact.statement())
        .append('\n');
    }
    text.append("\nOUTPUT CONTRACT (read last): reply with one JSON object and nothing else.")
      .append(" It carries summary, variables, mechanisms, stakeholders, firstOrderEffects, secondOrderEffects,")
      .append(" hypotheses, alternativeExplanations, counterArguments, falsificationConditions, corroboratingSignals,")
      .append(" verificationIndicators, predictions and unknowns. Every array must be present: use [] only for a stage")
      .append(" you evaluated and found empty, and emit an explicit UNKNOWN item when the input does not support an")
      .append(" artifact. Cite only the fact ids listed above; never invent a fact, a source or a span, and never state")
      .append(" a confidence score. predictions need expectedBy and verificationCriteria. No wrapper key, no markdown.");
    return text.toString();
  }
}
