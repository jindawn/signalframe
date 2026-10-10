package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.AnalysisPipeline;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.analysis.support.PipelineFixtures.*;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.MockModelGateway;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pipeline behaviour over both conformant call topologies.
 *
 * <p>Draft mode preserves the foundation runtime contract (one audited provider
 * run per analysis) while every protocol stage still extracts, validates and
 * ref-links its own typed artifact. Staged mode gives each model-backed stage its
 * own versioned prompt, purpose and ModelRun rows. Both produce the same gates,
 * the same deterministic confidence and the same snapshot shape.
 */
class ProtocolPipelineTest {

  private static final List<String> DURABLE_STEPS = List.of(
    "NormalizeInput",
    "ExtractFacts",
    "ClassifyDomain",
    "ScoreNews",
    "ExtractVariables",
    "AnalyzeMechanism",
    "AnalyzeStakeholders",
    "GenerateHypotheses",
    "GenerateCounterArguments",
    "GenerateFalsificationConditions",
    "GenerateCorroboratingSignals",
    "GenerateVerificationPlan",
    "SynthesizeAnalysis",
    "Persist"
  );

  private static final List<String> STAGE_PROMPT_VERSIONS = List.of(
    "fact-extraction-v1",
    "variable-analysis-v1",
    "mechanism-analysis-v1",
    "first-order-effects-v1",
    "second-order-effects-v1",
    "stakeholder-analysis-v1",
    "hypothesis-generation-v1",
    "alternative-explanations-v1",
    "counter-argument-v1",
    "falsification-conditions-v1",
    "corroborating-signals-v1",
    "prediction-generation-v1",
    "verification-plan-v1"
  );

  private record Run(AnalysisPipeline pipeline, InMemoryJobs jobs, InMemoryAnalyses analyses, RecordingRuns runs, JsonCodec json) {}

  private Run run(
    AnalysisPipelineMode.Mode mode,
    ModelGateway gateway,
    NewsItem news
  ) {
    var json = PipelineFixtures.json();
    var jobs = new InMemoryJobs();
    var analyses = new InMemoryAnalyses();
    var runs = new RecordingRuns();
    var pipeline = PipelineFixtures.pipeline(
      new AnalysisPipelineMode(mode),
      gateway,
      runs,
      jobs,
      analyses
    );
    pipeline.run(PipelineFixtures.context(jobs, news));
    return new Run(pipeline, jobs, analyses, runs, json);
  }

  private Run runDraft(ModelGateway gateway, NewsItem news) {
    return run(AnalysisPipelineMode.Mode.DRAFT, gateway, news);
  }

  private Run runStaged(ModelGateway gateway, NewsItem news) {
    return run(AnalysisPipelineMode.Mode.STAGED, gateway, news);
  }

  private String mockSnapshot(JsonCodec json, NewsItem news) {
    return new MockModelGateway(json)
      .call(
        new ModelRequest(
          UUID.randomUUID(),
          ModelPurpose.SYNTHESIS,
          "synthesis-v1",
          "",
          news,
          PipelineFixtures.profile(),
          false
        )
      )
      .content();
  }

  // ---- draft topology -----------------------------------------------------

  @Test
  void draftTopologyCompletesWithExactlyOneAuditedProviderRun() {
    var news = PipelineFixtures.news();
    var outcome = runDraft(new MockModelGateway(PipelineFixtures.json()), news);

    assertEquals(1, outcome.analyses().completed().size());
    assertEquals(DURABLE_STEPS, outcome.jobs().steps());
    assertEquals(1, outcome.runs().all().size());
    var run = outcome.runs().all().getFirst();
    assertEquals("synthesis-v1", run.promptVersion());
    assertEquals(ModelPurpose.SYNTHESIS, run.purpose());
    assertEquals("SUCCEEDED", run.status());

    var snapshot = outcome.analyses().completed().getFirst().result();
    assertTrue(snapshot.demo());
    assertFalse(snapshot.modifiesExistingHypotheses());
    assertFalse(snapshot.confidenceAssessment().isProbability());
    assertFalse(snapshot.facts().isEmpty());
    for (var fact : snapshot.facts()) {
      assertEquals(ClaimType.FACT, fact.type());
      assertFalse(fact.sourceRefs().isEmpty());
      for (var ref : fact.sourceRefs()) {
        assertEquals(news.source().id(), ref.sourceId());
        assertEquals(
          news.source().text().substring(ref.startOffset(), ref.endOffset()),
          ref.quote()
        );
      }
      assertEquals("REPORTED", fact.verificationStatus());
    }
    assertFalse(snapshot.hypotheses().isEmpty());
    for (var hypothesis : snapshot.hypotheses()) {
      assertEquals(ClaimType.HYPOTHESIS, hypothesis.type());
      assertEquals(HypothesisStatus.OPEN, hypothesis.status());
      assertFalse(hypothesis.supportingFactRefs().isEmpty());
      assertFalse(hypothesis.falsificationConditions().isEmpty());
      assertTrue(hypothesis.confidenceReason().contains("method=RUBRIC"));
    }
    assertFalse(snapshot.unknowns().isEmpty());
    for (var unknown : snapshot.unknowns()) {
      assertNotEquals(ClaimType.FACT, unknown.type());
      assertTrue(unknown.reasoning().contains("UNKNOWN."));
    }
  }

