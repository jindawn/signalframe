package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Offline verification of the two-stage synthesis path.
 *
 * <p>Every scenario is driven through the real stage clients, the real
 * {@link AnalysisResultValidator}, the real readers and the real deterministic
 * rubric; only the provider is scripted. The point of the suite is that each stage
 * can fail on its own without anything being assembled or persisted, and that a
 * successful pair is assembled into a snapshot the unchanged validator accepts.
 */
class TwoStageSynthesisTest {

  static final UUID FACT_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
  static final UUID HYPOTHESIS_ID = UUID.fromString(
    "bbbbbbbb-0000-4000-8000-000000000002"
  );
  static final String NEWS_TEXT =
    "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";

  // ---- fixtures -------------------------------------------------------------

  static NewsItem news() {
    return PipelineFixtures.news(NEWS_TEXT);
  }

  static String quote(String value) {
    return PipelineFixtures.json().write(value);
  }

  /** Stage 1 reply: one fact whose statement is the exact span of the source. */
  static String facts(String text, NewsItem news, int start, int end) {
    return facts(text, news, text.substring(start, end), start, end);
  }

  /** The same, with a quote that need not match the offsets, to build bad spans. */
  static String facts(
    String text,
    NewsItem news,
    String span,
    int start,
    int end
  ) {
    return (
      "{\"facts\":[{\"id\":\"" +
      FACT_ID +
      "\",\"statement\":" +
      quote(span) +
      ",\"reasoning\":\"the source states this\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() +
      "\",\"quote\":" +
      quote(span) +
      ",\"startOffset\":" +
      start +
      ",\"endOffset\":" +
      end +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}"
    );
  }

  static String validFacts(NewsItem news) {
    return facts(news.source().text(), news, 0, news.source().text().length());
  }

  /** A minimal, fully present stage-2 contract: the reply shape is the point. */
  static String reasoning(String summary, String unknowns) {
    return (
      "{\"summary\":" +
      quote(summary) +
      ",\"variables\":[],\"mechanisms\":[],\"stakeholders\":[],\"firstOrderEffects\":[]," +
      "\"secondOrderEffects\":[],\"hypotheses\":[],\"alternativeExplanations\":[]," +
      "\"counterArguments\":[],\"falsificationConditions\":[],\"corroboratingSignals\":[]," +
      "\"verificationIndicators\":[],\"predictions\":[],\"unknowns\":" +
      unknowns +
      "}"
    );
  }

  static String unknownItem(String statement) {
    return (
      "[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":" +
      quote(statement) +
      ",\"unknownReason\":\"the source does not say\",\"wouldResolveWith\":\"a second independent source\",\"sourceRefs\":[]}]"
    );
  }

  static String validReasoning() {
    return reasoning("本次报道仅陈述协议到期与谈判启动。", unknownItem("续约费率的具体数值"));
  }

  // ---- harness --------------------------------------------------------------

  /** A provider whose replies are scripted per purpose and fully recorded. */
  static final class ScriptedGateway implements ModelGateway {

    private final Map<ModelPurpose, ArrayDeque<Object>> scripted = new EnumMap<>(
      ModelPurpose.class
    );
    private final List<ModelRequest> requests = new ArrayList<>();

    void reply(ModelPurpose purpose, String body) {
      scripted.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(body);
    }

    void fail(ModelPurpose purpose, ModelFailure failure) {
      scripted.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(failure);
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    long count(ModelPurpose purpose) {
      return requests.stream().filter(r -> r.purpose() == purpose).count();
    }

    @Override
    public ModelResponse call(ModelRequest request) {
      requests.add(request);
      var queue = scripted.get(request.purpose());
      if (queue == null || queue.isEmpty()) throw ModelInvocationException.of(
        ModelFailure.CONFIGURATION,
        "no scripted reply for " + request.purpose()
      );
      var next = queue.poll();
      if (next instanceof ModelFailure failure) throw ModelInvocationException.of(
        failure,
        "scripted " + failure.code()
      );
      return new ModelResponse(
        (String) next,
        new ModelUsage(100L, 20L, 120L, null),
        "stub",
        "stub-model"
      );
    }
  }

  static PipelineState state(NewsItem news) {
    return new SourceAssessmentStage().execute(
      PipelineState.initial(UUID.randomUUID(), "two-stage-test", news, Instant.now())
    );
  }

  /** Single-shot per stage, so a call count is an exact assertion. */
  static TwoStageSynthesisService service(
    ScriptedGateway gateway,
    PipelineFixtures.RecordingRuns runs,
    JsonCodec json
  ) {
    return new TwoStageSynthesisService(
      PipelineFixtures.router(),
      gateway,
      runs,
      PipelineFixtures.reader(json),
      PipelineFixtures.promptLibrary(),
      json,
      PipelineFixtures.gateA(json),
      ConfidenceRubric.deterministic(),
      PipelineFixtures.strategies(),
      1,
      0,
      0L,
      1,
      0,
      0L
    );
  }

  /** Runs both stages and returns the assembled snapshot for assertions. */
  static AnalysisResult analyze(
    TwoStageSynthesisService service,
    PipelineState state
  ) {
    return service
      .analyze(state.jobId(), state.correlationId(), state, "")
      .snapshot();
  }

  // ---- 1. the happy path ----------------------------------------------------

  @Test
  void twoValidStagesAssembleIntoASnapshotTheFullValidatorAccepts() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    gateway.reply(ModelPurpose.SYNTHESIS, validReasoning());
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var result = analyze(service(gateway, runs, json), state);

    assertNotNull(result);
    assertEquals(1, result.facts().size(), "the stage-1 fact is carried through");
    assertEquals(FACT_ID, result.facts().getFirst().id());
    assertFalse(result.summary().isBlank());
    assertFalse(result.demo(), "a scripted live provider is not a demo");
    assertFalse(result.modifiesExistingHypotheses());
    assertEquals(
      NEWS_TEXT,
      result.facts().getFirst().sourceRefs().getFirst().quote(),
      "the exact span survives assembly"
    );
    assertEquals(0, result.facts().getFirst().sourceRefs().getFirst().startOffset());
    assertEquals(
      NEWS_TEXT.length(),
      result.facts().getFirst().sourceRefs().getFirst().endOffset()
    );
  }

  // ---- 2/5. per-stage validation --------------------------------------------

  @Test
  void aWrongOffsetInStageOneIsRejectedByTheProvenanceCheck() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    // The quote is the whole text but the offsets stop one short, so the quote is
    // no longer the substring those offsets address.
    gateway.reply(
      ModelPurpose.FACT_EXTRACTION,
      facts(
        news.source().text(),
        news,
        news.source().text(),
        0,
        news.source().text().length() - 1
      )
    );
    gateway.reply(ModelPurpose.SYNTHESIS, validReasoning());
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.FACTS_STAGE, failure.stage());
    assertEquals(
      0,
      gateway.count(ModelPurpose.SYNTHESIS),
      "stage 2 must not run when stage 1 fails"
    );
  }

  @Test
  void aFabricatedStatementOutsideTheSourceIsRejected() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(
      ModelPurpose.FACT_EXTRACTION,
      "{\"facts\":[{\"id\":\"" +
      FACT_ID +
      "\",\"statement\":\"一句新闻里根本没有的话\",\"reasoning\":\"r\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() +
      "\",\"quote\":\"一句新闻里根本没有的话\",\"startOffset\":0,\"endOffset\":12}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}"
    );
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
  }

  @Test
  void anEmptyFactSetIsRejectedBecauseNothingCouldBeSupported() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, "{\"facts\":[],\"unknowns\":[]}");
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.FACTS_STAGE, failure.stage());
    assertEquals(0, gateway.count(ModelPurpose.SYNTHESIS));
  }

  @Test
  void duplicateSpansCollapseToOneFact() {
    var json = PipelineFixtures.json();
    var news = news();
    var text = news.source().text();
    var span = quote(text);
    var second = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    var gateway = new ScriptedGateway();
    gateway.reply(
      ModelPurpose.FACT_EXTRACTION,
      "{\"facts\":[" +
      "{\"id\":\"" + FACT_ID + "\",\"statement\":" + span +
      ",\"reasoning\":\"the source states this\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() + "\",\"quote\":" + span + ",\"startOffset\":0,\"endOffset\":" +
      text.length() + "}],\"verificationStatus\":\"REPORTED\"}," +
      "{\"id\":\"" + second + "\",\"statement\":" + span +
      ",\"reasoning\":\"stated again\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() + "\",\"quote\":" + span + ",\"startOffset\":0,\"endOffset\":" +
      text.length() + "}],\"verificationStatus\":\"REPORTED\"}" +
      "],\"unknowns\":[]}"
    );
    gateway.reply(ModelPurpose.SYNTHESIS, validReasoning());
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var result = analyze(service(gateway, runs, json), state);
    assertEquals(
      1,
      result.facts().size(),
      "the same span twice is one fact (STG-03.2)"
    );
  }

  @Test
  void malformedJsonFromAStageIsAControlledFailure() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, "{ this is not json");
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.FACTS_STAGE, failure.stage());
  }

  // ---- 3/4. stage-2 reference integrity -------------------------------------

  @Test
  void aFactReferenceThatStageOneNeverProducedIsRejected() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    gateway.reply(
      ModelPurpose.SYNTHESIS,
      "{\"summary\":\"s\",\"variables\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"name\":\"v\",\"statement\":\"st\",\"reasoning\":\"r\",\"confidence\":30,\"direction\":\"UP\"," +
      "\"factRefs\":[\"" +
      UUID.randomUUID() +
      "\"],\"sourceRefs\":[]}],\"mechanisms\":[],\"stakeholders\":[],\"firstOrderEffects\":[]," +
      "\"secondOrderEffects\":[],\"hypotheses\":[],\"alternativeExplanations\":[],\"counterArguments\":[]," +
      "\"falsificationConditions\":[],\"corroboratingSignals\":[],\"verificationIndicators\":[]," +
      "\"predictions\":[],\"unknowns\":[]}"
    );
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.REASONING_STAGE, failure.stage());
  }

  @Test
  void aSourceThatIsNotTheSnapshotSourceIsRejected() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(
      ModelPurpose.FACT_EXTRACTION,
      facts(news.source().text(), news, 0, 4).replace(
        news.source().id().toString(),
        UUID.randomUUID().toString()
      )
    );
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(0, gateway.count(ModelPurpose.SYNTHESIS));
  }

  @Test
  void anOmittedStageTwoArrayIsRejectedRatherThanReadAsEmpty() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    // hypotheses is absent, not empty: an unevaluated stage is not "nothing found".
    gateway.reply(
      ModelPurpose.SYNTHESIS,
      "{\"summary\":\"s\",\"variables\":[],\"mechanisms\":[],\"stakeholders\":[],\"firstOrderEffects\":[]," +
      "\"secondOrderEffects\":[],\"alternativeExplanations\":[],\"counterArguments\":[]," +
      "\"falsificationConditions\":[],\"corroboratingSignals\":[],\"verificationIndicators\":[]," +
      "\"predictions\":[],\"unknowns\":[]}"
    );
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.REASONING_STAGE, failure.stage());
  }

  // ---- 6/10/11. UNKNOWN, rubric and the full schema -------------------------

  @Test
  void unknownItemsArePreservedAndConfidenceComesFromTheRubric() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    gateway.reply(ModelPurpose.SYNTHESIS, validReasoning());
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var result = analyze(service(gateway, runs, json), state);

    assertFalse(
      result.unknowns().isEmpty(),
      "the stage-2 UNKNOWN item must survive assembly (PR-05)"
    );
    var assessment = result.confidenceAssessment();
    assertEquals(
      ConfidenceMethod.RUBRIC,
      assessment.method(),
      "the assembled score is rubric-derived, never model-supplied (CF-01)"
    );
    assertEquals("0.1", assessment.rubricVersion());
    assertFalse(assessment.dimensions().isEmpty());
    assertFalse(assessment.isProbability());
    assertTrue(assessment.score() >= 0 && assessment.score() <= 100);
    assertNull(
      assessment.advisoryScore(),
      "no model number is adopted as an advisory score either"
    );
    assertEquals("0.1", result.protocolVersion());
    assertNotNull(result.provenance());
    assertTrue(
      result.provenance().promptVersions().contains("two-stage-facts-v1"),
      "PR-15: the stage prompt versions are recorded: " +
      result.provenance().promptVersions()
    );
    assertTrue(
      result.provenance().promptVersions().contains("two-stage-reasoning-v1")
    );
  }

  // ---- 12/13. failure isolation and audit -----------------------------------

  @Test
  void aProviderFailureInStageOneStopsBeforeStageTwoAndIsAudited() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.fail(ModelPurpose.FACT_EXTRACTION, ModelFailure.AUTHENTICATION);
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.FACTS_STAGE, failure.stage());
    assertEquals(0, gateway.count(ModelPurpose.SYNTHESIS));
    assertEquals(1, runs.all().size(), "one attempt, one audit row");
    assertEquals("FAILED", runs.all().getFirst().status());
    assertEquals("AUTHENTICATION", runs.all().getFirst().errorType());
    assertEquals(
      "two-stage-facts-v1",
      runs.all().getFirst().promptVersion(),
      "the audit names the stage prompt that produced the attempt"
    );
  }

  @Test
  void eachStageIsAuditedSeparatelyWithItsOwnPurposeAndVersion() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    gateway.reply(ModelPurpose.SYNTHESIS, validReasoning());
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    analyze(service(gateway, runs, json), state);

    assertEquals(2, runs.all().size(), "two stages, two audit rows");
    var byPurpose = new HashMap<ModelPurpose, ModelRun>();
    for (var run : runs.all()) byPurpose.put(run.purpose(), run);
    assertEquals(
      "two-stage-facts-v1",
      byPurpose.get(ModelPurpose.FACT_EXTRACTION).promptVersion()
    );
    assertEquals(
      "two-stage-reasoning-v1",
      byPurpose.get(ModelPurpose.SYNTHESIS).promptVersion()
    );
    for (var run : runs.all()) {
      assertEquals("SUCCEEDED", run.status());
      assertEquals("stub", run.provider());
      assertEquals("stub-model", run.model());
      assertNull(run.errorType());
      assertEquals(120L, run.totalTokens().longValue());
      assertNull(run.estimatedCost(), "cost is unknown, never fabricated");
    }
  }

  @Test
  void aFailedStageTwoIsNeverAssembled() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    gateway.reply(ModelPurpose.SYNTHESIS, "{\"summary\":\"only a summary\"}");
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    var failure = assertThrows(StageFailure.class, () ->
      analyze(service(gateway, runs, json), state)
    );
    assertEquals(TwoStageSynthesisService.REASONING_STAGE, failure.stage());
    assertEquals(
      1,
      runs.all().stream().filter(r -> "FAILED".equals(r.status())).count(),
      "the failed stage is audited as failed"
    );
  }

  // ---- 14. prompt budgets ---------------------------------------------------

  @Test
  void eachStagePromptCarriesOnlyItsOwnSchema() {
    var json = PipelineFixtures.json();
    var gateway = new ScriptedGateway();
    var runs = new PipelineFixtures.RecordingRuns();
    var service = service(gateway, runs, json);

    var sizes = service.promptSizes();
    assertTrue(
      sizes.get(TwoStageSynthesisService.FACTS_STAGE) <
      sizes.get(TwoStageSynthesisService.REASONING_STAGE),
      "fact extraction carries the smaller contract: " + sizes
    );

    var news = news();
    var state = state(news);
    var factsPrompt = service.factsPromptText(service.factsPrompt(), state);
    var reasoningPrompt = service.reasoningPromptText(
      service.reasoningPrompt(),
      state.withFacts(
        List.of(
          new ProtocolModel.Fact(
            FACT_ID,
            NEWS_TEXT,
            "reported",
            NEWS_TEXT.isEmpty() ? List.of() : List.of(),
            ProtocolModel.ProofStatus.REPORTED
          )
        )
      ),
      ""
    );
    assertTrue(
      factsPrompt.contains("\"Fact\""),
      "stage 1 carries the Fact definition"
    );
    assertFalse(
      factsPrompt.contains("\"Hypothesis\""),
      "stage 1 must not carry the reasoning definitions"
    );
    assertTrue(
      reasoningPrompt.contains("\"Hypothesis\""),
      "stage 2 carries the reasoning definitions"
    );
    assertFalse(
      reasoningPrompt.contains("\"Provenance\""),
      "stage 2 must not carry the assembly-only definitions"
    );
    assertTrue(
      reasoningPrompt.contains(FACT_ID.toString()),
      "stage 2 is shown the validated fact ids it may cite"
    );
    assertTrue(
      factsPrompt.trim().endsWith("UTF-16 code units of that exact text."),
      "the output contract stays last in the stage-1 prompt"
    );
  }

  // ---- 15. the monolithic path is untouched ---------------------------------

  @Test
  void theDefaultModeIsStillTheMonolithicDraft() {
    assertEquals(
      AnalysisPipelineMode.Mode.DRAFT,
      new AnalysisPipelineMode("draft").mode()
    );
    assertFalse(new AnalysisPipelineMode("draft").twoStage());
    assertTrue(new AnalysisPipelineMode("two-stage").twoStage());
    assertTrue(new AnalysisPipelineMode("TWO_STAGE").twoStage());
    // An unknown mode is still a configuration error, not a silent fallback.
    assertThrows(IllegalStateException.class, () ->
      new AnalysisPipelineMode("three-stage")
    );
  }

  /** The two-stage service never mints a claim: it only projects what stages said. */
  @Test
  void assemblyAddsNoClaimOfItsOwn() {
    var json = PipelineFixtures.json();
    var news = news();
    var gateway = new ScriptedGateway();
    gateway.reply(ModelPurpose.FACT_EXTRACTION, validFacts(news));
    gateway.reply(ModelPurpose.SYNTHESIS, validReasoning());
    var runs = new PipelineFixtures.RecordingRuns();
    var state = state(news);

    AnalysisResult result = analyze(service(gateway, runs, json), state);

    assertEquals(1, result.facts().size());
    assertTrue(result.variables().isEmpty());
    assertTrue(result.hypotheses().isEmpty());
    assertTrue(result.mechanisms().isEmpty());
    assertEquals(
      "本次报道仅陈述协议到期与谈判启动。",
      result.summary(),
      "the summary is the stage-2 text, not a synthesised one"
    );
  }
}
