package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.SourceRef;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Stage 1 的回复 schema 与严格 DTO Reader 的契约一致性。
 *
 * <p>真实测试反复出现同一类失败：prompt 展示的 schema 与实际反序列化的 DTO 不一致，
 * 于是每次只暴露"下一个字段错误"。这里把两者程序化对齐，任何一处不一致都会在离线
 * 阶段失败，而不是等到花掉一次真实请求之后。
 *
 * <p>检查两个方向：schema 不得声明 DTO 没有的字段（否则照做的模型会被判未知字段），
 * DTO 的每个字段也必须在 schema 中声明（否则模型不知道要输出它）。
 */
class Stage1ContractConsistencyTest {

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
      1,
      0,
      0L,
      1,
      0,
      0L,
      0,
      0,
      0,
      0,
      1,
      0L
    );
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> schema() {
    return JSON.read(service().factsReplySchema(), Map.class);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> schemas(Map<String, Object> schema) {
    return (Map<String, Object>) ((Map<String, Object>) schema.get("components")).get(
      "schemas"
    );
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> properties(Map<String, Object> node) {
    var props = node.get("properties");
    return props == null ? Map.of() : (Map<String, Object>) props;
  }

  /** DTO 的字段名，来自 record 的规范构造器，不是手抄的列表。 */
  static Set<String> dtoFields(Class<?> record) {
    var names = new LinkedHashSet<String>();
    for (var component : record.getRecordComponents()) names.add(component.getName());
    return names;
  }

  // ---- 根对象 ---------------------------------------------------------------

  @Test
  void theRootObjectMatchesTheEnvelopeDto() {
    var root = schema();
    assertEquals("object", root.get("type"));
    assertEquals(Boolean.FALSE, root.get("additionalProperties"), "根对象必须禁止额外字段");
    assertEquals(List.of("facts"), root.get("required"));
    assertEquals(
      dtoFields(ProtocolModel.FactsEnvelope.class),
      properties(root).keySet(),
      "根字段必须与 FactsEnvelope 完全一致"
    );
  }

  // ---- Fact -----------------------------------------------------------------

  @Test
  void theFactSchemaMatchesTheFactDto() {
    var fact = schemas(schema()).get("Fact");
    assertNotNull(fact, "contract 的 Fact 定义必须在闭包内");

    var declared = properties((Map<String, Object>) fact).keySet();
    var dto = dtoFields(ProtocolModel.Fact.class);

    assertEquals(
      dto,
      declared,
      "Fact 的 schema 字段与 DTO 字段必须双向一致；差异即为下一次真实失败的来源"
    );
    assertEquals(
      Boolean.FALSE,
      ((Map<String, Object>) fact).get("additionalProperties"),
      "Fact 必须禁止额外字段，否则模型多写一个键就会让整条回复被拒"
    );
  }

  // ---- SourceRef ------------------------------------------------------------

  @Test
  void theSourceRefSchemaMatchesItsDto() {
    var ref = schemas(schema()).get("SourceRef");
    assertNotNull(ref, "contract 的 SourceRef 定义必须在闭包内");

    assertEquals(
      dtoFields(SourceRef.class),
      properties((Map<String, Object>) ref).keySet(),
      "SourceRef 的 schema 字段与 DTO 字段必须双向一致"
    );
  }

  // ---- Unknown --------------------------------------------------------------

  @Test
  void theUnknownItemSchemaMatchesItsDto() {
    var root = schema();
    var items =
      (Map<String, Object>) ((Map<String, Object>) properties(root).get("unknowns")).get(
        "items"
      );

    assertEquals(
      dtoFields(ProtocolModel.Unknown.class),
      properties(items).keySet(),
      "unknowns 项字段必须与 Unknown DTO 完全一致"
    );
    assertEquals(List.of("statement", "unknownReason"), items.get("required"));

    // 嵌套类型也必须声明：裸的 {"type":"array"} 会让模型自由发挥，
    // 而 DTO 期望 List<SourceRef>，形状不符即反序列化失败。
    var sourceRefs = (Map<String, Object>) properties(items).get("sourceRefs");
    assertEquals("array", sourceRefs.get("type"));
    assertEquals(
      Map.of("$ref", "#/components/schemas/SourceRef"),
      sourceRefs.get("items"),
      "unknowns[].sourceRefs 的 items 必须指向 SourceRef，而不是留空"
    );
    var id = (Map<String, Object>) properties(items).get("id");
    assertEquals("string", id.get("type"));
    assertEquals("uuid", id.get("format"), "id 必须声明为 uuid，避免非法 UUID 只在真实请求里暴露");
  }

  // ---- 来源引用字段 ---------------------------------------------------------

  @Test
  void everyFactFieldThatCarriesProvenanceIsDeclared() {
    var fact = (Map<String, Object>) schemas(schema()).get("Fact");
    var refs = (Map<String, Object>) properties(fact).get("sourceRefs");

    assertEquals("array", refs.get("type"));
    assertEquals(
      Map.of("$ref", "#/components/schemas/SourceRef"),
      refs.get("items"),
      "facts[].sourceRefs 必须指向 SourceRef"
    );
    assertTrue(
      ((List<?>) fact.get("required")).containsAll(
        List.of("id", "statement", "reasoning", "sourceRefs")
      ),
      "provenance 必需的字段必须在 required 中：" + fact.get("required")
    );
  }

  /**
   * 真实模型最可能输出的形状：facts 合规，且 unknowns 非空并带 SourceRef。
   *
   * <p>这是"schema 与 DTO 一致"的端到端证明：照 schema 写的每一项都必须能反序列化，
   * 而不是只在 unknowns 为空时才通过。
   */
  @Test
  void aReplyWithAStructuredUnknownPassesTheReader() {
    var text = "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";
    var span = text.substring(0, text.indexOf("双方"));
    var news = PipelineFixtures.news(text);
    var state = new SourceAssessmentStage().execute(
      PipelineState.initial(UUID.randomUUID(), "consistency-e2e", news, java.time.Instant.now())
    );
    var sourceId = news.source().id();
    var ref =
      "{\"sourceId\":\"" + sourceId + "\",\"quote\":" + JSON.write(span) +
      ",\"startOffset\":0,\"endOffset\":" + span.length() + "}";
    var raw =
      "{\"facts\":[{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"FACT\",\"statement\":" +
      JSON.write(span) + ",\"reasoning\":\"来源如此陈述\",\"confidence\":20,\"sourceRefs\":[" + ref +
      "],\"verificationStatus\":\"REPORTED\"}]," +
      "\"unknowns\":[{\"statement\":\"发布日期未知\",\"unknownReason\":\"来源未给出时间\"," +
      "\"wouldResolveWith\":\"出版方页面\",\"sourceRefs\":[" + ref + "]}]}";

    var artifacts = new StageResponseReader(JSON).facts(raw, state);

    assertEquals(1, artifacts.value().size(), "facts 必须通过");
    assertEquals(1, artifacts.unknowns().size(), "unknowns 必须被保留而不是被拒");
    assertEquals("发布日期未知", artifacts.unknowns().getFirst().statement());
  }

  // ---- 严格性没有被放宽 ------------------------------------------------------

  @Test
  void theReaderStillRejectsUnknownFields() {
    var state = new SourceAssessmentStage().execute(
      PipelineState.initial(
        UUID.randomUUID(),
        "consistency",
        PipelineFixtures.news("某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。"),
        java.time.Instant.now()
      )
    );
    var raw =
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"x\",\"reasoning\":\"y\",\"sourceRefs\":[],\"extraKey\":1}],\"unknowns\":[]}";

    var failure = assertThrows(
      StageOutputException.class,
      () -> new StageResponseReader(JSON).facts(raw, state)
    );
    assertTrue(
      failure.hint().contains("ENVELOPE_UNKNOWN_FIELD") ||
      failure.hint().contains("extraKey"),
      "未知字段仍必须被拒绝：" + failure.hint()
    );
  }
}
