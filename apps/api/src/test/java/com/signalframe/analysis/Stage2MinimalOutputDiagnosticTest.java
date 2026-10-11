package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.news.application.NewsService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 诊断实验（<b>不是</b> Stage 2 验收）：区分"输出规模瓶颈"与"上游/传输问题"。
 *
 * <p>两次真实请求都在 120.3s 撞上客户端上限，且降低 reasoning effort 无效。本实验只
 * 改一件事：通过已有的 {@code guidance} 通道要求模型生成<b>符合正式契约的最小合法
 * 回复</b>——不修改 prompt 模板、不改契约、不改生产路由、不降低校验。
 *
 * <p>判据：若最小输出能在 120s 内返回，瓶颈在输出规模；若仍然超时，则与输出量无关，
 * 问题在上游/传输侧。无论结果如何，<b>本测试都不构成 Stage 2 LIVE PASS</b>。
 */
@EnabledIfEnvironmentVariable(named = "AI_STAGE2_DIAGNOSTIC", matches = "(?i)true")
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
class Stage2MinimalOutputDiagnosticTest {

  /**
   * 最小合法输出要求。逐项依据正式契约：
   * <ul>
   *   <li>{@code summary} 必须非空；</li>
   *   <li>{@code hypotheses} 恰好 1 条，且必须引用 Stage 1 的真实 fact id（EP-02）；</li>
   *   <li>{@code falsificationConditions} 恰好 1 条服务该假设（STG-13）；</li>
   *   <li>{@code unknowns} 至少 1 条，显式记录未确定项（PR-05）；</li>
   *   <li>其余数组返回空数组：协议允许"评估过但无发现"，不得为填充而编造。</li>
   * </ul>
   */
  static final String GUIDANCE =
    "DIAGNOSTIC RUN (not an acceptance run). Produce the smallest reply that is still valid " +
    "under the contract: a one-sentence summary; exactly one hypothesis that cites one of the " +
    "validated fact ids above and states its reasoning; exactly one falsification condition that " +
    "serves that hypothesis; at least one unknown recording what the source does not establish. " +
    "Return an empty array [] for every other array. Do not invent content to fill an array, and " +
    "do not omit any of the required keys.";

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
  void minimalOutputDiagnostic() {
    var news = newsService.create(
      new NewsInput(null, StageOneSnapshot.TEXT, "stage 2 minimal-output diagnostic (test data)")
    );
    var job = jobs.create(news.id(), "stage2-diagnostic");
    var state = StageOneSnapshot.state(job.id(), news);

    var startedAt = System.nanoTime();
    try {
      var probe = twoStage.reasoningOnly(
        job.id(),
        state.correlationId(),
        state,
        GUIDANCE
      );
      var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      var run = runs.recent(job.id()).getFirst();

      // ---- 可观测的测量 ----
      System.out.println("DIAG2 model=" + run.model() + " maxTokens=" + probe.profile().maxTokens() +
        " timeout=" + probe.profile().timeout() + " reasoningEffort=low providerCalls=" + probe.providerCalls());
      System.out.println("DIAG2 status=" + run.status() + " latencyMs=" + run.latencyMs() +
        " wallClockMs=" + elapsedMs);
      System.out.println("DIAG2 inputTokens=" + token(run.inputTokens()) +
        " outputTokens=" + token(run.outputTokens()) + " totalTokens=" + token(run.totalTokens()) +
        " estimatedCost=" + token(run.estimatedCost()));
      System.out.println("DIAG2 counts variables=" + probe.reasoning().variables().size() +
        " mechanisms=" + probe.reasoning().mechanisms().size() +
        " hypotheses=" + probe.reasoning().hypotheses().size() +
        " falsification=" + probe.reasoning().falsificationConditions().size() +
        " unknowns=" + probe.reasoning().unknowns().size());
      System.out.println(
        "DIAG2 verdict=" + verdict(run.errorType(), true) +
        " (latencyMs=" + run.latencyMs() + " < timeout=120000)"
      );
      System.out.println("DIAG2 meaning=provider 在超时前返回；瓶颈在输出规模而非上游/传输");
      System.out.println("DIAG2 NOTE 这是诊断运行，不构成 Stage 2 LIVE PASS");
    } catch (RuntimeException failure) {
      var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      var audited = runs.recent(job.id());
      var run = audited.isEmpty() ? null : audited.getFirst();
      System.out.println("DIAG2 FAILED wallClockMs=" + elapsedMs +
        " errorType=" + (run == null ? "UNKNOWN" : run.errorType()) +
        " latencyMs=" + (run == null ? "UNKNOWN" : run.latencyMs()));
      System.out.println("DIAG2 failure=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
      var errorType = run == null ? "UNKNOWN" : run.errorType();
      System.out.println(
        "DIAG2 verdict=" + verdict(errorType, run != null) + " (errorType=" + errorType + ")"
      );
      System.out.println(
        "DIAG2 meaning=" + ("TIMEOUT".equals(errorType)
          ? "未在 120s 内返回：与输出规模无关，问题在上游/传输侧"
          : "provider 已返回但在超时前校验失败：不是超时，需按 errorType 单独处理")
      );
      System.out.println("DIAG2 NOTE 这是诊断运行，不构成 Stage 2 LIVE PASS");
      // 诊断实验记录结果即可，不用断言把超时变成构建失败
    }

    // ---- 无法观测的指标，明确标记 ----
    System.out.println("DIAG2 firstByteMs=UNKNOWN (同步非流式调用，SDK 读取完整响应后才返回)");
    System.out.println("DIAG2 connectMs=UNKNOWN (无连接层观测点)");
    System.out.println("DIAG2 httpStatus=UNKNOWN (超时无响应) requestId=UNKNOWN (超时无响应)");
    System.out.println("DIAG2 finishReason=UNKNOWN (adapter 未暴露；空补全会报 PROVIDER_RESPONSE_INVALID)");
    System.out.println("DIAG2 responseContentLength=UNKNOWN (adapter 未暴露)");

    // 无论结果如何都不断言成功：本测试只产出诊断记录
    assertEquals(1, runs.recent(job.id()).size(), "诊断运行必须恰好一次 provider 请求");
  }

  /**
   * 诊断状态机：只有真正的超时才叫 TIMEOUT。
   *
   * <p>此前的实现把任何异常都标成 TIMED_OUT_WITH_MINIMAL_OUTPUT，于是一次 16.4s 内
   * 正常返回、只是 schema 校验失败的运行被错误地记成了超时。
   */
  static String verdict(String errorType, boolean responded) {
    if ("TIMEOUT".equals(errorType)) return "TIMED_OUT";
    if (responded) return "PROVIDER_PASS_SCHEMA_OR_RULE_FAIL";
    return "PROVIDER_FAILED_NO_RESPONSE";
  }

  private static String token(Object value) {
    return value == null ? "UNKNOWN" : value.toString();
  }
}
