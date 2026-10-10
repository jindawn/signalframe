package com.signalframe.analysis.support;

import com.signalframe.ai.application.*;
import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.AnalysisPipeline;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.analysis.application.strategies.AiDomainStrategy;
import com.signalframe.analysis.application.strategies.BusinessDomainStrategy;
import com.signalframe.analysis.application.strategies.ConsumerDomainStrategy;
import com.signalframe.analysis.application.strategies.DefaultDomainStrategy;
import com.signalframe.analysis.application.strategies.EmploymentDomainStrategy;
import com.signalframe.analysis.application.strategies.EnergyDomainStrategy;
import com.signalframe.analysis.application.strategies.FinanceDomainStrategy;
import com.signalframe.analysis.application.strategies.GeopoliticsDomainStrategy;
import com.signalframe.analysis.application.strategies.HealthcareDomainStrategy;
import com.signalframe.analysis.application.strategies.MacroDomainStrategy;
import com.signalframe.analysis.application.strategies.PolicyDomainStrategy;
import com.signalframe.analysis.application.strategies.RealEstateDomainStrategy;
import com.signalframe.analysis.application.strategies.TechnologyAnalysisStrategy;
import com.signalframe.analysis.domain.AnalysisRepository;
import com.signalframe.shared.confidence.ConfidenceRubric;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.analysis.domain.NewsValueScorer;
import com.signalframe.analysis.application.DefaultNewsValueScorer;
import com.signalframe.analysis.application.steps.ProtocolStage;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.RoutingProfilePolicy;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.shared.JsonCodec;
import jakarta.validation.Validation;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Test fixtures for the protocol pipeline.
 *
 * <p>Everything here is in-memory: no network, no clock dependency, no provider
 * credential. The only real collaborators are the project's own pure components
 * (JSON codec, validator, prompt catalog) and optionally the offline reference
 * adapter, so a stage can be exercised exactly as production runs it.
 */
public final class PipelineFixtures {

  public static final String SOURCE_TEXT =
    "某公司发布新一代推理服务，价格下降 30%。该公司同时警告供应链仍存在限制。";

  private PipelineFixtures() {}

  public static NewsItem news() {
    return news(SOURCE_TEXT);
  }

  public static NewsItem news(String text) {
    Instant now = Instant.now();
    return new NewsItem(
      UUID.randomUUID(),
      "Pipeline fixture news",
      new Source(
        UUID.randomUUID(),
        null,
        text,
        "PASTED",
        null,
        now
      ),
      DomainType.OTHER,
      now
    );
  }

  public static JsonCodec json() {
    return new JsonCodec();
  }

  public static jakarta.validation.Validator validator() {
    return Validation.buildDefaultValidatorFactory().getValidator();
  }

  public static AnalysisResultValidator gateA(JsonCodec json) {
    return new AnalysisResultValidator(json, validator());
  }

  public static ModelProfile profile() {
    return new ModelProfile(
      "mock",
      "mock-v1",
      "https://example.invalid",
      "AI_TEST_API_KEY",
      0.2,
      2048,
      20,
      true,
      false,
      true
    );
  }

  public static ModelRouter router() {
    return new ModelRouter(
      new AiProperties(
        "analysis.fast",
        Map.of(
          ModelPurpose.SYNTHESIS,
          "analysis.fast",
          ModelPurpose.FACT_EXTRACTION,
          "analysis.fast",
          ModelPurpose.DEEP_ANALYSIS,
          "analysis.fast",
          ModelPurpose.COUNTER_ARGUMENT,
          "analysis.fast"
        ),
        Map.of("analysis.fast", profile())
      ),
      validator(),
      new RoutingProfilePolicy()
    );
  }

