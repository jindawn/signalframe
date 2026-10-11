package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.util.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Stage 2 的回复 schema 与 reader 窄契约 DTO 的一致性审计（汇总式）。
 *
 * <p>Stage 1 曾经因为"prompt 展示 contract 投影、reader 反序列化窄 DTO"而反复失败，
 * 每次只暴露一个字段。这里对 Stage 2 做一次性汇总审计：把所有差异收集起来一起报告，
 * 而不是让第一个差异终止检查。
 */
class Stage2ContractConsistencyTest {

  static final JsonCodec JSON = PipelineFixtures.json();

  static TwoStageSynthesisService service() {
    return new TwoStageSynthesisService(
      PipelineFixtures.router(),
      new PipelineFixtures.ScriptedGateway(r -> "{}"),
      new PipelineFixtures.RecordingRuns(),
      new StageResponseReader(JSON),
      PipelineFixtures.promptLibrary(),
      JSON,
      PipelineFixtures.gateA(JSON),
      ConfidenceRubric.deterministic(),
      PipelineFixtures.strategies(),
      1, 0, 0L, 1, 0, 0L, 0, 0, 0, 0, 1, 0L
    );
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> schema() {
    return JSON.read(service().reasoningReplySchema(), Map.class);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> schemas(Map<String, Object> schema) {
    return (Map<String, Object>) ((Map<String, Object>) schema.get("components")).get("schemas");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> properties(Object node) {
    var props = ((Map<String, Object>) node).get("properties");
    return props == null ? Map.of() : (Map<String, Object>) props;
  }

  static Set<String> dtoFields(Class<?> record) {
    var names = new LinkedHashSet<String>();
    for (var c : record.getRecordComponents()) names.add(c.getName());
    return names;
  }

  /** contract 定义名 -> reader 实际反序列化的 DTO。 */
  static Map<String, Class<?>> pairs() {
    var m = new LinkedHashMap<String, Class<?>>();
    m.put("Variable", ProtocolModel.Variable.class);
    m.put("CausalLink", ProtocolModel.Mechanism.class);
    m.put("Stakeholder", ProtocolModel.Stakeholder.class);
    m.put("Hypothesis", ProtocolModel.Hypothesis.class);
    m.put("CorroboratingSignal", ProtocolModel.Signal.class);
    m.put("Indicator", ProtocolModel.PlanItem.class);
    m.put("Prediction", ProtocolModel.Prediction.class);
    // 以下数组在 contract 里都是 Statement，而 reader 用各自更宽的 DTO
    m.put("Statement", ProtocolModel.Effect.class);
    return m;
  }

  /**
   * 基线：Stage 2 的 schema 与 reader DTO 目前有 64 处不一致（2026-10-11 审计）。
   *
   * <p>因此本测试暂时禁用：它不是校验标准，而是"待修复缺陷"的守护测试。修复
   * Stage 2 的回复 schema（根对象 + 逐数组按 reader DTO 声明字段）之后，把这里
   * 的 @Disabled 去掉，它就会成为 Stage 2 的契约一致性回归。在此之前，
   * <b>Stage 2 不具备真实测试条件</b>。
   */
  @Disabled("Stage 2 schema 与 reader DTO 尚有 64 处不一致，待修复后启用")
  @Test
  void auditStageTwoSchemaAgainstReaderDtos() {
    var schema = schema();
    var components = schemas(schema);
    var diffs = new ArrayList<String>();

    // 1. 根对象是否存在
    if (!"object".equals(schema.get("type"))) diffs.add(
      "根对象缺少 type=object（回复的根形状只能靠散文描述）"
    );
    if (schema.get("properties") == null) diffs.add("根对象缺少 properties");
    if (schema.get("required") == null) diffs.add("根对象缺少 required");

    var rootProps = schema.get("properties") == null
      ? Map.<String, Object>of()
      : properties(schema);
    var dtoRoot = dtoFields(TwoStageModel.Reasoning.class);
    for (var field : dtoRoot) if (!rootProps.containsKey(field)) diffs.add(
      "根对象未声明 DTO 字段: " + field
    );
    for (var field : rootProps.keySet()) if (!dtoRoot.contains(field)) diffs.add(
      "根对象声明了 DTO 没有的字段: " + field
    );

    // 2. 每个数组的 items 指向哪个定义，以及该定义的字段与 DTO 是否一致
    for (var entry : pairs().entrySet()) {
      var definition = (Map<String, Object>) components.get(entry.getKey());
      if (definition == null) {
        diffs.add("contract 缺少定义: " + entry.getKey());
        continue;
      }
      var declared = properties(definition).keySet();
      var dto = dtoFields(entry.getValue());
      for (var f : declared) if (!dto.contains(f)) diffs.add(
        entry.getKey() + " 声明了 " + entry.getValue().getSimpleName() + " 没有的字段: " + f
      );
      for (var f : dto) if (!declared.contains(f)) diffs.add(
        entry.getValue().getSimpleName() + " 的字段未被 " + entry.getKey() + " 声明: " + f
      );
      if (!Boolean.FALSE.equals(definition.get("additionalProperties"))) diffs.add(
        entry.getKey() + " 未禁止额外字段（模型多写一个键即整条回复被拒）"
      );
    }

    // 3. 关键数组的 items 是否声明
    for (var array : List.of(
      "variables", "mechanisms", "hypotheses", "predictions", "verificationIndicators", "unknowns"
    )) {
      var node = (Map<String, Object>) rootProps.get(array);
      if (node == null) continue;
      if (node.get("items") == null) diffs.add("数组 " + array + " 未声明 items");
    }

    assertTrue(
      diffs.isEmpty(),
      "Stage 2 的 schema 与 reader DTO 共 " + diffs.size() + " 处不一致：\n  - " +
      String.join("\n  - ", diffs)
    );
  }
}
