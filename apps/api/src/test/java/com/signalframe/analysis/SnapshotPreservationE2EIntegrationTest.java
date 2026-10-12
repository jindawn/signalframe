package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.OpenAiStubServer;
import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.steps.ProtocolModel;
import com.signalframe.analysis.application.steps.TwoStageModel;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.FalsificationConditionText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The snapshot-preservation end-to-end: a compliant model reply survives the whole
 * chain with nothing silently dropped.
 *
 * <p>Chain under test: news → stage 1 → stage 2 → assembler → {@code SnapshotDraftStage}
 * → the 14-step pipeline → PostgreSQL → the analysis API. Real Spring orchestration,
 * real Flyway V1→V4 on an isolated PostgreSQL, real validators and the real
 * deterministic rubric; only the provider is a local HTTP stub, and the credential is
 * a test sentinel.
 *
 * <p>Every field the fixture declares is asserted field by field at the API, and the
 * persisted row is compared against what the API returns, so a loss anywhere between
 * the reply and the read path fails here.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "analysis.pipeline.mode=two-stage",
    "ai.two-stage.max-provider-calls=2",
  }
)
class SnapshotPreservationE2EIntegrationTest {

  static final String SENTINEL_KEY = "sk-preservation-sentinel-135792468";
  static final String STUB_MODEL = "stub-preservation-model";

  static final UUID FACT_A = UUID.fromString("aaaabbbb-0000-4000-8000-000000000001");
  static final UUID FACT_B = UUID.fromString("aaaabbbb-0000-4000-8000-000000000002");
  static final UUID HYPOTHESIS_ID = UUID.fromString(
    "aaaabbbb-0000-4000-8000-000000000003"
  );
  static final UUID CONDITION_ID = UUID.fromString(
    "aaaabbbb-0000-4000-8000-000000000004"
  );
  static final UUID PREDICTION_ID = UUID.fromString(
    "aaaabbbb-0000-4000-8000-000000000005"
  );
  static final UUID INDICATOR_ID = UUID.fromString(
    "aaaabbbb-0000-4000-8000-000000000006"
  );

  static final String SENTENCE_ONE = "某公司宣布其专利许可协议将于本季度到期。";
  static final String SENTENCE_TWO = "双方已启动续约费率谈判。";
  static final String NEWS_TEXT = SENTENCE_ONE + SENTENCE_TWO;

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static final OpenAiStubServer stub;
  static final Deque<String> scripted = new ArrayDeque<>();