  /**
   * Every domain strategy the application context injects, in a fixed order.
   *
   * <p>The pipeline never selects by list order: {@code DomainStrategyResolver}
   * decides by specificity and rejects a duplicate top specificity (DS-04), so
   * this list is deliberately built in a different order than the strategy
   * package and still yields the same selection.
   */
  public static List<DomainAnalysisStrategy> strategies() {
    return List.of(
      new DefaultDomainStrategy(),
      new ConsumerDomainStrategy(),
      new AiDomainStrategy(),
      new BusinessDomainStrategy(),
      new EmploymentDomainStrategy(),
      new EnergyDomainStrategy(),
      new FinanceDomainStrategy(),
      new GeopoliticsDomainStrategy(),
      new HealthcareDomainStrategy(),
      new MacroDomainStrategy(),
      new PolicyDomainStrategy(),
      new RealEstateDomainStrategy(),
      new TechnologyAnalysisStrategy()
    );
  }

  public static StageResponseReader reader(JsonCodec json) {
    return new StageResponseReader(json);
  }

  public static PromptLibrary promptLibrary() {
    return new PromptLibrary();
  }

  public static StagePrompts stagePrompts(JsonCodec json) {
    return new StagePrompts(json);
  }

  public static StageModelClient client(
    ModelGateway gateway,
    RecordingRuns runs
  ) {
    return new StageModelClient(
      router(),
      gateway,
      runs,
      new ModelRetryPolicy(2, 1, Duration.ZERO)
    );
  }

  public static ModelAnalysisService modelService(
    ModelGateway gateway,
    RecordingRuns runs
  ) {
    var json = json();
    return new ModelAnalysisService(
      router(),
      gateway,
      runs,
      gateA(json),
      new PromptCatalog(),
      json,
      new ModelRetryPolicy(2, 1, Duration.ZERO)
    );
  }

  /** Every protocol stage, in the pipeline's fixed order. */
  public static List<ProtocolStage> stages(
    AnalysisPipelineMode mode,
    ModelGateway gateway,
    RecordingRuns runs,
    AnalysisRepository analyses
  ) {
    var json = json();
    var reader = reader(json);
    var prompts = promptLibrary();
    var stagePrompts = stagePrompts(json);
    var client = client(gateway, runs);
    NewsValueScorer scorer = new DefaultNewsValueScorer();
    var strategies = strategies();
    var rubric = ConfidenceRubric.deterministic();
    return List.of(
      new SourceAssessmentStage(),
      new SnapshotDraftStage(
        modelService(gateway, runs),
        json,
        prompts,
        strategies,
        mode
      ),
      new FactExtractionStage(client, prompts, reader, stagePrompts, mode),
      new DomainClassificationStage(),
      new NewsScoringStage(scorer),
      new VariableAnalysisStage(client, prompts, reader, stagePrompts, mode),
      new MechanismAnalysisStage(client, prompts, reader, stagePrompts, mode),
      new FirstOrderEffectStage(client, prompts, reader, stagePrompts, mode),
      new SecondOrderEffectStage(client, prompts, reader, stagePrompts, mode),
      new StakeholderAnalysisStage(client, prompts, reader, stagePrompts, mode),
      new HypothesisGenerationStage(
        client,
        prompts,
        reader,
        stagePrompts,
        mode
      ),
      new AlternativeExplanationStage(
        client,
        prompts,
        reader,
        stagePrompts,
        mode
      ),
      new CounterArgumentStage(client, prompts, reader, stagePrompts, mode),
      new FalsificationConditionStage(
        client,
        prompts,
        reader,
        stagePrompts,
        mode
      ),
      new CorroboratingSignalStage(
        client,
        prompts,
        reader,
        stagePrompts,
        mode
      ),
      new PredictionGenerationStage(
        client,
        prompts,
        reader,
        stagePrompts,
        mode
      ),
      new VerificationPlanStage(
        client,
        prompts,
        reader,
        stagePrompts,
        mode
      ),
      new EpistemicGateStage(),
      new ConfidenceAssessmentStage(rubric),
      new SynthesisStage(
        modelService(gateway, runs),
        prompts,
        strategies,
        mode
      ),
      new ProjectionStage(gateA(json), json, rubric, strategies),
      new PersistStage(analyses)
    );
  }

