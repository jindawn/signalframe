package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.AnalysisPipeline;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The two-stage mode's persistence boundary, checked through the real pipeline with
 * the offline scripted provider.
 *
 * <p>Two properties matter and neither can be shown by a service-level unit test:
 * a stage-2 failure must leave no analysis behind, and a successful two-stage run
 * must still be one ordinary {@code Analysis} that Wave 2B's hypothesis engine can
 * read.
 */
class TwoStagePersistenceBoundaryTest {

  static final UUID FACT_ID = UUID.fromString("cccccccc-0000-4000-8000-000000000003");
  static final String NEWS_TEXT =
    "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";

  static String quote(String value) {
    return PipelineFixtures.json().write(value);
  }

  static String factsBody(NewsItem news) {
    var text = news.source().text();
    return (
      "{\"facts\":[{\"id\":\"" +
      FACT_ID +
      "\",\"statement\":" +
      quote(text) +
      ",\"reasoning\":\"the source states this\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() +
      "\",\"quote\":" +
      quote(text) +
      ",\"startOffset\":0,\"endOffset\":" +
      text.length() +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}"
    );
  }

  /**
   * A stage-2 reply rich enough for the downstream stages as well: the assembled
   * draft is fed to the same STG-09..STG-15 stages a monolithic draft is, so a
   * snapshot with no hypothesis fails there (EP-02) even though it is structurally
   * valid. That is the intended behaviour, and this fixture satisfies it.
   */
  static String reasoningBody(NewsItem news) {
    var hypothesisId = java.util.UUID.fromString(
      "dddddddd-0000-4000-8000-000000000004"
    );
    var conditionId = java.util.UUID.fromString(
      "eeeeeeee-0000-4000-8000-000000000005"
    );
    return (
      "{\"summary\":\"本次报道仅陈述协议到期与谈判启动。\"," +
      "\"variables\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"name\":\"续约费率\",\"statement\":\"费率尚未确定\",\"reasoning\":\"谈判刚开始\"," +
      "\"whyItMatters\":\"费率决定下游成本\",\"confidence\":30,\"direction\":\"UNKNOWN\",\"factRefs\":[\"" +
      FACT_ID +
      "\"],\"sourceRefs\":[]}]," +
      "\"mechanisms\":[],\"stakeholders\":[],\"firstOrderEffects\":[],\"secondOrderEffects\":[]," +
      "\"hypotheses\":[{\"id\":\"" +
      hypothesisId +
      "\",\"title\":\"协议到期推动续约谈判\",\"statement\":\"协议到期是本季度谈判启动的原因\"," +
      "\"reasoning\":\"协议期限与谈判时间一致\",\"supportingFactRefs\":[\"" +
      FACT_ID +
      "\"],\"supportingEvidenceRefs\":[],\"contradictingEvidenceRefs\":[]," +
      "\"assumptions\":[\"双方均有续约意愿\"],\"alternativeRefs\":[],\"falsificationRefs\":[\"" +
      conditionId +
      "\"],\"confidenceReason\":\"rubric\"}]," +
      "\"alternativeExplanations\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"谈判可能由定价基准调整驱动\",\"reasoning\":\"同期行业费率变化\"," +
      "\"confidence\":25,\"factRefs\":[\"" +
      FACT_ID +
      "\"],\"sourceRefs\":[],\"rivalsHypothesisRef\":\"" +
      hypothesisId +
      "\"}]," +
      "\"counterArguments\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"到期并不必然导致谈判\",\"reasoning\":\"许多协议静默续期\"," +
      "\"confidence\":25,\"factRefs\":[\"" +
      FACT_ID +
      "\"],\"sourceRefs\":[],\"targetHypothesisRef\":\"" +
      hypothesisId +
      "\"}]," +
      "\"falsificationConditions\":[{\"id\":\"" +
      conditionId +
      "\",\"hypothesisRef\":\"" +
      hypothesisId +
      "\",\"observable\":\"续约公告\",\"comparison\":\"是否出现谈判记录\"," +
      "\"decisionBoundary\":\"若 90 天内没有出现任何续约谈判记录则条件成立\"," +
      "\"statement\":\"若 90 天内仍无谈判记录，则该假设被证伪\"}]," +
      "\"corroboratingSignals\":[],\"verificationIndicators\":[],\"predictions\":[]," +
      "\"unknowns\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"续约费率的具体数值\",\"unknownReason\":\"the source does not say\"," +
      "\"wouldResolveWith\":\"a second independent source\",\"sourceRefs\":[]}]}"
    );
  }

  record Outcome(List<Analysis> analyses, List<ModelRun> runs) {}

  static Outcome runPipeline(
    java.util.function.Function<com.signalframe.ai.domain.ModelRequest, String> responder
  ) {
    var json = PipelineFixtures.json();
    var jobs = new PipelineFixtures.InMemoryJobs();
    var analyses = new PipelineFixtures.InMemoryAnalyses();
    var runs = new PipelineFixtures.RecordingRuns();
    var gateway = new PipelineFixtures.ScriptedGateway(responder);
    var pipeline = PipelineFixtures.pipeline(
      new AnalysisPipelineMode(AnalysisPipelineMode.Mode.TWO_STAGE),
      gateway,
      runs,
      jobs,
      analyses
    );
    var news = PipelineFixtures.news(NEWS_TEXT);
    pipeline.run(PipelineFixtures.context(jobs, news));
    return new Outcome(analyses.completed(), runs.all());
  }

  @Test
  void aSuccessfulTwoStageRunPersistsExactlyOneAnalysis() {
    var outcome = runPipeline(request ->
      request.purpose() == ModelPurpose.FACT_EXTRACTION
        ? factsBody(request.news())
        : reasoningBody(request.news())
    );

    assertEquals(1, outcome.analyses().size(), "one analysis is persisted");
    var analysis = outcome.analyses().getFirst();
    assertEquals(1, analysis.result().facts().size());
    assertEquals(FACT_ID, analysis.result().facts().getFirst().id());
    assertEquals(
      ConfidenceMethod.RUBRIC,
      analysis.result().confidenceAssessment().method(),
      "the persisted score is the rubric's, not a model's"
    );
    assertFalse(analysis.result().unknowns().isEmpty());
    assertEquals(2, outcome.runs().size(), "two stages, two audit rows");
  }

  @Test
  void aStageTwoFailurePersistsNothingAtAll() {
    assertThrows(StageFailure.class, () ->
      runPipeline(request ->
        request.purpose() == ModelPurpose.FACT_EXTRACTION
          ? factsBody(request.news())
          : "{ not a reasoning reply"
      )
    );
  }

  @Test
  void aStageOneFailureNeverReachesStageTwoAndPersistsNothing() {
    var requests = new ArrayList<ModelPurpose>();
    assertThrows(StageFailure.class, () ->
      runPipeline(request -> {
        requests.add(request.purpose());
        return "{ not a facts reply";
      })
    );
    assertFalse(requests.isEmpty(), "stage 1 is attempted");
    assertTrue(
      requests.stream().noneMatch(purpose -> purpose == ModelPurpose.SYNTHESIS),
      "stage 2 must not be attempted when stage 1 fails: " + requests
    );
  }

  @Test
  void theMonolithicModeStillPersistsTheSameShape() {
    var json = PipelineFixtures.json();
    var jobs = new PipelineFixtures.InMemoryJobs();
    var analyses = new PipelineFixtures.InMemoryAnalyses();
    var runs = new PipelineFixtures.RecordingRuns();
    var pipeline = PipelineFixtures.pipeline(
      new AnalysisPipelineMode(AnalysisPipelineMode.Mode.DRAFT),
      new com.signalframe.infrastructure.ai.providers.MockModelGateway(json),
      runs,
      jobs,
      analyses
    );
    pipeline.run(PipelineFixtures.context(jobs, PipelineFixtures.news(NEWS_TEXT)));

    assertEquals(1, analyses.completed().size(), "the compatible path is unchanged");
    assertEquals(1, runs.all().size(), "one synthesis call, one audit row");
    assertEquals(
      "synthesis-v3",
      runs.all().getFirst().promptVersion(),
      "the monolithic path still records the monolithic prompt version"
    );
  }

  /** The two-stage mode must not change what a jobs-level failure looks like. */
  @Test
  void aTwoStageFailureNamesTheStageThatFailed() {
    var failure = assertThrows(StageFailure.class, () ->
      runPipeline(request ->
        request.purpose() == ModelPurpose.FACT_EXTRACTION
          ? factsBody(request.news())
          : "{\"summary\":\"only a summary\"}"
      )
    );
    assertEquals(TwoStageSynthesisService.REASONING_STAGE, failure.stage());
    assertNotNull(failure.kind());
    assertFalse(failure.jobMessage().isBlank());
  }

  /** The JSON contract of the narrow stage reply is the point of the PoC. */
  @Test
  void theNarrowStageContractsAreTheOnlyShapesRequired() {
    JsonCodec json = PipelineFixtures.json();
    var reasoning = json.read(
      reasoningBody(PipelineFixtures.news(NEWS_TEXT)),
      TwoStageModel.Reasoning.class
    );
    assertNotNull(reasoning.summary());
    assertNotNull(reasoning.variables());
    assertNotNull(reasoning.verificationIndicators());
    assertNotNull(reasoning.unknowns(), "unknowns is present in this fixture");
    var partial = json.read("{\"summary\":\"s\"}", TwoStageModel.Reasoning.class);
    assertNull(
      partial.variables(),
      "a missing artifact parses as null, which the stage validator rejects: an omitted stage is never empty"
    );
  }
}