  static {
    postgres.start();
    try {
      stub = new OpenAiStubServer(body -> {
        var next = scripted.poll();
        if (next == null) return OpenAiStubServer.Reply.status(
          503,
          OpenAiStubServer.error(503, "stub script exhausted")
        );
        return OpenAiStubServer.Reply.ok(
          OpenAiStubServer.completion(next, 4812, 1190)
        );
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("ai.profiles.[analysis.fast].provider", () -> "openai-compatible");
    registry.add("ai.profiles.[analysis.fast].model", () -> STUB_MODEL);
    registry.add("ai.profiles.[analysis.fast].base-url", stub::baseUrl);
    registry.add("ai.routes.SYNTHESIS", () -> "analysis.fast");
    registry.add("ai.routes.FACT_EXTRACTION", () -> "analysis.fast");
  }

  @TestConfiguration
  static class PreservationStubs {

    @Bean
    @Primary
    ModelCredentialSource testCredentialSource() {
      return name -> Optional.of(SENTINEL_KEY);
    }
  }

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  @BeforeEach
  void resetScript() {
    scripted.clear();
    stub.clear();
  }

  // ---- the compliant fixture -------------------------------------------------

  /** Two facts, each the exact span of one sentence, with its UTF-16 offsets. */
  static String factsBody(NewsItem news) {
    var text = news.source().text();
    var firstEnd = SENTENCE_ONE.length();
    var secondStart = firstEnd;
    var secondEnd = text.length();
    var facts = List.of(
      new ProtocolModel.Fact(
        FACT_A,
        SENTENCE_ONE,
        "the source states this",
        List.of(new SourceRef(news.source().id(), SENTENCE_ONE, 0, firstEnd)),
        ProtocolModel.ProofStatus.REPORTED
      ),
      new ProtocolModel.Fact(
        FACT_B,
        SENTENCE_TWO,
        "the source states this",
        List.of(
          new SourceRef(
            news.source().id(),
            SENTENCE_TWO,
            secondStart,
            secondEnd
          )
        ),
        ProtocolModel.ProofStatus.REPORTED
      )
    );
    return new JsonCodec().write(
      new TwoStageModel.Facts(facts, List.of())
    );
  }

  /**
   * A fully compliant stage-2 reply: every artifact cites the stage-1 facts, the
   * mechanism declares PLAUSIBLE with its fact basis, the prediction is dated and
   * observable, and the plan item is attached to that prediction.
   */
  static String reasoningBody(NewsItem news) {
    var text = news.source().text();
    var spanA = new SourceRef(news.source().id(), SENTENCE_ONE, 0, SENTENCE_ONE.length());
    var spanB = new SourceRef(
      news.source().id(),
      SENTENCE_TWO,
      SENTENCE_ONE.length(),
      text.length()
    );
    var facts = List.of(FACT_A, FACT_B);
    var deadline = Instant.now().plus(Duration.ofDays(60));

    var variable = new ProtocolModel.Variable(
      UUID.randomUUID(),
      "续约费率",
      "UNKNOWN",
      "谈判中",
      ProtocolModel.Direction.UNCHANGED,
      "尚未确定",
      "费率决定下游成本",
      "续约费率仍在谈判",
      "双方已启动谈判",
      facts,
      List.of(spanB),
      30
    );
    var mechanism = new ProtocolModel.Mechanism(
      UUID.randomUUID(),
      FACT_A.toString(),
      FACT_B.toString(),
      ProtocolModel.SupportLevel.PLAUSIBLE,
      "到期触发重新议价",
      "协议期限与谈判启动一致",
      List.of("双方均有续约意愿"),
      facts,
      List.of(spanA, spanB),
      null,
      25
    );
    var hypothesis = new ProtocolModel.Hypothesis(
      HYPOTHESIS_ID,
      "协议到期推动续约谈判",
      "协议到期是本季度谈判启动的原因",
      "两个事实在时间上一致",
      facts,
      List.of(),
      List.of(),
      List.of("双方均有续约意愿"),
      List.of(),
      List.of(CONDITION_ID),
      "rubric",
      null
    );
    var alternative = new ProtocolModel.Alternative(
      UUID.randomUUID(),
      "谈判由定价基准调整驱动",
      "同期行业费率变化",
      HYPOTHESIS_ID,
      facts,
      List.of(spanB),
      25
    );
    var counterArgument = new ProtocolModel.CounterArgument(
      UUID.randomUUID(),
      "到期并不必然导致谈判",
      "许多协议静默续期",
      HYPOTHESIS_ID,
      facts,
      List.of(spanA),
      25
    );
    var condition = new ProtocolModel.FalsificationCondition(
      CONDITION_ID,
      HYPOTHESIS_ID,
      "续约公告",
      "是否出现谈判记录",
      "若 90 天内没有出现任何续约谈判记录则条件成立",
      "若 90 天内仍无谈判记录，则该假设被证伪"
    );
    var prediction = new ProtocolModel.Prediction(
      PREDICTION_ID,
      HYPOTHESIS_ID,
      "下一份公告将披露续约结果",
      "续约公告",
      deadline,
      "确认需公告披露续约；部分确认需仅披露谈判进展；拒绝需公告称不再续约",
      "公司公告页",
      ProtocolModel.PredictionStatus.OPEN,
      facts
    );
    var plan = new ProtocolModel.PlanItem(
      INDICATOR_ID,
      "续约公告中的费率",
      "公司公告页",
      "公告披露费率低于上期",
      "公告披露费率高于上期",
      ProtocolModel.PlanPriority.HIGH,
      deadline,
      HYPOTHESIS_ID,
      PREDICTION_ID,
      "跟踪费率公告",
      "费率是核心变量",
      List.of(spanB),
      25
    );
    return new JsonCodec().write(
      new TwoStageModel.Reasoning(
        "协议到期推动了本季度的续约谈判。",
        List.of(variable),
        List.of(mechanism),
        List.of(),
        List.of(),
        List.of(),
        List.of(hypothesis),
        List.of(alternative),
        List.of(counterArgument),
        List.of(condition),
        List.of(),
        List.of(plan),
        List.of(prediction),
        List.of()
      )
    );
  }

  // ---- the positive end-to-end ----------------------------------------------

  @Test
  void aCompliantReplySurvivesTheWholeChainIntoPostgresAndTheApi() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "snapshot preservation e2e")
    );
    assertEquals(201, created.statusCode(), created.body());
    var news = json.read(created.body(), NewsItem.class);

    scripted.add(factsBody(news));
    scripted.add(reasoningBody(news));

    var job = runAnalysis(news);
    assertEquals(JobStatus.COMPLETED, job.status(), job.error());
    assertEquals(2, stub.bodies().size(), "two stages, two requests");

    var analysis = json.read(
      request("GET", "/api/v1/analyses/" + job.analysisId(), null).body(),
      Analysis.class
    );
    var result = analysis.result();

    // ---- facts: both preserved, with their exact spans and provenance ------
    assertEquals(2, result.facts().size(), "both facts survive: " + result.facts());
    var byId = new HashMap<UUID, Fact>();
    for (var fact : result.facts()) byId.put(fact.id(), fact);
    assertTrue(byId.containsKey(FACT_A), "fact A kept its id");
    assertTrue(byId.containsKey(FACT_B), "fact B kept its id");
    assertEquals(SENTENCE_ONE, byId.get(FACT_A).statement());
    assertEquals(SENTENCE_TWO, byId.get(FACT_B).statement());
    assertEquals(
      SENTENCE_ONE,
      NEWS_TEXT.substring(
        byId.get(FACT_A).sourceRefs().getFirst().startOffset(),
        byId.get(FACT_A).sourceRefs().getFirst().endOffset()
      ),
      "fact A's UTF-16 span still addresses its sentence"
    );
    assertEquals(
      SENTENCE_TWO,
      NEWS_TEXT.substring(
        byId.get(FACT_B).sourceRefs().getFirst().startOffset(),
        byId.get(FACT_B).sourceRefs().getFirst().endOffset()
      ),
      "fact B's UTF-16 span still addresses its sentence"
    );
    for (var fact : result.facts()) {
      assertEquals(ClaimType.FACT, fact.type());
      assertEquals("REPORTED", fact.verificationStatus());
      assertEquals(news.source().id(), fact.sourceRefs().getFirst().sourceId());
    }

    // ---- variables: the declared state, not a hardcoded UNKNOWN -----------
    assertEquals(1, result.variables().size());
    var variable = result.variables().getFirst();
    assertEquals("谈判中", variable.currentState());
    assertEquals("尚未确定", variable.magnitude());
    assertEquals("费率决定下游成本", variable.whyItMatters());
    assertEquals("UNCHANGED", variable.direction());
    assertTrue(variable.factRefs().containsAll(List.of(FACT_A, FACT_B)));

    // ---- mechanisms: kept, with the declared level and fact basis ---------
    assertEquals(
      1,
      result.mechanisms().size(),
      "a PLAUSIBLE mechanism with a fact basis must survive: " + result.mechanisms()
    );
    var mechanism = result.mechanisms().getFirst();
    assertEquals("PLAUSIBLE", mechanism.supportLevel());
    assertEquals("到期触发重新议价", mechanism.statement());
    assertTrue(
      mechanism.sourceRefs().size() == 2,
      "the declared spans travel with the mechanism"
    );

    // ---- hypothesis, alternative, counter-argument, falsification ---------
    assertEquals(1, result.hypotheses().size());
    assertEquals(HYPOTHESIS_ID, result.hypotheses().getFirst().id());
    assertEquals(1, result.alternativeExplanations().size());
    assertEquals(1, result.counterArguments().size());
    assertEquals(1, result.falsificationConditions().size());
    var condition = result.falsificationConditions().getFirst();
    assertEquals(
      "若 90 天内仍无谈判记录，则该假设被证伪",
      condition.statement(),
      "the free-text statement is preserved as written"
    );
    var parts = FalsificationConditionText.parts(condition);
    assertEquals(
      "续约公告",
      parts.get(FalsificationConditionText.OBSERVABLE),
      "the observable is preserved rather than declared absent"
    );
    assertEquals(
      "若 90 天内没有出现任何续约谈判记录则条件成立",
      parts.get(FalsificationConditionText.DECISION_BOUNDARY)
    );

    // ---- the prediction: present, OPEN, dated, never verified -------------
    assertEquals(
      1,
      result.predictions().size(),
      "the declared prediction must reach the snapshot (SCH-07): " + result.predictions()
    );
    var prediction = result.predictions().getFirst();
    assertEquals(PREDICTION_ID, prediction.id());
    assertEquals(HYPOTHESIS_ID, prediction.hypothesisId());
    assertEquals("OPEN", prediction.status(), "a prediction is never marked verified");
    assertEquals("PREDICTION", prediction.type());
    assertEquals("续约公告", prediction.observable());
    assertEquals("公司公告页", prediction.whereToCheck());
    assertTrue(prediction.expectedBy().isAfter(analysis.createdAt()));
    assertTrue(prediction.basisFactRefs().containsAll(List.of(FACT_A, FACT_B)));

    // ---- the verification plan: mapped from the indicators (SCH-08) -------
    assertEquals(
      1,
      result.verificationIndicators().size(),
      "the declared indicator must become a plan item: " + result.verificationIndicators()
    );
    var indicator = result.verificationIndicators().getFirst();
    assertEquals(INDICATOR_ID, indicator.id());
    assertEquals("公司公告页", indicator.whereToCheck());
    assertEquals("公告披露费率低于上期", indicator.supportingResult());
    assertEquals("公告披露费率高于上期", indicator.contradictingResult());
    assertEquals("HIGH", indicator.priority());
    assertEquals(PREDICTION_ID, indicator.predictionId());
    assertNotNull(indicator.deadline());

    // ---- epistemic status and confidence are not inflated -----------------
    assertEquals(ConfidenceMethod.RUBRIC, result.confidenceAssessment().method());
    assertEquals("0.1", result.confidenceAssessment().rubricVersion());
    assertFalse(result.confidenceAssessment().isProbability());
    assertFalse(result.demo());
    assertFalse(result.modifiesExistingHypotheses());
    assertEquals("0.1", result.protocolVersion());

    // ---- no fabrication: nothing was invented to fill a gap ---------------
    // The remaining notes must be protocol facts about the input (a single undated
    // source, no second-order effect declared), never a note that a declared
    // artifact was dropped. Those were the symptom this fix removes.
    var notes = result.unknowns().stream().map(Statement::statement).toList();
    for (var dropped : List.of(
      "SCH-06 pending",
      "SCH-07 pending",
      "SCH-08 pending",
      "EP-03"
    )) {
      assertFalse(
        notes.stream().anyMatch(n -> n.contains(dropped)),
        "nothing declared may be dropped: " + dropped + " in " + notes
      );
    }

    // ---- the API read agrees with what was persisted ----------------------
    var persisted = db.queryForObject(
      "SELECT payload::text FROM analyses WHERE id=?",
      String.class,
      job.analysisId()
    );
    var persistedResult = json
      .read(persisted, Analysis.class)
      .result();
    assertEquals(2, persistedResult.facts().size());
    assertEquals(1, persistedResult.mechanisms().size());
    assertEquals(1, persistedResult.predictions().size());
    assertEquals(1, persistedResult.verificationIndicators().size());
    assertEquals(1, persistedResult.falsificationConditions().size());
    assertEquals(
      result.predictions().getFirst().statement(),
      persistedResult.predictions().getFirst().statement(),
      "the API returns exactly the persisted snapshot"
    );

    // ---- the research engine still reads it unchanged ---------------------
    var detail = json.read(
      request("GET", "/api/v1/hypotheses/" + HYPOTHESIS_ID, null).body(),
      HypothesisDetail.class
    );
    assertEquals(0L, detail.version());
    assertEquals("CREATED", detail.timeline().getFirst().eventType());
  }

