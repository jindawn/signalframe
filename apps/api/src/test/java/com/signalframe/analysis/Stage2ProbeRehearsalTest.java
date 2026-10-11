package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.OpenAiStubServer;
import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.news.application.NewsService;
import com.signalframe.shared.JsonCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stage 2 独立探针的离线演练：参数正确、只发一次请求、绝不触发 Stage 1。
 *
 * <p>与真实探针共用 {@link StageOneSnapshot} 与 {@code reasoningOnly} 入口，因此这里
 * 通过的东西就是真实运行会执行的东西。
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "ai.two-stage.max-provider-calls=1",
    "ai.two-stage.reasoning.max-attempts=1",
    "ai.two-stage.reasoning.max-repair-attempts=0",
    "ai.two-stage.reasoning.max-tokens=16384",
    "ai.two-stage.reasoning.timeout=120",
    "ai.client.reasoning-effort.SYNTHESIS=low",
  }
)
class Stage2ProbeRehearsalTest {

  static final String SENTINEL_KEY = "sk-stage2-rehearsal-sentinel-9753";
  static final String STUB_MODEL = "stub-stage2-model";

  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine");
  static final OpenAiStubServer stub;
  static final AtomicReference<String> canned = new AtomicReference<>();

  static {
    postgres.start();
    try {
      stub = new OpenAiStubServer(body -> {
        var next = canned.get();
        if (next == null) return OpenAiStubServer.Reply.status(
          503,
          OpenAiStubServer.error(503, "stub not primed")
        );
        return OpenAiStubServer.Reply.ok(OpenAiStubServer.completion(next, 3300, 900));
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String quote(String v) {
    return new JsonCodec().write(v);
  }

  /** 符合 reader 窄契约的 Stage 2 回复，factRefs 指向 Stage 1 的真实 fact id。 */
  static String reasoningReply() {
    var factId = StageOneSnapshot.FACT_IDS[0];
    var variable = new ProtocolModel.Variable(
      UUID.randomUUID(), "续约价格", "UNKNOWN", "谈判中",
      ProtocolModel.Direction.UNCHANGED, "尚未确定", "价格决定下游成本",
      "续约价格仍在谈判", "双方已启动谈判",
      List.of(factId), List.of(), 30
    );
    var hypothesis = new ProtocolModel.Hypothesis(
      UUID.randomUUID(), "协议到期推动续约谈判", "协议到期是本季度谈判启动的原因",
      "两条事实在时间上一致", List.of(factId), List.of(), List.of(),
      List.of("双方均有续约意愿"), List.of(), List.of(), "rubric", null
    );
    return new JsonCodec().write(
      new TwoStageModel.Reasoning(
        "协议到期推动本季度续约谈判。",
        List.of(variable), List.of(), List.of(), List.of(), List.of(),
        List.of(hypothesis), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of()
      )
    );
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
  }

  @TestConfiguration
  static class Stubs {

    @Bean
    @Primary
    ModelCredentialSource testCredentialSource() {
      return name -> Optional.of(SENTINEL_KEY);
    }
  }

  @Autowired
  TwoStageSynthesisService twoStage;

  @Autowired
  ModelRunRepository runs;

  @Autowired
  NewsService newsService;

  @Autowired
  JobRepository jobs;

  @BeforeEach
  void reset() {
    stub.clear();
    canned.set(null);
  }

  @Test
  void theStageTwoProbeSendsOneRequestWithTheExperimentalParameters() {
    var news = newsService.create(new NewsInput(null, StageOneSnapshot.TEXT, "stage2 rehearsal"));
    var job = jobs.create(news.id(), "stage2-rehearsal");
    var state = StageOneSnapshot.state(job.id(), news);
    canned.set(reasoningReply());

    var probe = twoStage.reasoningOnly(job.id(), state.correlationId(), state, null);

    // ---- 参数与调用次数 ----
    assertEquals(1, probe.providerCalls(), "恰好一次 provider 请求");
    assertEquals(1, stub.bodies().size(), "stub 只看到一次请求");
    assertEquals(16384, probe.profile().maxTokens(), "Stage 2 的实验上限");
    assertEquals(120, probe.profile().timeout());

    var sent = stub.bodies().getFirst();
    System.out.println(
      "PROMPT2-WIRE " + sent.length() + " chars ~" + (sent.length() / 4) + " tokens"
    );
    assertTrue(sent.contains("\"max_tokens\":16384"), "上限必须写到 wire 上");
    assertTrue(sent.contains("\"reasoning_effort\":\"low\""), "推理强度必须写到 wire 上");

    // ---- 绝不触发 Stage 1 ----
    assertFalse(
      sent.contains("fact extraction stage"),
      "不得出现 Stage 1 的抽取提示词"
    );
    assertTrue(
      sent.contains("VALIDATED FACTS from stage 1"),
      "必须是 Stage 2 的推理提示词"
    );
    for (var id : StageOneSnapshot.FACT_IDS) assertTrue(
      sent.contains(id.toString()),
      "Stage 1 的每个 fact id 都必须出现在提示词中: " + id
    );

    // ---- 审计 ----
    var audited = runs.recent(job.id());
    assertEquals(1, audited.size(), "一次尝试一行审计");
    assertEquals(ModelPurpose.SYNTHESIS, audited.getFirst().purpose());
    assertEquals("SUCCEEDED", audited.getFirst().status());

    // ---- 结果与业务规则 ----
    assertEquals("协议到期推动本季度续约谈判。", probe.reasoning().summary());
    assertEquals(1, probe.reasoning().variables().size());
    assertEquals(1, probe.reasoning().hypotheses().size());
    assertEquals(
      List.of(StageOneSnapshot.FACT_IDS[0]),
      probe.reasoning().variables().getFirst().factRefs(),
      "factRefs 必须指向 Stage 1 的真实 fact id"
    );
    assertEquals(
      ProtocolModel.Direction.UNCHANGED,
      probe.reasoning().variables().getFirst().direction()
    );
  }

  /**
   * 独立语义检查：Stage 2 的字段名必须与正式 contract 的数组名一致。
   *
   * <p>不依赖 {@code StageReplySchema}（它是同源派生，自证无效）：这里直接读生成的
   * {@code AnalysisResult}，把它的数组字段与 Stage 2 的 DTO 组件比对。
   */
  @Test
  void stageTwoFieldNamesMatchTheFormalContract() {
    var contractArrays = new LinkedHashSet<String>();
    for (var component : com.signalframe.contract.AnalysisResult.class.getRecordComponents()) {
      if (List.class.isAssignableFrom(component.getType())) contractArrays.add(component.getName());
    }
    // facts 由 Stage 1 产出，Stage 2 不重复生成
    contractArrays.remove("facts");
    // upcomingObservations 是快照级的 reading list（EPISTEMIC_TYPES §5.4），由投影层
    // 填充而不是 Stage 2 产出；本检查第一次运行正是靠它发现"同源派生 schema 看不到
    // DTO 缺字段"这一盲区，因此这里显式记录而不是静默忽略。
    contractArrays.remove("upcomingObservations");

    var stageTwoArrays = new LinkedHashSet<String>();
    for (var component : TwoStageModel.Reasoning.class.getRecordComponents()) {
      if (List.class.isAssignableFrom(component.getType())) stageTwoArrays.add(component.getName());
    }

    assertEquals(
      contractArrays,
      stageTwoArrays,
      "Stage 2 的数组名必须与正式 contract 完全一致；差异即为契约语义不一致"
    );
    assertTrue(contractArrays.contains("verificationIndicators"), "plan 的契约名是 verificationIndicators");
    assertTrue(contractArrays.contains("alternativeExplanations"), "备择解释的契约名");
    assertTrue(contractArrays.contains("corroboratingSignals"), "信号的契约名");
  }

  /**
   * 最小输出诊断：guidance 注入而参数不变。
   *
   * <p>这是诊断实验的离线证明：诊断只通过 guidance 通道改变输出内容要求，不修改
   * prompt 模板、不改契约、不改参数、不触发 Stage 1，也不会引发第二次隐式请求。
   */
  @Test
  void theDiagnosticGuidanceIsInjectedWithoutChangingAnyParameter() {
    var news = newsService.create(new NewsInput(null, StageOneSnapshot.TEXT, "stage2 diagnostic"));
    var job = jobs.create(news.id(), "stage2-diagnostic-offline");
    var state = StageOneSnapshot.state(job.id(), news);
    canned.set(reasoningReply());

    var probe = twoStage.reasoningOnly(
      job.id(),
      state.correlationId(),
      state,
      Stage2MinimalOutputDiagnosticTest.GUIDANCE
    );

    var sent = stub.bodies().getFirst();
    assertTrue(
      sent.contains("DIAGNOSTIC RUN (not an acceptance run)"),
      "guidance 必须被追加到提示词"
    );
    assertTrue(sent.contains("\"max_tokens\":16384"), "诊断不得改变输出上限");
    assertTrue(sent.contains("\"reasoning_effort\":\"low\""), "诊断不得改变推理强度");
    assertFalse(sent.contains("fact extraction stage"), "诊断不得触发 Stage 1");
    assertEquals(1, probe.providerCalls(), "诊断恰好一次请求");
    assertEquals(1, runs.recent(job.id()).size(), "诊断恰好一行审计");
  }

  // ---- 任务一：Stage 1 的 unknown 不得被 Stage 2 覆盖 ----

  @Test
  void stageOneUnknownsSurviveWhenStageTwoDoesNotResolveThem() {
    var merged = TwoStageSynthesisService.mergeUnknowns(
      StageOneSnapshot.unknowns(),
      List.of()
    );

    assertEquals(2, merged.size(), "Stage 1 的 2 条 unknown 必须完整保留");
    assertEquals(StageOneSnapshot.UNKNOWNS[0], merged.get(0).statement());
    assertEquals(StageOneSnapshot.UNKNOWNS[1], merged.get(1).statement());
  }

  @Test
  void aRestatedUnknownIsMergedNotDuplicated() {
    var stageOne = StageOneSnapshot.unknowns();
    var restated = new ProtocolModel.Unknown(
      null,
      StageOneSnapshot.UNKNOWNS[0] + "。", // 同一条，仅标点不同
      "Stage 2 认为同样未确定",
      "出版方的原始公告",
      List.of()
    );

    var merged = TwoStageSynthesisService.mergeUnknowns(stageOne, List.of(restated));

    assertEquals(2, merged.size(), "复述同一条不得变成三条");
    assertEquals(
      stageOne.getFirst().id(),
      merged.getFirst().id(),
      "保留 Stage 1 的来源 id"
    );
    assertEquals(StageOneSnapshot.UNKNOWNS[1], merged.get(1).statement());
  }

  @Test
  void aNewStageTwoUnknownIsAppendedAfterStageOne() {
    var extra = new ProtocolModel.Unknown(
      null, "谈判对手的立场未知", "来源未提及", "对方公告", List.of()
    );

    var merged = TwoStageSynthesisService.mergeUnknowns(
      StageOneSnapshot.unknowns(),
      List.of(extra)
    );

    assertEquals(3, merged.size(), "新的未确定项必须追加而不是替换");
    assertEquals("谈判对手的立场未知", merged.get(2).statement());
  }

  // ---- 任务四：诊断状态机 ----

  @Test
  void theDiagnosticVerdictOnlyCallsATimeoutATimeout() {
    assertEquals(
      "TIMED_OUT",
      Stage2MinimalOutputDiagnosticTest.verdict("TIMEOUT", false),
      "只有真正的超时才叫 TIMEOUT"
    );
    assertEquals(
      "PROVIDER_PASS_SCHEMA_OR_RULE_FAIL",
      Stage2MinimalOutputDiagnosticTest.verdict("OUTPUT_INVALID", true),
      "provider 已返回时不得记成超时"
    );
    assertEquals(
      "PROVIDER_FAILED_NO_RESPONSE",
      Stage2MinimalOutputDiagnosticTest.verdict(null, false)
    );
  }

  /** 未知字段仍被拒绝，严格性没有被 schema 修复放宽。 */
  @Test
  void unknownFieldsAreStillRejectedAfterTheSchemaRepair() {
    var news = newsService.create(new NewsInput(null, StageOneSnapshot.TEXT, "stage2 strict"));
    var job = jobs.create(news.id(), "stage2-strict");
    var state = StageOneSnapshot.state(job.id(), news);
    canned.set(reasoningReply().replace("{\"summary\":", "{\"extraRootKey\":1,\"summary\":"));

    var failure = assertThrows(StageFailure.class, () ->
      twoStage.reasoningOnly(job.id(), state.correlationId(), state, null)
    );

    assertTrue(
      failure.jobMessage().contains("SCHEMA") || failure.jobMessage().contains("校验"),
      "未知字段必须被拒绝: " + failure.jobMessage()
    );
  }
}
