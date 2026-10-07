package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Deterministic offline adapter.
 *
 * <p>It needs no credential, so the router can fall back to it whenever a
 * profile has no key configured. Output is a structurally valid analysis
 * snapshot that repeats the supplied source as a reported claim and states that
 * nothing was independently verified.
 */
@Component
public class MockModelGateway implements ModelGateway, ModelProviderAdapter {

  static final String PROVIDER = "mock";

  private final JsonCodec json;

  public MockModelGateway(JsonCodec json) {
    this.json = json;
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public Set<ModelCapability> capabilities() {
    return Set.of(ModelCapability.STRUCTURED_OUTPUT);
  }

  @Override
  public boolean requiresCredentials() {
    return false;
  }

  @Override
  public ModelResponse call(
    ModelRequest request,
    ModelCredentials credentials
  ) {
    return call(request);
  }

  public ModelResponse call(ModelRequest request) {
    var source = request.news().source();
    String quote = source
      .text()
      .substring(0, Math.min(200, source.text().length()));
    var refs = List.of(new SourceRef(source.id(), quote, 0, quote.length()));
    Instant now = Instant.now();
    var fact = new Fact(
      UUID.randomUUID(),
      ClaimType.FACT,
      quote,
      "用户提供的原文陈述，尚未独立核实。",
      40,
      refs
    );
    var unknown = new Statement(
      ClaimType.INFERENCE,
      "需要独立来源验证",
      "Mock 仅演示结构与流程，不生成真实研究判断。",
      20,
      refs
    );
    var variable = new Variable(
      UUID.randomUUID(),
      "报道涉及的核心变量（待识别）",
      "UNKNOWN",
      ClaimType.INFERENCE,
      "核心变量仍需研究",
      "演示占位，不推断方向。",
      20,
      refs
    );
    var hypothesis = new Hypothesis(
      UUID.randomUUID(),
      "待验证：报道中的变化是否持续",
      "需要后续证据确认变化的持续性。",
      "OPEN",
      "仅有单一输入来源，缺少独立验证。",
      now,
      now,
      ClaimType.HYPOTHESIS,
      "报道中的变化可能持续",
      "演示假设，不代表研究结论。",
      30,
      refs
    );
    var indicator = new Indicator(
      UUID.randomUUID(),
      null,
      "独立来源复核",
      "寻找原始公告与可重复数据",
      "后续研究",
      ClaimType.INFERENCE,
      "复核同一报道的核心主张",
      "当前没有独立证据。",
      20,
      refs
    );
    var result = new AnalysisResult(
      "演示分析 · " + request.news().title(),
      List.of(fact),
      List.of(variable),
      List.of(),
      List.of(),
      List.of(unknown),
      List.of(),
      List.of(hypothesis),
      List.of(unknown),
      List.of(unknown),
      List.of(
        new Statement(
          ClaimType.INFERENCE,
          "独立原始记录与报道不一致时，放弃该假设。",
          "可证伪条件示例。",
          30,
          refs
        )
      ),
      List.of(),
      List.of(indicator),
      List.of(unknown),
      new ConfidenceAssessment(30, "Mock 演示：单一来源，未独立验证。", false),
      List.of(unknown),
      false,
      true
    );
    return new ModelResponse(
      json.write(result),
      new ModelUsage(0L, 0L, 0L, 0.0),
      PROVIDER,
      "mock-v1"
    );
  }
}
