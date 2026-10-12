package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.ClaimType;
import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Stage 1 反序列化诊断：每一类失败都必须定位到准确字段，而不是笼统报 SCHEMA_INVALID。
 *
 * <p>真实响应未保存，因此这里不假设线上响应的字段值（标记 UNKNOWN）。这里构造的是
 * 各类失败形态的离线 Fixture，用来证明诊断能力本身，并锁定"顶层结构错误"与
 * "字段级类型错误"的区分。
 */
class Stage1DeserializationDiagnosisTest {

  static final JsonCodec JSON = PipelineFixtures.json();
  static final StageResponseReader READER = new StageResponseReader(JSON);
  static final String NEWS = "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";
  static final String SPAN = NEWS.substring(0, NEWS.indexOf("双方"));

  static PipelineState state() {
    return new SourceAssessmentStage().execute(
      PipelineState.initial(
        UUID.randomUUID(),
        "stage1-diagnosis",
        PipelineFixtures.news(NEWS),
        Instant.now()
      )
    );
  }

  /** 合法回复的骨架，各用例只改一处。 */
  static String legal(PipelineState s) {
    return (
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"type\":\"FACT\",\"statement\":" +
      JSON.write(SPAN) +
      ",\"reasoning\":\"the source states this\",\"confidence\":30,\"sourceRefs\":[{\"sourceId\":\"" +
      s.news().source().id() +
      "\",\"quote\":" +
      JSON.write(SPAN) +
      ",\"startOffset\":0,\"endOffset\":" +
      SPAN.length() +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}"
    );
  }

  static String ref(PipelineState s) {
    return (
      "{\"sourceId\":\"" +
      s.news().source().id() +
      "\",\"quote\":" +
      JSON.write(SPAN) +
      ",\"startOffset\":0,\"endOffset\":" +
      SPAN.length() +
      "}"
    );
  }

  static StageOutputException refused(String raw, PipelineState s) {
    return assertThrows(StageOutputException.class, () -> READER.facts(raw, s));
  }

  // ---- 1. 合法回复：schema 与 provenance 都通过 ----------------------------

  /**
   * 关键回归：contract 的 Fact 要求 type 与 confidence，模型照 schema 输出时必须通过。
   * 这正是线上失败形态（顶层正确、内部字段被拒）的修复证明。
   */
  @Test
  void aContractCompliantReplyIsAccepted() {
    var s = state();
    var artifacts = READER.facts(legal(s), s);

    assertEquals(1, artifacts.value().size());
    var fact = artifacts.value().getFirst();
    assertEquals(ClaimType.FACT, fact.type(), "type 被接受但不被信任");
    assertEquals(30, fact.confidence(), "advisory confidence 被保留");
    assertEquals(ProtocolModel.ProofStatus.REPORTED, fact.verificationStatus());
    assertEquals(SPAN, fact.statement());
  }

  /** type 与 confidence 缺失也必须通过：它们是契约字段，不是证据。 */
  @Test
  void aReplyWithoutTypeOrConfidenceIsStillAccepted() {
    var s = state();
    var minimal =
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":" +
      JSON.write(SPAN) +
      ",\"reasoning\":\"reported\",\"sourceRefs\":[" +
      ref(s) +
      "],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    assertEquals(1, READER.facts(minimal, s).value().size());
  }

  // ---- 2. 未知字段：必须点名该字段，而不是报"缺少 facts" -------------------

  @Test
  void anUnknownFactFieldNamesTheFieldAndThePath() {
    var s = state();
    var withExtra = legal(s).replace("\"reasoning\":\"the source states this\",", "\"reasoning\":\"the source states this\",\"claimType\":\"FACT\",");

    var failure = refused(withExtra, s);
    var message = failure.hint();

    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(message.contains("ENVELOPE_UNKNOWN_FIELD"), message);
    assertTrue(message.contains("claimType"), "必须点名未知字段：" + message);
    assertTrue(message.contains("facts"), "必须给出字段路径：" + message);
  }

  // ---- 3. 非法枚举：verificationStatus --------------------------------------

  @Test
  void anIllegalVerificationStatusIsReportedAsAnInvalidEnum() {
    var s = state();
    var bad = legal(s).replace("\"REPORTED\"", "\"NOT_A_STATUS\"");

    // 小写声明同样不是协议允许的值；它必须被拒绝，而不是被当成 REPORTED。
    var lowercase = legal(s).replace("\"REPORTED\"", "\"reported\"");
    assertTrue(
      refused(lowercase, s).hint().contains("EXPECTED_ENUM"),
      "大小写不匹配的枚举也必须被拒绝"
    );

    var failure = refused(bad, s);
    var message = failure.hint();

    // 非法枚举必须在原始 JSON 层被拒绝：Jackson 会把未知枚举值映射为 null，
    // 而 DTO 的保守默认会把它变成 REPORTED —— 那是模型从未声明的 epistemic status。
    assertTrue(message.contains("EXPECTED_ENUM"), message);
    assertTrue(message.contains("facts[0].verificationStatus"), "必须给出字段路径：" + message);
    assertTrue(message.contains("NOT_A_STATUS"), "必须点名非法值：" + message);
    assertTrue(message.contains("never silently downgraded"), message);
  }

  // ---- 4. 非法 UUID ---------------------------------------------------------

  @Test
  void anIllegalFactIdIsReportedAsAnInvalidUuid() {
    var s = state();
    var bad = legal(s).replaceFirst(
      "\"id\":\"[0-9a-f-]{36}\"",
      "\"id\":\"fact-1\""
    );

    var failure = refused(bad, s);
    var message = failure.hint();

    assertTrue(message.contains("ENVELOPE_INVALID_UUID"), message);
    assertTrue(message.contains("id"), message);
    assertTrue(message.contains("UUID"), message);
  }

  // ---- 5. sourceRefs 对象与数组混淆 ----------------------------------------

  @Test
  void sourceRefsAsAnObjectIsReportedAsAWrongJsonType() {
    var s = state();
    // 合法 JSON，但 sourceRefs 是对象而不是数组
    var bad = "{\"facts\":[{\"id\":\"" + UUID.randomUUID() +
      "\",\"statement\":" + JSON.write(SPAN) +
      ",\"reasoning\":\"reported\",\"sourceRefs\":{\"0\":" + ref(s) +
      "},\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    var failure = refused(bad, s);
    var message = failure.hint();

    assertTrue(
      message.contains("ENVELOPE_EXPECTED_OBJECT") ||
      message.contains("ENVELOPE_TYPE_MISMATCH") ||
      message.contains("WRONG_JSON_TYPE"),
      message
    );
    assertTrue(message.contains("sourceRefs"), "必须给出字段路径：" + message);
  }

  // ---- 6. offset 类型错误 ---------------------------------------------------

  /**
   * 类型完全不可转换时，必须定位到字段、期望类型与实际 JSON 类型。
   *
   * <p>已知宽松（KNOWN GAP）：Jackson 3 默认会把 `"0"` 或 `1.5` 强制转换成 Integer，
   * 因此"字符串形式的 offset"目前不会被拒。这里用对象值覆盖诊断路径，并把该宽松
   * 记录在报告中，而不是假装它不存在。
   */
  @Test
  void anOffsetOfAnUnconvertibleTypeIsReportedWithItsPathAndExpectedType() {
    var s = state();
    var bad = legal(s).replace("\"startOffset\":0", "\"startOffset\":{}");

    var failure = refused(bad, s);
    var message = failure.hint();

    assertTrue(
      message.contains("ENVELOPE_TYPE_MISMATCH") ||
      message.contains("ENVELOPE_EXPECTED_") ||
      message.contains("ENVELOPE_INVALID_VALUE"),
      message
    );
    assertTrue(message.contains("startOffset"), "必须给出字段路径：" + message);
  }

  // ---- 7. 顶层结构错误：必须与字段级错误区分 --------------------------------

  @Test
  void aMissingFactsKeyIsATopLevelStructureFailure() {
    var s = state();
    var failure = refused("{\"foo\":1}", s);
    var message = failure.hint();

    assertTrue(message.contains("expected a JSON object"), message);
    assertTrue(message.contains("top-level keys"), message);
    assertTrue(message.contains("foo"), "顶层键必须被点名：" + message);
    assertTrue(message.contains("first deserialization failure"), message);
  }

  @Test
  void aWrapperRootIsATopLevelStructureFailure() {
    var s = state();
    var wrapped = "{\"result\":" + legal(s) + "}";
    var message = refused(wrapped, s).hint();

    assertTrue(message.contains("top-level keys"), message);
    assertTrue(message.contains("result"), message);
  }

  /** 顶层键存在但内部字段错误：不得报成"缺少 facts 数组"。 */
  @Test
  void aFieldLevelFailureIsNeverReportedAsAMissingFactsArray() {
    var s = state();
    var bad = legal(s).replace("\"REPORTED\"", "\"NOT_A_STATUS\"");

    // 小写声明同样不是协议允许的值；它必须被拒绝，而不是被当成 REPORTED。
    var lowercase = legal(s).replace("\"REPORTED\"", "\"reported\"");
    assertTrue(
      refused(lowercase, s).hint().contains("EXPECTED_ENUM"),
      "大小写不匹配的枚举也必须被拒绝"
    );
    var message = refused(bad, s).hint();

    assertTrue(message.contains("facts[0]"), "必须定位到具体字段：" + message);
    assertTrue(
      message.contains("EXPECTED_ENUM") || message.contains("ENVELOPE_"),
      "必须给出结构化的失败类别：" + message
    );
    assertFalse(
      message.contains("expected a JSON object with a 'facts' array"),
      "字段级错误不得被报成缺少 facts 数组：" + message
    );
  }

  // ---- 8. 语法错误 ----------------------------------------------------------

  @Test
  void malformedJsonIsReportedAsMalformedNotAsASchemaFailure() {
    var s = state();
    var failure = assertThrows(StageOutputException.class, () ->
      READER.facts("{\"facts\":[", s)
    );

    assertEquals(StageFailure.Kind.MALFORMED_OUTPUT, failure.kind());
  }

  // ---- 9. 协议语义：不得提升、不得放宽 --------------------------------------

  @Test
  void aDeclaredNonFactTypeIsRefused() {
    var s = state();
    var inference = legal(s).replace("\"type\":\"FACT\"", "\"type\":\"INFERENCE\"");

    var failure = refused(inference, s);

    assertTrue(
      failure.hint().contains("may only report FACT"),
      "不得接受非 FACT 类型：" + failure.hint()
    );
  }

  @Test
  void anOutOfRangeConfidenceIsRefused() {
    var s = state();
    var bad = legal(s).replace("\"confidence\":30", "\"confidence\":140");

    var failure = refused(bad, s);

    assertTrue(failure.hint().contains("0-100"), failure.hint());
  }

  // ---- 10. Provenance 语义仍然生效 ------------------------------------------

  @Test
  void provenanceRulesStillHoldAfterTheMappingFix() {
    var s = state();
    var paraphrased = legal(s).replace(
      JSON.write(SPAN),
      JSON.write("协议即将到期")
    );

    var failure = refused(paraphrased, s);

    assertTrue(
      failure.hint().contains("verbatim") || failure.hint().contains("PR-01"),
      "引用与来源校验不得被放宽：" + failure.hint()
    );
  }
}
