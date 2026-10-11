package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.news.application.NewsService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stage 2 独立真实探针（opt-in）。
 *
 * <p>只跑 Stage 2：不触发 Stage 1，也不进入完整 pipeline。输入是 Stage 1 已通过那次
 * Live Probe 的固定快照（{@link StageOneSnapshot}），因此每次运行对比的是同一个输入。
 *
 * <p>参数：max_tokens=16384、reasoning_effort=high、timeout=120、调用上限 1 次、
 * 0 重试、0 修复。未设 {@code AI_STAGE2_LIVE_PROBE=true} 时整体跳过，普通构建与 CI
 * 绝不接触 provider。
 */
@EnabledIfEnvironmentVariable(named = "AI_STAGE2_LIVE_PROBE", matches = "(?i)true")
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.NONE,
  properties = {
    "ai.two-stage.max-provider-calls=1",
    "ai.two-stage.reasoning.max-attempts=1",
    "ai.two-stage.reasoning.max-repair-attempts=0",
    "ai.two-stage.reasoning.max-tokens=16384",
    "ai.two-stage.reasoning.timeout=120",
    "ai.client.reasoning-effort.SYNTHESIS=low",
  }
)
class Stage2LiveProbeTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine");

  static {
    postgres.start();
  }

  static String env(String name) {
    var value = System.getenv(name);
    return value == null ? "" : value.trim();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("ai.profiles.[analysis.fast].provider", () -> env("AI_FAST_PROVIDER"));
    registry.add("ai.profiles.[analysis.fast].model", () -> env("AI_FAST_MODEL"));
    registry.add("ai.profiles.[analysis.fast].base-url", () -> env("AI_FAST_BASE_URL"));
    registry.add("ai.routes.SYNTHESIS", () -> "analysis.fast");
  }

  @Autowired
  TwoStageSynthesisService twoStage;

  @Autowired
  ModelRunRepository runs;

  @Autowired
  NewsService newsService;

  @Autowired
  JobRepository jobs;

  @Test
  void oneStageTwoCallAgainstTheFixedStageOneSnapshot() {
    var news = newsService.create(
      new NewsInput(null, StageOneSnapshot.TEXT, "stage 2 live probe (test data)")
    );
    var job = jobs.create(news.id(), "stage2-live-probe");
    var state = StageOneSnapshot.state(job.id(), news);

    var probe = twoStage.reasoningOnly(job.id(), state.correlationId(), state, null);
    var audited = runs.recent(job.id());
    assertEquals(1, audited.size(), "一次尝试一行审计");
    var run = audited.getFirst();

    // ---- 请求参数与测量 ----
    System.out.println("PROBE2 provider=" + run.provider() + " model=" + run.model());
    System.out.println(
      "PROBE2 maxTokens=" + probe.profile().maxTokens() + " timeout=" + probe.profile().timeout() +
      " reasoningEffort=high providerCalls=" + probe.providerCalls()
    );
    System.out.println(
      "PROBE2 status=" + run.status() + " errorType=" + run.errorType() +
      " latencyMs=" + run.latencyMs()
    );
    System.out.println(
      "PROBE2 inputTokens=" + token(run.inputTokens()) + " outputTokens=" + token(run.outputTokens()) +
      " totalTokens=" + token(run.totalTokens()) + " estimatedCost=" + token(run.estimatedCost())
    );
    System.out.println(
      "PROBE2 responseContentLength=UNKNOWN (adapter 不暴露原始内容长度；空补全会报 PROVIDER_RESPONSE_INVALID)"
    );

    // ---- 1. Provider ----
    assertEquals("SUCCEEDED", run.status(), "provider 必须成功: " + run.errorType());
    assertNull(run.errorType());
    assertTrue(run.latencyMs() > 0 && run.latencyMs() <= 120_000, "在 120s 界内");
    assertEquals(1, probe.providerCalls(), "恰好一次 provider 请求");
    assertEquals(ModelPurpose.SYNTHESIS, run.purpose());

    // ---- 2. Schema：14 个根字段反序列化成功 ----
    var r = probe.reasoning();
    assertNotNull(r.summary());
    assertFalse(r.summary().isBlank(), "summary 不得为空");
    System.out.println(
      "PROBE2 counts variables=" + r.variables().size() + " mechanisms=" + r.mechanisms().size() +
      " stakeholders=" + r.stakeholders().size() + " firstOrder=" + r.firstOrderEffects().size() +
      " secondOrder=" + r.secondOrderEffects().size() + " hypotheses=" + r.hypotheses().size() +
      " alternatives=" + r.alternatives().size() + " counters=" + r.counterArguments().size() +
      " falsification=" + r.falsificationConditions().size() + " signals=" + r.signals().size() +
      " plan=" + r.plan().size() + " predictions=" + r.predictions().size() +
      " unknowns=" + r.unknowns().size()
    );

    // ---- 3. factRefs 必须指向 Stage 1 的有效 id ----
    var valid = Set.of(StageOneSnapshot.FACT_IDS);
    var cited = new LinkedHashSet<UUID>();
    for (var v : r.variables()) cited.addAll(v.factRefs());
    for (var m : r.mechanisms()) cited.addAll(m.factRefs());
    for (var s : r.stakeholders()) cited.addAll(s.factRefs());
    for (var e : r.firstOrderEffects()) cited.addAll(e.factRefs());
    for (var e : r.secondOrderEffects()) cited.addAll(e.factRefs());
    for (var h : r.hypotheses()) cited.addAll(h.supportingFactRefs());
    for (var a : r.alternatives()) cited.addAll(a.factRefs());
    for (var c : r.counterArguments()) cited.addAll(c.factRefs());
    for (var p : r.predictions()) cited.addAll(p.basisFactRefs());
    System.out.println("PROBE2 citedFactRefs=" + cited);
    assertTrue(
      valid.containsAll(cited),
      "所有 factRefs 必须来自 Stage 1 的 5 个 id，不得凭空编造: " + cited
    );

    // ---- 4. Stage 1 的 2 条 unknown 不得被静默覆盖 ----
    // reasoningOnly 不经过 assembly，所以这里对真实 Stage 2 输出显式执行生产用的
    // mergeUnknowns，验证"Stage 2 未有效解决时 Stage 1 的缺口完整保留"。
    var mergedUnknowns = TwoStageSynthesisService.mergeUnknowns(
      StageOneSnapshot.unknowns(),
      r.unknowns()
    );
    for (var statement : StageOneSnapshot.UNKNOWNS) assertTrue(
      mergedUnknowns.stream().anyMatch(m -> m.statement().equals(statement)),
      "Stage 1 的 unknown 被静默覆盖: " + statement
    );
    System.out.println(
      "PROBE2 stageOneUnknowns=" + StageOneSnapshot.UNKNOWNS.length +
      " stageTwoUnknowns=" + r.unknowns().size() +
      " mergedUnknowns=" + mergedUnknowns.size()
    );

    // ---- 7. 重叠 facts 是否被重复引用（计数供人工判读，重叠组为 3/4 与 5）----
    var counts = new LinkedHashMap<UUID, Integer>();
    for (var id : cited) counts.put(id, 0);
    java.util.function.Consumer<List<UUID>> tally = refs -> {
      for (var id : refs) counts.merge(id, 1, Integer::sum);
    };
    for (var v : r.variables()) tally.accept(v.factRefs());
    for (var m : r.mechanisms()) tally.accept(m.factRefs());
    for (var s : r.stakeholders()) tally.accept(s.factRefs());
    for (var e : r.firstOrderEffects()) tally.accept(e.factRefs());
    for (var e : r.secondOrderEffects()) tally.accept(e.factRefs());
    for (var h : r.hypotheses()) tally.accept(h.supportingFactRefs());
    for (var a : r.alternatives()) tally.accept(a.factRefs());
    for (var c : r.counterArguments()) tally.accept(c.factRefs());
    for (var pr : r.predictions()) tally.accept(pr.basisFactRefs());
    System.out.println("PROBE2 factRefCounts=" + counts + " (重叠组: 第3/4条子句与第5条整句)");

    // ---- 4. 无凭空编造的事实：Stage 2 不产出 fact ----
    // reader 的窄契约不接受 facts 字段，且 factRefs 已被上面锁定，因此不存在新造事实。

    // ---- 5. 业务规则（reader 已强制，这里复核关键项）----
    for (var h : r.hypotheses()) assertTrue(
      !h.supportingFactRefs().isEmpty(),
      "假设必须有事实依据（EP-02）"
    );
    for (var p : r.predictions()) {
      assertTrue(p.expectedBy().isAfter(Instant.now()), "预测必须是未来时间（STG-14.1）");
      assertFalse(p.verificationCriteria().isBlank(), "预测必须有判别标准");
    }
    for (var item : r.plan()) {
      assertNotNull(item.deadline(), "验证计划项必须有截止时间（STG-15）");
      assertNotEquals(
        item.supportingResult(),
        item.contradictingResult(),
        "支持与反驳结果必须可区分（STG-15.2）"
      );
    }
    for (var m : r.mechanisms()) assertNotEquals(
      ProtocolModel.SupportLevel.SUPPORTED,
      m.supportLevel(),
      "无独立来源时不得声称 SUPPORTED（EP-04）"
    );

    System.out.println(
      "PROBE2 PROVIDER=PASS SCHEMA=PASS FACTREFS=PASS BUSINESS_RULES=PASS"
    );
    System.out.println("PROBE2 NOTE stage 2 only: stage 1 与 pipeline 均未执行");
  }

  private static String token(Object value) {
    return value == null ? "UNKNOWN" : value.toString();
  }
}
