package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.ModelRequest;
import com.signalframe.analysis.application.steps.AnalysisPipelineMode;
import com.signalframe.analysis.application.strategies.*;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.analysis.support.PipelineFixtures.*;
import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.CausalLink;
import com.signalframe.contract.ConfidenceMethod;
import com.signalframe.contract.DomainType;
import com.signalframe.contract.ModelPurpose;
import com.signalframe.contract.NewsItem;
import com.signalframe.contract.Variable;
import com.signalframe.infrastructure.ai.providers.MockModelGateway;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Wave 2A integration gate: TASK-03's pipeline and TASK-05's domain strategies
 * must work as one system, not as two independently tested halves.
 *
 * <p>Every case here is asserted through the real {@link
 * com.signalframe.analysis.application.AnalysisPipeline}, the real stage list, the
 * real gates and the real projection. Nothing asserts the resolver in isolation:
 * the point of the gate is that the pipeline selects a strategy through {@link
 * DomainStrategyResolver} and that the selected dictionary's recommendations — and
 * only its recommendations — reach the run.
 *
 * <p>References: ANALYSIS_PROTOCOL_V0_1 §2/§4/§5, EPISTEMIC_TYPES EP-01…EP-12,
 * CONFIDENCE_MODEL_V0_1 CF-01…CF-08, DOMAIN_STRATEGY_CONTRACT DS-01…DS-12.
 */
class Wave2aIntegrationTest {

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

  private static final String AI_NEWS =
    "OpenAI shipped a new AI model and cut inference prices by 30 percent.";
  private static final String TECH_NEWS =
    "The new GPU semiconductor chip enters volume production while cloud software vendors adopt the platform.";
  private static final String FINANCE_NEWS =
    "The central bank raised the interest rate and stock valuations fell sharply.";
  private static final String UNCLASSIFIED_NEWS =
    "A local community held a harvest festival last weekend.";

  /** One completed run, with everything a gate assertion needs. */
  private record Outcome(
    InMemoryJobs jobs,
    InMemoryAnalyses analyses,
    RecordingRuns runs,
    ScriptedGateway gateway,
    JsonCodec json,
    NewsItem news
  ) {
    AnalysisResult snapshot() {
      assertEquals(
        1,
        analyses.completed().size(),
        "exactly one analysis is persisted"
      );
      return analyses.completed().getFirst().result();
    }
  }

  private Outcome runDraft(String sourceText) {
    return run(
      AnalysisPipelineMode.Mode.DRAFT,
      sourceText,
      mockRecordingGateway()
    );
  }

  private Outcome run(
    AnalysisPipelineMode.Mode mode,
    String sourceText,
    ScriptedGateway gateway
  ) {
    return run(mode, PipelineFixtures.news(sourceText), gateway);
  }

  private Outcome run(
    AnalysisPipelineMode.Mode mode,
    NewsItem news,
    ScriptedGateway gateway
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
    return new Outcome(jobs, analyses, runs, gateway, json, news);
  }

  /** Records every request while answering exactly like the offline adapter. */
  private static ScriptedGateway mockRecordingGateway() {
    var json = PipelineFixtures.json();
    var mock = new MockModelGateway(json);
    return new ScriptedGateway(request -> mock.call(request).content());
  }

  private static DomainAnalysisStrategy selected(DomainType domain) {
    return DomainStrategyResolver.resolve(domain, PipelineFixtures.strategies());
  }

  /** The strategy id the pipeline records in the snapshot's provenance (PR-16). */
  private static String recordedStrategyId(AnalysisResult snapshot) {
    var provenance = snapshot.provenance();
    assertNotNull(provenance, "provenance must name the selected strategy (PR-16)");
    String id = provenance.domainStrategyId();
    assertNotNull(id, "provenance must name the selected strategy");
    assertFalse(id.isBlank(), "provenance must name the selected strategy");
    return id;
  }

  // ---- A/B/C/D: classification -> resolver -> strategy ---------------------