  @Test
  void theStoredConfidenceIsTheRubricAndCarriesItsProvenance() {
    var outcome = runDraft(
      new MockModelGateway(PipelineFixtures.json()),
      PipelineFixtures.news()
    );
    var snapshot = outcome.analyses().completed().getFirst().result();
    var assessment = snapshot.confidenceAssessment();
    // SCH-09/SCH-13: the rubric state and the run provenance are structured
    // fields now, so the assertion reads them instead of parsing free text.
    assertEquals(ConfidenceMethod.RUBRIC, assessment.method());
    assertEquals("0.1", assessment.rubricVersion());
    assertFalse(assessment.dimensions().isEmpty());
    assertFalse(assessment.isProbability());
    assertTrue(assessment.reason().contains("RUBRIC 0.1"));
    assertTrue(assessment.reason().contains("not a probability of truth"));
    assertEquals("0.1", snapshot.protocolVersion());
    assertNotNull(snapshot.provenance());
    assertFalse(snapshot.provenance().domainStrategyId().isBlank());
    assertEquals(
      List.of("synthesis-v1"),
      snapshot.provenance().promptVersions()
    );
    assertTrue(assessment.score() >= 0 && assessment.score() <= 100);
  }

  @Test
  void confidenceIsDeterministicForIdenticalInput() {
    var first = runDraft(
      new MockModelGateway(PipelineFixtures.json()),
      PipelineFixtures.news(PipelineFixtures.SOURCE_TEXT)
    );
    var second = runDraft(
      new MockModelGateway(PipelineFixtures.json()),
      PipelineFixtures.news(PipelineFixtures.SOURCE_TEXT)
    );
    var a = first.analyses().completed().getFirst().result().confidenceAssessment();
    var b = second
      .analyses()
      .completed()
      .getFirst()
      .result()
      .confidenceAssessment();
    assertEquals(a.score(), b.score());
    assertEquals(a.reason(), b.reason());
  }

  // ---- staged topology ----------------------------------------------------

  @Test
  void stagedTopologyRecordsOneAuditedModelRunPerStage() {
    var outcome = runStaged(
      new MockModelGateway(PipelineFixtures.json()),
      PipelineFixtures.news()
    );

    assertEquals(1, outcome.analyses().completed().size());
    var versions = outcome
      .runs()
      .all()
      .stream()
      .map(ModelRun::promptVersion)
      .toList();
    assertEquals(14, versions.size(), "thirteen stage runs plus one synthesis run");
    for (var expected : STAGE_PROMPT_VERSIONS) assertTrue(
      versions.contains(expected),
      "missing audit row for " + expected
    );
    assertTrue(versions.contains("synthesis-v1"));
    assertTrue(
      outcome.runs().all().stream().allMatch(r -> "SUCCEEDED".equals(r.status()))
    );
    var purposes = outcome
      .runs()
      .all()
      .stream()
      .map(ModelRun::purpose)
      .toList();
    assertTrue(purposes.contains(ModelPurpose.FACT_EXTRACTION));
    assertTrue(purposes.contains(ModelPurpose.DEEP_ANALYSIS));
    assertTrue(purposes.contains(ModelPurpose.COUNTER_ARGUMENT));
    assertTrue(purposes.contains(ModelPurpose.SYNTHESIS));
  }

  @Test
  void stagedTopologyUsesTheStageTypedEnvelopeWhenTheModelReturnsOne() {
    var json = PipelineFixtures.json();
    var news = PipelineFixtures.news();
    String quote = news.source().text();
    String factsEnvelope =
      "{\"facts\":[{\"statement\":\"" +
      quote +
      "\",\"reasoning\":\"Reported source claim, not independently verified.\"," +
      "\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() +
      "\",\"quote\":\"" +
      quote +
      "\",\"startOffset\":0,\"endOffset\":" +
      quote.length() +
      "}],\"verificationStatus\":\"REPORTED\"}]}";
    var snapshot = mockSnapshot(json, news);
    var gateway = new ScriptedGateway(request ->
      "fact-extraction-v1".equals(request.promptVersion())
        ? factsEnvelope
        : snapshot
    );

    var outcome = runStaged(gateway, news);

    var facts = outcome.analyses().completed().getFirst().result().facts();
    assertEquals(1, facts.size());
    assertEquals(quote, facts.getFirst().statement());
    var factRun = outcome
      .runs()
      .all()
      .stream()
      .filter(r -> "fact-extraction-v1".equals(r.promptVersion()))
      .findFirst()
      .orElseThrow();
    assertEquals(ModelPurpose.FACT_EXTRACTION, factRun.purpose());
    assertEquals("stub", factRun.provider());
    var factRequest = gateway
      .requests()
      .stream()
      .filter(r -> "fact-extraction-v1".equals(r.promptVersion()))
      .findFirst()
      .orElseThrow();
    assertTrue(
      factRequest.prompt().contains("UNTRUSTED SOURCE TEXT"),
      "the source text is labelled as data in every stage prompt"
    );
    assertTrue(factRequest.prompt().contains(news.source().text()));
    assertFalse(
      factRequest.repair(),
      "the first attempt is not a repair round"
    );
  }