  public static AnalysisPipeline pipeline(
    AnalysisPipelineMode mode,
    ModelGateway gateway,
    RecordingRuns runs,
    InMemoryJobs jobs,
    InMemoryAnalyses analyses
  ) {
    return new AnalysisPipeline(
      jobs,
      new AnalysisStages(stages(mode, gateway, runs, analyses))
    );
  }

  public static com.signalframe.analysis.application.PipelineContext context(
    InMemoryJobs jobs,
    NewsItem news
  ) {
    var job = jobs.create(news.id(), "fixture");
    return new com.signalframe.analysis.application.PipelineContext(
      job.id(),
      "fixture",
      news
    );
  }

  /** Job repository that keeps the durable step sequence in memory. */
  public static final class InMemoryJobs implements JobRepository {

    private final Map<UUID, AnalysisJob> jobs = new LinkedHashMap<>();
    private final List<String> steps = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @Override
    public AnalysisJob create(UUID newsId, String correlationId) {
      Instant now = Instant.now();
      var job = new AnalysisJob(
        UUID.randomUUID(),
        newsId,
        JobStatus.QUEUED,
        null,
        null,
        correlationId,
        now,
        now,
        List.of(new JobEvent(1L, JobStatus.QUEUED, "Queued", "queued", now))
      );
      jobs.put(job.id(), job);
      return job;
    }

    @Override
    public Optional<AnalysisJob> find(UUID id) {
      return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public boolean claim(UUID id) {
      return true;
    }

    @Override
    public void progress(UUID id, JobStatus status, String step, String message) {
      steps.add(step);
    }

    @Override
    public void fail(UUID id, String message) {
      failures.add(message);
    }

    @Override
    public void recoverInterrupted() {}

    public List<String> steps() {
      return List.copyOf(steps);
    }

    public List<String> failures() {
      return List.copyOf(failures);
    }
  }

  /** Analysis repository that captures the persisted snapshot. */
  public static final class InMemoryAnalyses implements AnalysisRepository {

    private final List<Analysis> completed = new ArrayList<>();

    @Override
    public void complete(Analysis analysis) {
      completed.add(analysis);
    }

    @Override
    public Optional<Analysis> find(UUID id) {
      return completed.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    @Override
    public List<Analysis> forNews(UUID newsId) {
      return completed.stream().filter(a -> a.newsId().equals(newsId)).toList();
    }

    public List<Analysis> completed() {
      return List.copyOf(completed);
    }
  }

  /** Model run repository that records every audited attempt. */
  public static final class RecordingRuns implements ModelRunRepository {

    private final List<ModelRun> runs = new ArrayList<>();

    @Override
    public void save(ModelRun run) {
      runs.add(run);
    }

    @Override
    public List<ModelRun> recent(UUID jobId) {
      return runs.stream().filter(r -> r.jobId().equals(jobId)).toList();
    }

    public List<ModelRun> all() {
      return List.copyOf(runs);
    }
  }

  /** Gateway driven by a test-supplied responder; records every request. */
  public static final class ScriptedGateway implements ModelGateway {

    private final Function<ModelRequest, String> responder;
    private final List<ModelRequest> requests = new ArrayList<>();

    public ScriptedGateway(Function<ModelRequest, String> responder) {
      this.responder = responder;
    }

    @Override
    public ModelResponse call(ModelRequest request) {
      requests.add(request);
      String content = responder.apply(request);
      return new ModelResponse(
        content,
        new ModelUsage(1L, 1L, 2L, null),
        "stub",
        "stub-model"
      );
    }

    public List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    public long countPurpose(ModelPurpose purpose) {
      return requests.stream().filter(r -> r.purpose() == purpose).count();
    }
  }
}