  @Test
  void aiNewsSelectsTheAiStrategyThroughThePipeline() {
    var snapshot = runDraft(AI_NEWS).snapshot();
    assertEquals(
      "AI/AiDomainStrategy",
      recordedStrategyId(snapshot),
      "the pipeline must record the resolver's choice for AI input"
    );
    assertInstanceOf(AiDomainStrategy.class, selected(DomainType.AI));
  }

  @Test
  void technologyNewsSelectsTheTechnologyStrategyThroughThePipeline() {
    var snapshot = runDraft(TECH_NEWS).snapshot();
    assertEquals(
      "TECH/TechnologyAnalysisStrategy",
      recordedStrategyId(snapshot)
    );
    assertInstanceOf(
      TechnologyAnalysisStrategy.class,
      selected(DomainType.TECH)
    );
  }

  @Test
  void financeNewsSelectsTheFinanceStrategyThroughThePipeline() {
    var snapshot = runDraft(FINANCE_NEWS).snapshot();
    assertEquals(
      "FINANCE/FinanceDomainStrategy",
      recordedStrategyId(snapshot)
    );
    assertInstanceOf(FinanceDomainStrategy.class, selected(DomainType.FINANCE));
  }

  @Test
  void unclassifiedNewsFallsBackToTheDefaultStrategy() {
    var snapshot = runDraft(UNCLASSIFIED_NEWS).snapshot();
    assertEquals(
      "OTHER/DefaultDomainStrategy",
      recordedStrategyId(snapshot)
    );
    var fallback = selected(DomainType.OTHER);
    assertInstanceOf(DefaultDomainStrategy.class, fallback);
    assertEquals(0, fallback.specificity());
    for (DomainType domain : DomainType.values()) assertTrue(
      fallback.supports(domain),
      "the fallback must support " + domain
    );
  }

  // ---- E: exactly one top-specificity strategy per domain ------------------

  @Test
  void everyDomainResolvesToExactlyOneTopSpecificityStrategy() {
    var strategies = PipelineFixtures.strategies();
    assertDoesNotThrow(
      () -> DomainStrategyResolver.validateConfiguration(strategies),
      "DS-02/DS-03/DS-04: the strategy set must be unambiguous at startup"
    );
    for (DomainType domain : DomainType.values()) {
      var candidates = DomainStrategyResolver.allFor(domain, strategies);
      assertFalse(
        candidates.isEmpty(),
        "no strategy claims " + domain + " (DS-05)"
      );
      int max = candidates
        .stream()
        .mapToInt(DomainAnalysisStrategy::specificity)
        .max()
        .orElseThrow();
      var top = candidates.stream().filter(s -> s.specificity() == max).toList();
      assertEquals(
        1,
        top.size(),
        "exactly one top-specificity strategy is required for " + domain
      );
      assertSame(
        top.getFirst(),
        DomainStrategyResolver.resolve(domain, strategies)
      );
      assertTrue(
        top.getFirst().supports(domain),
        "the selected strategy must claim " + domain
      );
    }
  }

  @Test
  void aiIsNotConsumedByBothTheAiAndTechnologyDictionaries() {
    var strategies = PipelineFixtures.strategies();
    assertInstanceOf(AiDomainStrategy.class, selected(DomainType.AI));
    var technology = strategies
      .stream()
      .filter(TechnologyAnalysisStrategy.class::isInstance)
      .findFirst()
      .orElseThrow();
    assertFalse(
      technology.supports(DomainType.AI),
      "TECH must not also claim AI, or the choice would depend on tie-breaking"
    );
    assertNotSame(
      DomainStrategyResolver.resolve(DomainType.AI, strategies),
      DomainStrategyResolver.resolve(DomainType.TECH, strategies)
    );
  }

  // ---- F: the selected strategy's guidance reaches the model request -------

