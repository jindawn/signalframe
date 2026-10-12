package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.ModelRetryPolicy;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.ModelPurpose;
import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The stage-1 reply shape: what the prompt now asks for, and how each real failure
 * mode is refused.
 *
 * <p>The live probe returned a decodable JSON object that was neither a facts
 * envelope nor a snapshot, so the root shape is exercised explicitly here: the
 * prompt's own schema must carry a root object, and every wrong root must be named
 * rather than guessed at.
 */
class Stage1ResponseShapeTest {

  static final JsonCodec JSON = PipelineFixtures.json();
  static final StageResponseReader READER = new StageResponseReader(JSON);
  static final String NEWS_TEXT = "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";
  static final String SPAN = NEWS_TEXT.substring(0, NEWS_TEXT.indexOf("双方"));

  static PipelineState state() {
    var news = PipelineFixtures.news(NEWS_TEXT);
    return new SourceAssessmentStage().execute(
      PipelineState.initial(UUID.randomUUID(), "stage1-shape", news, Instant.now())
    );
  }

  static String factsReply(PipelineState state) {
    return (
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":" +
      JSON.write(SPAN) +
      ",\"reasoning\":\"the source states this\",\"sourceRefs\":[{\"sourceId\":\"" +
      state.news().source().id() +
      "\",\"quote\":" +
      JSON.write(SPAN) +
      ",\"startOffset\":0,\"endOffset\":" +
      SPAN.length() +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}"
    );
  }

  // ---- the prompt must state the root object --------------------------------

  /**
   * The reply schema shown to the model must have a root object naming `facts` as a
   * required array. A components bag alone says nothing about the reply's root,
   * which is what left the shape to trailing prose in the live run.
   */
  @Test
  void theStageOneReplySchemaCarriesARootObjectWithFactsRequired() {
    var schema = JSON.read(
      new TwoStageSynthesisService(
        PipelineFixtures.router(),
        new PipelineFixtures.ScriptedGateway(r -> factsReply(state())),
        new PipelineFixtures.RecordingRuns(),
        READER,
        PipelineFixtures.promptLibrary(),
        JSON,
        PipelineFixtures.gateA(JSON),
        com.signalframe.shared.confidence.ConfidenceRubric.deterministic(),
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
      ).factsReplySchema(),
      Map.class
    );

    assertEquals("object", schema.get("type"), "the reply schema has a root type");
    assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    assertEquals(List.of("facts"), schema.get("required"), "facts is required");
    var properties = (Map<String, Object>) schema.get("properties");
    assertEquals(
      "array",
      ((Map<String, Object>) properties.get("facts")).get("type"),
      "facts is a top-level array"
    );
    assertEquals(
      "#/components/schemas/Fact",
      ((Map<String, Object>) ((Map<String, Object>) properties.get("facts")).get("items")).get("$ref"),
      "the fact definition still comes from the generated contract"
    );
    var components =
      (Map<String, Object>) ((Map<String, Object>) schema.get("components")).get("schemas");
    assertTrue(components.containsKey("Fact"), "the contract's definitions are still present");

    // unknowns 项必须声明字段名：reader 会拒绝未知字段，schema 只写 "an object"
    // 会让照做的模型在它从未约束过的字段上失败（实测 unknowns[0].issue）。
    var unknownItem =
      (Map<String, Object>) ((Map<String, Object>) properties.get("unknowns")).get("items");
    var unknownProps = (Map<String, Object>) unknownItem.get("properties");
    assertTrue(unknownProps.containsKey("statement"), "unknowns 项必须声明 statement");
    assertTrue(unknownProps.containsKey("unknownReason"), "unknowns 项必须声明 unknownReason");
    assertTrue(unknownProps.containsKey("wouldResolveWith"), "unknowns 项必须声明 wouldResolveWith");
    assertEquals(
      List.of("statement", "unknownReason"),
      unknownItem.get("required"),
      "unknowns 项的最小必填字段"
    );
  }

  /** The prompt itself must name the forbidden wrapper keys. */
  @Test
  void theStageOnePromptForbidsWrapperKeysAndShowsAMinimalShape() {
    var state = state();
    var service = new TwoStageSynthesisService(
      PipelineFixtures.router(),
      new PipelineFixtures.ScriptedGateway(r -> factsReply(state)),
      new PipelineFixtures.RecordingRuns(),
      READER,
      PipelineFixtures.promptLibrary(),
      JSON,
      PipelineFixtures.gateA(JSON),
      com.signalframe.shared.confidence.ConfidenceRubric.deterministic(),
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
    var text = service.factsPromptText(service.factsPrompt(), state);
    // The root schema and the explicit contract added ~670 chars. A bound keeps a
    // future prompt change from quietly inflating the smallest request in the system.
    System.out.println(
      "PROMPT-SIZE stage1=" + text.length() + " chars ~" + (text.length() / 4) + " tokens"
    );
    assertTrue(
      text.length() < 6_000,
      "the stage-1 prompt stays small: " + text.length() + " chars"
    );

    assertTrue(text.contains("no wrapper key"), "wrappers are forbidden in words");
    assertTrue(
      text.contains("nothing named result, data, output, analysis or"),
      "the likely wrapper keys are named"
    );
    assertTrue(
      text.contains("Minimal shape, for the avoidance of doubt"),
      "a literal example is given"
    );
    assertTrue(
      text.contains("An unknowns item has exactly these keys"),
      "unknowns 项的字段必须被点名"
    );
    assertTrue(
      text.contains(state.news().source().id().toString()),
      "the example pins the real source UUID"
    );
  }

  // ---- the legal reply passes ----------------------------------------------

  @Test
  void aLegalStageOneReplyPassesSchemaAndProvenance() {
    var state = state();
    var artifacts = READER.facts(factsReply(state), state);

    assertEquals(1, artifacts.value().size(), "one fact");
    assertFalse(artifacts.demo());
    var fact = artifacts.value().getFirst();
    assertEquals(SPAN, fact.statement());
    assertEquals(SPAN, fact.sourceRefs().getFirst().quote());
    assertEquals(0, fact.sourceRefs().getFirst().startOffset());
    assertEquals(SPAN.length(), fact.sourceRefs().getFirst().endOffset());
    assertEquals(ProtocolModel.ProofStatus.REPORTED, fact.verificationStatus());
    assertNotNull(fact.id(), "the fact keeps its own id");
  }

  // ---- every wrong root is refused, and named -------------------------------

  static StageOutputException refused(String raw, PipelineState state) {
    return assertThrows(StageOutputException.class, () ->
      READER.facts(raw, state)
    );
  }

  @Test
  void aWrapperRootIsRefusedAndTheTopLevelKeysAreNamed() {
    var state = state();
    var wrapped =
      "{\"result\":" +
      factsReply(state) +
      "}";

    var failure = refused(wrapped, state);

    assertTrue(
      failure.getMessage().contains("result"),
      "the received top-level keys are reported: " + failure.getMessage()
    );
    assertTrue(
      failure.getMessage().contains("'facts' array"),
      "the expected root is stated: " + failure.getMessage()
    );
  }

  @Test
  void aMissingFactsArrayIsRefused() {
    // An absent facts key reads as an empty fact set, which the reader refuses:
    // a snapshot with no fact cannot support anything downstream (STG-03).
    var failure = refused("{\"unknowns\":[]}", state());

    assertTrue(
      failure.getMessage().contains("no extracted fact"),
      failure.getMessage()
    );
  }

  @Test
  void aFactsKeyOfTheWrongTypeIsRefused() {
    var state = state();
    var objectInsteadOfArray = "{\"facts\":{\"facts\":" + factsReply(state).substring(9) + "}";

    var failure = refused(objectInsteadOfArray, state());

    assertTrue(failure.getMessage().contains("'facts' array"), failure.getMessage());
  }

  @Test
  void proseIsRefusedAsUndecodableRatherThanAsAWrongShape() {
    var failure = refused("Here are the facts I extracted: none.", state());

    assertTrue(
      failure.getMessage().contains("not decodable JSON"),
      "prose is a malformed response, not a schema mismatch: " + failure.getMessage()
    );
  }

  @Test
  void aNonVerbatimQuoteIsRefused() {
    var state = state();
    var paraphrased =
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"协议即将到期\",\"reasoning\":\"reported\",\"sourceRefs\":[{\"sourceId\":\"" +
      state.news().source().id() +
      "\",\"quote\":\"协议即将到期\",\"startOffset\":0,\"endOffset\":6}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    var failure = refused(paraphrased, state);

    assertTrue(
      failure.getMessage().contains("not a verbatim substring"),
      failure.getMessage()
    );
  }

  @Test
  void wrongOffsetsAreRefused() {
    var state = state();
    var shifted =
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":" +
      JSON.write(SPAN) +
      ",\"reasoning\":\"reported\",\"sourceRefs\":[{\"sourceId\":\"" +
      state.news().source().id() +
      "\",\"quote\":" +
      JSON.write(SPAN) +
      ",\"startOffset\":1,\"endOffset\":" +
      (SPAN.length() + 1) +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    var failure = refused(shifted, state);

    assertTrue(
      failure.getMessage().contains("not a verbatim substring") ||
      failure.getMessage().contains("outside the source text"),
      failure.getMessage()
    );
  }

  @Test
  void aSourceRefThatIsNotTheAnalyzedSourceIsRefused() {
    var state = state();
    var foreign =
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":" +
      JSON.write(SPAN) +
      ",\"reasoning\":\"reported\",\"sourceRefs\":[{\"sourceId\":\"" +
      UUID.randomUUID() +
      "\",\"quote\":" +
      JSON.write(SPAN) +
      ",\"startOffset\":0,\"endOffset\":" +
      SPAN.length() +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    var failure = refused(foreign, state);

    assertTrue(
      failure.getMessage().contains("does not match the analyzed source"),
      failure.getMessage()
    );
  }

  @Test
  void aFactWithoutAnySourceRefIsRefused() {
    var noRef =
      "{\"facts\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":" +
      JSON.write(SPAN) +
      ",\"reasoning\":\"reported\",\"sourceRefs\":[],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    assertThrows(StageOutputException.class, () -> READER.facts(noRef, state()));
  }

  /** A provider failure and a schema failure stay distinguishable. */
  @Test
  void aProviderFailureIsNotReportedAsASchemaFailure() {
    var gateway = new PipelineFixtures.ScriptedGateway(r -> {
      throw com.signalframe.ai.domain.ModelInvocationException.of(
        com.signalframe.ai.domain.ModelFailure.AUTHENTICATION,
        "credential rejected"
      );
    });
    var client = new StageModelClient(
      PipelineFixtures.router(),
      gateway,
      new PipelineFixtures.RecordingRuns(),
      new ModelRetryPolicy(1, 0, java.time.Duration.ZERO)
    );
    var state = state();

    var failure = assertThrows(
      StageFailure.class,
      () ->
        client.invoke(
          new StageModelClient.StageCall(
            "TwoStageFacts",
            state.jobId(),
            state.correlationId(),
            state.news(),
            ModelPurpose.FACT_EXTRACTION,
            "two-stage-facts-v1",
            "prompt"
          ),
          raw -> READER.facts(raw, state)
        )
    );

    assertEquals(StageFailure.Kind.PROVIDER_FAILURE, failure.kind());
    assertTrue(failure.jobMessage().contains("AUTHENTICATION"), failure.jobMessage());
  }
}
