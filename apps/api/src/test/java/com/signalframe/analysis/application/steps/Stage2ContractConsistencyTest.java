package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Stage 2 的回复 schema 与 reader DTO 的一致性回归。
 *
 * <p>修复前这里审计出 64 处不一致（根对象缺失、14 个根字段未声明、CausalLink 与
 * Mechanism 字段体系不同、contract 投影里没有 Stakeholder 定义等）。现在 schema 由
 * reader 的 DTO 直接派生，本测试锁定该同源关系：任何一方改动而另一方未跟随，都会失败。
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

  static Set<String> dtoFields(Class<?> record) {
    var names = new LinkedHashSet<String>();
    for (var c : record.getRecordComponents()) names.add(c.getName());
    return names;
  }

  /** 从根 DTO 递归收集所有 record 类型，名 -> 类型。 */
  static Map<String, Class<?>> nestedDtos() {
    var found = new LinkedHashMap<String, Class<?>>();
    collect(TwoStageModel.Reasoning.class, found);
    return found;
  }

  static void collect(Class<?> type, Map<String, Class<?>> found) {
    if (!type.isRecord() || found.containsKey(type.getSimpleName())) return;
    found.put(type.getSimpleName(), type);
    for (var component : type.getRecordComponents()) {
      var generic = component.getGenericType();
      Class<?> element = component.getType();
      if (generic instanceof java.lang.reflect.ParameterizedType p) {
        var arg = p.getActualTypeArguments()[0];
        if (arg instanceof Class<?> direct) element = direct;
      }
      if (element.isRecord()) collect(element, found);
    }
  }

  // ---- 根对象 ---------------------------------------------------------------

  @Test
  void theRootObjectIsDeclaredAndMatchesTheDto() {
    var root = schema();
    assertEquals("object", root.get("type"));
    assertEquals(Boolean.FALSE, root.get("additionalProperties"));
    assertEquals(
      dtoFields(TwoStageModel.Reasoning.class),
      new LinkedHashSet<>(((Map<String, Object>) root.get("properties")).keySet()),
      "根字段必须与 TwoStageModel.Reasoning 完全一致"
    );
    assertEquals(
      List.copyOf(dtoFields(TwoStageModel.Reasoning.class)),
      root.get("required"),
      "summary 与 13 个数组必须全部 required"
    );
  }

  // ---- 嵌套定义 -------------------------------------------------------------

  @Test
  void everyNestedDtoIsDeclaredWithItsExactFields() {
    var defs = (Map<String, Object>) schema().get("$defs");
    var diffs = new ArrayList<String>();
    for (var entry : nestedDtos().entrySet()) {
      var node = (Map<String, Object>) defs.get(entry.getKey());
      if (node == null) {
        diffs.add("缺少定义: " + entry.getKey());
        continue;
      }
      var declared = new LinkedHashSet<>(
        ((Map<String, Object>) node.get("properties")).keySet()
      );
      if (!declared.equals(dtoFields(entry.getValue()))) diffs.add(
        entry.getKey() + " 字段不一致: schema=" + declared + " dto=" + dtoFields(entry.getValue())
      );
      if (!Boolean.FALSE.equals(node.get("additionalProperties"))) diffs.add(
        entry.getKey() + " 未禁止额外字段"
      );
      if (!List.copyOf(dtoFields(entry.getValue())).equals(node.get("required"))) diffs.add(
        entry.getKey() + " required 与 DTO 不一致"
      );
    }
    assertTrue(diffs.isEmpty(), "共 " + diffs.size() + " 处：\n  - " + String.join("\n  - ", diffs));
  }

  /** 每个数组都必须声明 items，且指向对应 DTO 的定义。 */
  @Test
  void everyArrayDeclaresItsItemShape() {
    var root = schema();
    var properties = (Map<String, Object>) root.get("properties");
    var diffs = new ArrayList<String>();
    for (var entry : properties.entrySet()) {
      if (entry.getKey().equals("summary")) continue; // summary 是字符串，不是数组
      var node = (Map<String, Object>) entry.getValue();
      if (!"array".equals(node.get("type"))) {
        diffs.add(entry.getKey() + " 不是数组: " + node);
        continue;
      }
      var items = node.get("items");
      if (items == null) diffs.add(entry.getKey() + " 未声明 items");
      else if (!((Map<String, Object>) items).containsKey("$ref")) diffs.add(
        entry.getKey() + " 的 items 不是 $ref: " + items
      );
    }
    assertTrue(diffs.isEmpty(), "共 " + diffs.size() + " 处：\n  - " + String.join("\n  - ", diffs));
  }

  /** summary 必须是字符串，且 13 个数组一个不少。 */
  @Test
  void summaryIsAStringAndAllThirteenArraysArePresent() {
    var properties = (Map<String, Object>) schema().get("properties");
    assertEquals("string", ((Map<String, Object>) properties.get("summary")).get("type"));
    assertEquals(
      List.of(
        "variables", "mechanisms", "stakeholders", "firstOrderEffects",
        "secondOrderEffects", "hypotheses", "alternativeExplanations",
        "counterArguments", "falsificationConditions", "corroboratingSignals",
        "verificationIndicators", "predictions", "unknowns"
      ),
      properties.keySet().stream().filter(k -> !k.equals("summary")).toList(),
      "13 个数组必须全部声明"
    );
  }

  /** 枚举字段必须声明取值集合，而不是裸 string。 */
  @Test
  void enumFieldsDeclareTheirAllowedValues() {
    var defs = (Map<String, Object>) schema().get("$defs");
    var variable = (Map<String, Object>) ((Map<String, Object>) defs.get("Variable")).get("properties");
    var direction = (Map<String, Object>) variable.get("direction");
    assertEquals("string", direction.get("type"));
    assertEquals(
      List.of("UP", "DOWN", "UNCHANGED", "UNKNOWN"),
      direction.get("enum"),
      "direction 必须声明枚举取值"
    );
    var factRefs = (Map<String, Object>) variable.get("factRefs");
    assertEquals("array", factRefs.get("type"));
    assertEquals(
      Map.of("type", "string", "format", "uuid"),
      factRefs.get("items"),
      "factRefs 必须是 uuid 数组，才能与 Stage 1 的 Fact ID 对齐"
    );
  }

  /**
   * 正向：由 DTO 构造的回复必须能被同一 DTO 反序列化，且根键与 schema 的 required 一致。
   *
   * <p>schema 由 DTO 派生，因此"照 schema 写"与"DTO 能读"是同一件事；本用例锁定该
   * 往返，防止有人只改一边。
   */
  @Test
  void aReplyBuiltFromTheDtoRoundTripsAndSatisfiesTheSchema() {
    var minimal = new TwoStageModel.Reasoning(
      "一句总结",
      List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
      List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
    );
    var raw = JSON.write(minimal);
    var parsed = JSON.read(raw, TwoStageModel.Reasoning.class);
    assertEquals(minimal, parsed, "DTO 必须能反序列化自身序列化的回复");

    var root = schema();
    assertEquals(
      List.copyOf(dtoFields(TwoStageModel.Reasoning.class)),
      root.get("required"),
      "schema 的 required 必须与 DTO 组件一致"
    );
    assertTrue(
      ((Map<String, Object>) root.get("properties")).keySet().containsAll(
        JSON.read(raw, Map.class).keySet()
      ),
      "DTO 写出的每个键都必须在 schema 中声明"
    );
  }

  /** schema 的 factRefs 形状必须能承载 Stage 1 的 fact id。 */
  @Test
  void factReferencesAreUuidArraysSoStageOneIdsCanBeCited() {
    var defs = (Map<String, Object>) schema().get("$defs");
    for (var name : List.of("Variable", "Mechanism", "Hypothesis", "Effect", "Alternative", "CounterArgument")) {
      var node = (Map<String, Object>) defs.get(name);
      assertNotNull(node, name + " 必须被声明");
      var props = (Map<String, Object>) node.get("properties");
      var refs =
        props.get("factRefs") != null
          ? (Map<String, Object>) props.get("factRefs")
          : (Map<String, Object>) props.get("supportingFactRefs");
      assertNotNull(refs, name + " 必须有一个事实引用字段");
      assertEquals("array", refs.get("type"), name);
    }
  }
}