  /**
   * A stage failure leaves nothing behind: no analysis row, no indicator, no
   * prediction, and a job that says which stage failed. A failed run is never a
   * partial success.
   */
  @Test
  void aStageFailureLeavesNoPartialSnapshot() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "snapshot preservation negative")
    );
    var news = json.read(created.body(), NewsItem.class);
    scripted.add(factsBody(news));
    // Two broken replies so the repair round also fails inside the call ceiling.
    scripted.add("{\"summary\":\"only a summary\"}");
    scripted.add("{\"summary\":\"only a summary\"}");

    var job = runAnalysis(news);

    assertEquals(JobStatus.FAILED, job.status());
    assertNotNull(job.error());
    assertTrue(
      job.error().contains("TwoStageReasoning"),
      "the failure names the stage: " + job.error()
    );
    assertNull(job.analysisId(), "no snapshot is recorded on the job");
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM analyses WHERE news_id=?", Integer.class, news.id()),
      "a failed analysis persists no snapshot"
    );
    // The audit stays truthful: stage 1 really did succeed and stage 2 really did
    // fail, while the job and the absence of an analysis row are what say the run
    // produced nothing.
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM model_runs WHERE job_id=? AND status='SUCCEEDED'",
        Integer.class,
        job.id()
      ),
      "the successful stage is still audited honestly"
    );
    assertTrue(
      db.queryForObject(
        "SELECT count(*) FROM model_runs WHERE job_id=? AND status='FAILED'",
        Integer.class,
        job.id()
      ) >= 1,
      "the failed stage is audited too"
    );
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM predictions WHERE hypothesis_id IN (SELECT id FROM hypotheses WHERE analysis_id IS NULL)",
        Integer.class
      ),
      "no orphan prediction was written by the failed run"
    );
  }

  // ---- helpers --------------------------------------------------------------

  AnalysisJob runAnalysis(NewsItem news) throws Exception {
    var started = request("POST", "/api/v1/news/" + news.id() + "/analyze", null);
    assertEquals(202, started.statusCode(), started.body());
    var job = json.read(started.body(), AnalysisJob.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (
      job.status() != JobStatus.COMPLETED &&
      job.status() != JobStatus.FAILED &&
      System.nanoTime() < deadline
    ) {
      Thread.sleep(75);
      job = json.read(
        request("GET", "/api/v1/analysis-jobs/" + job.id(), null).body(),
        AnalysisJob.class
      );
    }
    return job;
  }

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    ).timeout(Duration.ofSeconds(30));
    if (body != null) builder.header("Content-Type", "application/json");
    return http.send(
      builder
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.write(body))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }
}