  // ---- failures and isolation ---------------------------------------------

  @Test
  void aProviderFailureFailsThePipelineBeforeAnythingIsPersisted() {
    var gateway = new ScriptedGateway(request -> {
      throw ModelInvocationException.of(
        ModelFailure.AUTHENTICATION,
        "credential rejected"
      );
    });
    var json = PipelineFixtures.json();
    var jobs = new InMemoryJobs();
    var analyses = new InMemoryAnalyses();
    var runs = new RecordingRuns();
    var pipeline = PipelineFixtures.pipeline(
      new AnalysisPipelineMode(AnalysisPipelineMode.Mode.DRAFT),
      gateway,
      runs,
      jobs,
      analyses
    );

    // In the draft topology the provider call belongs to the existing, audited
    // analysis runtime, which classifies the failure itself; the pipeline never
    // reaches a stage. The invariant under test is that nothing is persisted and
    // the attempt is still audited.
    var failure = assertThrows(
      com.signalframe.shared.ApplicationException.class,
      () -> pipeline.run(PipelineFixtures.context(jobs, PipelineFixtures.news()))
    );

    assertEquals("AUTHENTICATION", failure.code());
    assertTrue(analyses.completed().isEmpty(), "no snapshot may be persisted");
    assertEquals(
      List.of("NormalizeInput", "ExtractFacts"),
      jobs.steps(),
      "durable steps up to the failing one still report their status"
    );
    assertFalse(runs.all().isEmpty(), "the failed attempt is still audited");
  }

  @Test
  void aMalformedDraftIsRepairedOnceAndThenFailsTheJob() {
    var gateway = new ScriptedGateway(request -> "definitely not json");
    var json = PipelineFixtures.json();
    var jobs = new InMemoryJobs();
    var analyses = new InMemoryAnalyses();
    var runs = new RecordingRuns();
    var pipeline = PipelineFixtures.pipeline(
      new AnalysisPipelineMode(AnalysisPipelineMode.Mode.DRAFT),
      gateway,
      runs,
      jobs,
      analyses
    );

    var failure = assertThrows(
      com.signalframe.shared.ApplicationException.class,
      () -> pipeline.run(PipelineFixtures.context(jobs, PipelineFixtures.news()))
    );

    assertEquals("MODEL_OUTPUT_FAILED", failure.code());
    assertEquals(2, gateway.requests().size(), "one call plus one repair round");
    assertTrue(analyses.completed().isEmpty());
  }

  @Test
  void stagesArePureFunctionsOfTheirInputState() {
    var news = PipelineFixtures.news();
    var state = PipelineState.initial(UUID.randomUUID(), "corr", news, java.time.Instant.now());

    var after = new SourceAssessmentStage().execute(state);

    assertNotNull(after.sourceAssessment());
    assertNull(state.sourceAssessment(), "the input artifact must not be mutated");
    assertNotSame(state, after);
    assertTrue(state.executedStages().isEmpty());
    assertEquals(List.of("SourceAssessment"), after.executedStages());
  }

  @Test
  void thePipelineExposesTheFixedStepNamesAndStageMapping() {
    var outcome = runDraft(
      new MockModelGateway(PipelineFixtures.json()),
      PipelineFixtures.news()
    );
    assertEquals(DURABLE_STEPS, outcome.pipeline().stepNames());
    assertEquals(
      List.of("EpistemicGate", "ConfidenceAssessment", "Synthesis", "Projection"),
      outcome.pipeline().stageNames("SynthesizeAnalysis")
    );
    assertEquals(
      List.of("SnapshotDraft", "FactExtraction"),
      outcome.pipeline().stageNames("ExtractFacts")
    );
    assertEquals(
      List.of("MechanismAnalysis", "FirstOrderEffect", "SecondOrderEffect"),
      outcome.pipeline().stageNames("AnalyzeMechanism")
    );
  }
}