  @Test
  void strategyGuidanceReachesTheModelRequest() {
    var outcome = runDraft(AI_NEWS);
    assertFalse(
      outcome.gateway().requests().isEmpty(),
      "the run must issue a model call"
    );
    String prompt = outcome.gateway().requests().getFirst().prompt();
    String guidance = selected(DomainType.AI).guidance();
    assertFalse(guidance.isBlank(), "guidance must not be empty (DS-05)");
    assertTrue(
      guidance.length() <= DomainStrategySpecRenderer.MAX_GUIDANCE_LENGTH,
      "guidance must stay within the bounded rendering (DS-07)"
    );
    assertTrue(
      prompt.contains(guidance),
      "the AI dictionary's guidance must reach the model request verbatim"
    );
    assertTrue(
      prompt.contains("deployed capability versus benchmark capability"),
      "the request must carry the AI dictionary's variables, not generic advice"
    );
  }

  @Test
  void aDifferentDomainSendsDifferentGuidance() {
    String aiPrompt = runDraft(AI_NEWS).gateway().requests().getFirst().prompt();
    String financePrompt = runDraft(FINANCE_NEWS)
      .gateway()
      .requests()
      .getFirst()
      .prompt();
    assertNotEquals(
      aiPrompt,
      financePrompt,
      "two domains must not send identical strategy guidance"
    );
    assertTrue(
      financePrompt.contains(selected(DomainType.FINANCE).guidance()),
      "the FINANCE dictionary's guidance must reach its request"
    );
  }

  // ---- G: a template is never promoted into a FACT -------------------------

  @Test
  void strategyTemplatesNeverBecomeFacts() {
    var outcome = runDraft(AI_NEWS);
    var snapshot = outcome.snapshot();
    var strategy = selected(DomainType.AI);
    assertFalse(snapshot.facts().isEmpty(), "STG-03: an analysis needs facts");
    var templateText = new ArrayList<String>();
    strategy.spec().variables().forEach(v -> templateText.add(v.name()));
    strategy
      .spec()
      .mechanisms()
      .forEach(m -> {
        templateText.add(m.fromConcept());
        templateText.add(m.toConcept());
        templateText.add(m.explanationPattern());
      });
    strategy.spec().metrics().forEach(m -> templateText.add(m.whatToCheck()));
    for (var fact : snapshot.facts()) {
      assertTrue(
        outcome.news().source().text().contains(fact.statement()),
        "a FACT must remain a verbatim source span (PR-07), not strategy text"
      );
      for (String template : templateText) assertFalse(
        fact.statement().contains(template),
        "a strategy recommendation must never surface as a FACT: " + template
      );
    }
  }

  // ---- H: mechanism templates start SPECULATIVE ---------------------------

  @Test
  void mechanismTemplatesStartSpeculativeAndCannotBeRaisedByAStrategy() {
    for (DomainType domain : DomainType.values()) {
      var strategy = selected(domain);
      for (var template : strategy.spec().mechanisms()) assertEquals(
        MechanismTemplate.SPECULATIVE,
        template.startingSupportLevel(),
        "DS-08: every template for " + domain + " starts SPECULATIVE"
      );
    }
    assertThrows(
      IllegalArgumentException.class,
      () -> new MechanismTemplate("a", "b", "c", "d", "SUPPORTED"),
      "a strategy must not be able to construct a supported mechanism"
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> new MechanismTemplate("a", "b", "c", "d", "PLAUSIBLE")
    );
  }

  @Test
  void noPersistedMechanismIsSupportedWithoutFactRefs() {
    var snapshot = runDraft(AI_NEWS).snapshot();
    for (CausalLink mechanism : snapshot.mechanisms()) {
      if ("SUPPORTED".equals(mechanism.supportLevel())) {
        assertFalse(
          mechanism.factRefs().isEmpty(),
          "STG-05: SUPPORTED requires fact refs on both ends, never template existence"
        );
      }
    }
  }

  // ---- I/L: happy path through the real mock gateway -----------------------

  @Test
  void theMockGatewayStillDrivesTheWholePipelineInBothTopologies() {
    for (var mode : AnalysisPipelineMode.Mode.values()) {
      var json = PipelineFixtures.json();
      var jobs = new InMemoryJobs();
      var analyses = new InMemoryAnalyses();
      var runs = new RecordingRuns();
      var pipeline = PipelineFixtures.pipeline(
        new AnalysisPipelineMode(mode),
        new MockModelGateway(json),
        runs,
        jobs,
        analyses
      );
      pipeline.run(
        PipelineFixtures.context(jobs, PipelineFixtures.news(TECH_NEWS))
      );
      assertEquals(
        DURABLE_STEPS,
        jobs.steps(),
        "the fixed 14 durable steps must run in order in " + mode
      );
      assertEquals(
        1,
        analyses.completed().size(),
        "one snapshot per run in " + mode
      );
      assertTrue(
        analyses.completed().getFirst().result().demo(),
        "offline output must stay flagged as demo (PR-04)"
      );
      assertFalse(runs.all().isEmpty(), "every provider attempt must be audited");
    }
  }

  // ---- J: an unsupported inference becomes UNKNOWN -------------------------

  @Test
  @SuppressWarnings("unchecked")
  void anUnsupportedDirectionalInferenceIsDemotedToUnknown() {
    var json = PipelineFixtures.json();
    var news = PipelineFixtures.news(AI_NEWS);
    var mock = new MockModelGateway(json);
    String draft = mock
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
    Map<String, Object> snapshot = json.read(draft, Map.class);
    List<Map<String, Object>> variables = (List<Map<String, Object>>) snapshot.get(
      "variables"
    );
    // A stated direction with no span, so no fact inside the snapshot can support it.
    variables.getFirst().put("direction", "UP");
    variables.getFirst().put("sourceRefs", List.of());
    String unsupported = json.write(snapshot);

    var outcome = run(
      AnalysisPipelineMode.Mode.DRAFT,
      news,
      new ScriptedGateway(request -> unsupported)
    );
    var persisted = outcome.snapshot();
    for (Variable variable : persisted.variables()) {
      if (!"UNKNOWN".equals(variable.direction())) assertFalse(
        variable.factRefs().isEmpty(),
        "EP-01/EP-05: a direction requires a resolvable fact reference"
      );
    }
    assertTrue(
      persisted
        .unknowns()
        .stream()
        .anyMatch(u ->
          u.reasoning().contains("unsupported inference") ||
          u.statement().contains("Dropped variables")
        ),
      "EP-01: the unsupported inference must be reported as UNKNOWN, never kept"
    );
    assertFalse(
      persisted.facts().isEmpty(),
      "STG-03: the fact set is independent of the demotion"
    );
  }

  @Test
  void aSingleSourceSnapshotAlwaysStatesItsUnknowns() {
    var snapshot = runDraft(UNCLASSIFIED_NEWS).snapshot();
    assertFalse(
      snapshot.unknowns().isEmpty(),
      "STG-16.2: empty unknowns on a single-source snapshot is itself a defect"
    );
  }

  // ---- K: confidence stays rubric-derived and deterministic ----------------

  @Test
  void confidenceIsTheRubricForEveryDomainStrategy() {
    for (String news : List.of(
      AI_NEWS,
      TECH_NEWS,
      FINANCE_NEWS,
      UNCLASSIFIED_NEWS
    )) {
      var assessment = runDraft(news).snapshot().confidenceAssessment();
      assertFalse(
        assessment.isProbability(),
        "CF-01: the score is never a probability of truth"
      );
      assertEquals(
        ConfidenceMethod.RUBRIC,
        assessment.method(),
        "CF-08: the stored assessment must be rubric-scored"
      );
      assertEquals("0.1", assessment.rubricVersion());
      assertTrue(assessment.score() >= 0 && assessment.score() <= 100);
    }
  }

  @Test
  void theSameInputScoresIdenticallyAcrossRuns() {
    var first = runDraft(AI_NEWS).snapshot().confidenceAssessment();
    var second = runDraft(AI_NEWS).snapshot().confidenceAssessment();
    assertEquals(first.score(), second.score(), "CF-02/CF-03: the rubric is pure");
    assertEquals(first.reason(), second.reason());
  }
}
