package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.MockModelGateway;
import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Negative cases for snapshot preservation: nothing a model declared may be lost
 * silently, nothing may be invented, and nothing may be promoted.
 *
 * <p>Each case starts from the offline reference snapshot (a valid
 * {@code AnalysisResult}), mutates exactly one thing in its JSON, and asserts both
 * the conservative outcome and the *visible* note that explains it. The reader is the
 * production one, so the rules under test are the shipped ones.
 */
class SnapshotPreservationNegativeTest {

  static final JsonCodec JSON = new JsonCodec();
  static final StageResponseReader READER = new StageResponseReader(JSON);

  static final String NEWS_TEXT = "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";

  static NewsItem news() {
    return com.signalframe.ai.AiTestFixtures.news();
  }

  /** A valid snapshot from the offline fixture, as a mutable JSON tree. */
  static Map<String, Object> snapshotTree(NewsItem news) {
    var raw = new MockModelGateway(JSON)
      .call(
        new ModelRequest(
          UUID.randomUUID(),
          ModelPurpose.SYNTHESIS,
          "synthesis-v3",
          "",
          news,
          com.signalframe.ai.AiTestFixtures.profile("mock", "mock-v1"),
          false
        )
      )
      .content();
    return JSON.read(raw, Map.class);
  }

  static PipelineState stateWith(
    NewsItem news,
    List<ProtocolModel.Fact> facts,
    List<ProtocolModel.Hypothesis> hypotheses
  ) {
    return PipelineState.initial(
      UUID.randomUUID(),
      "preservation-negative",
      news,
      Instant.now()
    )
      .withFacts(facts)
      .withHypotheses(hypotheses);
  }

  static ProtocolModel.Fact factFrom(Map<String, Object> tree, int index) {
    var facts = (List<Map<String, Object>>) tree.get("facts");
    var f = facts.get(index);
    var refs = (List<Map<String, Object>>) f.get("sourceRefs");
    var ref = refs.getFirst();
    return new ProtocolModel.Fact(
      UUID.fromString((String) f.get("id")),
      (String) f.get("statement"),
      "reported",
      List.of(
        new SourceRef(
          UUID.fromString((String) ref.get("sourceId")),
          (String) ref.get("quote"),
          (Integer) ref.get("startOffset"),
          (Integer) ref.get("endOffset")
        )
      ),
      ProtocolModel.ProofStatus.REPORTED
    );
  }

  static ProtocolModel.Hypothesis hypothesisFrom(Map<String, Object> tree) {
    var h = ((List<Map<String, Object>>) tree.get("hypotheses")).getFirst();
    return new ProtocolModel.Hypothesis(
      UUID.fromString((String) h.get("id")),
      (String) h.get("title"),
      (String) h.get("statement"),
      "reasoning",
      List.of(),
      List.of(),
      List.of(),
      List.of("assumption"),
      List.of(),
      List.of(),
      "rubric",
      null
    );
  }

  // ---- a mechanism may not be promoted without its fact basis ----------------

  @Test
  void aSupportedMechanismWithoutFactsIsNeverLeftAsSupported() {
    var news = news();
    var tree = snapshotTree(news);
    var fact = factFrom(tree, 0);
    var hypothesis = hypothesisFrom(tree);
    // The model claims SUPPORTED but names no fact basis: STG-05 forbids that level.
    tree.put(
      "mechanisms",
      List.of(
        Map.of(
          "id",
          UUID.randomUUID().toString(),
          "cause",
          "a",
          "effect",
          "b",
          "type",
          "INFERENCE",
          "statement",
          "a causes b",
          "reasoning",
          "claimed",
          "supportLevel",
          "SUPPORTED",
          "sourceRefs",
          List.of()
        )
      )
    );
    var raw = JSON.write(tree);
    var state = stateWith(news, List.of(fact), List.of(hypothesis));

    var artifacts = READER.mechanisms(raw, state);

    assertTrue(
      artifacts.value().isEmpty(),
      "an unsupported SUPPORTED mechanism is not carried: " + artifacts.value()
    );
    assertTrue(
      artifacts
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("demoted")),
      "the demotion is stated, never silent: " + artifacts.unknowns()
    );
    assertFalse(
      artifacts
        .value()
        .stream()
        .anyMatch(m -> m.supportLevel() == ProtocolModel.SupportLevel.SUPPORTED),
      "nothing may be left claiming SUPPORTED without evidence"
    );
  }

  @Test
  void aPlausibleMechanismWithItsFactBasisIsKeptAtItsLevel() {
    var news = news();
    var tree = snapshotTree(news);
    var fact = factFrom(tree, 0);
    var hypothesis = hypothesisFrom(tree);
    tree.put(
      "mechanisms",
      List.of(
        Map.of(
          "id",
          UUID.randomUUID().toString(),
          "cause",
          "a",
          "effect",
          "b",
          "type",
          "INFERENCE",
          "statement",
          "a causes b",
          "reasoning",
          "documented",
          "supportLevel",
          "PLAUSIBLE",
          "sourceRefs",
          List.of(
            Map.of(
              "sourceId",
              fact.sourceRefs().getFirst().sourceId().toString(),
              "quote",
              fact.sourceRefs().getFirst().quote(),
              "startOffset",
              fact.sourceRefs().getFirst().startOffset(),
              "endOffset",
              fact.sourceRefs().getFirst().endOffset()
            )
          )
        )
      )
    );
    var raw = JSON.write(tree);
    var state = stateWith(news, List.of(fact), List.of(hypothesis));

    var artifacts = READER.mechanisms(raw, state);

    assertEquals(1, artifacts.value().size(), artifacts.unknowns().toString());
    assertEquals(
      ProtocolModel.SupportLevel.PLAUSIBLE,
      artifacts.value().getFirst().supportLevel(),
      "a supported level is honoured, not flattened"
    );
    assertFalse(artifacts.value().getFirst().factRefs().isEmpty());
  }

  // ---- a non-compliant prediction is dropped visibly ------------------------

  @Test
  void aPredictionWhoseDeadlineHasPassedIsDroppedWithAReason() {
    var news = news();
    var tree = snapshotTree(news);
    var fact = factFrom(tree, 0);
    var hypothesis = hypothesisFrom(tree);
    tree.put(
      "predictions",
      List.of(
        Map.of(
          "id",
          UUID.randomUUID().toString(),
          "hypothesisId",
          hypothesis.id().toString(),
          "statement",
          "something will happen",
          "type",
          "PREDICTION",
          "expectedBy",
          Instant.now().minusSeconds(3600).toString(),
          "status",
          "OPEN",
          "verificationCriteria",
          "confirm when it happens; reject when it does not",
          "observable",
          "a filing",
          "whereToCheck",
          "the filing page"
        )
      )
    );
    var state = stateWith(news, List.of(fact), List.of(hypothesis));

    var artifacts = READER.predictions(JSON.write(tree), state);

    assertTrue(artifacts.value().isEmpty(), "a past deadline is not a prediction");
    assertTrue(
      artifacts
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("prediction was not carried")),
      "the drop is stated: " + artifacts.unknowns()
    );
  }

  @Test
  void aPredictionForAHypothesisThatIsNotInTheSnapshotIsDropped() {
    var news = news();
    var tree = snapshotTree(news);
    var fact = factFrom(tree, 0);
    var hypothesis = hypothesisFrom(tree);
    tree.put(
      "predictions",
      List.of(
        Map.of(
          "id",
          UUID.randomUUID().toString(),
          "hypothesisId",
          UUID.randomUUID().toString(),
          "statement",
          "something will happen",
          "type",
          "PREDICTION",
          "expectedBy",
          Instant.now().plusSeconds(86400).toString(),
          "status",
          "OPEN",
          "verificationCriteria",
          "confirm when it happens; reject when it does not",
          "observable",
          "a filing",
          "whereToCheck",
          "the filing page"
        )
      )
    );
    var state = stateWith(news, List.of(fact), List.of(hypothesis));

    var artifacts = READER.predictions(JSON.write(tree), state);

    assertTrue(
      artifacts.value().isEmpty(),
      "a dangling hypothesis reference is refused, not repaired"
    );
    assertTrue(
      artifacts
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("prediction was not carried"))
    );
  }

  // ---- a plan item the protocol cannot accept is dropped visibly ------------

  @Test
  void aPlanItemWithoutADistinguishableResultIsDroppedWithAReason() {
    var news = news();
    var tree = snapshotTree(news);
    var fact = factFrom(tree, 0);
    var hypothesis = hypothesisFrom(tree);
    tree.put(
      "verificationIndicators",
      List.of(
        Map.of(
          "id",
          UUID.randomUUID().toString(),
          "name",
          "check the filing",
          "type",
          "INFERENCE",
          "statement",
          "check the filing",
          "reasoning",
          "it matters",
          "whereToCheck",
          "the filing page",
          "supportingResult",
          "the same text",
          "contradictingResult",
          "the same text",
          "priority",
          "HIGH",
          "deadline",
          Instant.now().plusSeconds(86400).toString()
        )
      )
    );
    var state = stateWith(news, List.of(fact), List.of(hypothesis));

    var artifacts = READER.verificationPlan(JSON.write(tree), state);

    assertTrue(artifacts.value().isEmpty(), "an undecidable plan item is refused");
    assertTrue(
      artifacts
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("plan item was not carried")),
      "the drop is stated: " + artifacts.unknowns()
    );
  }

  // ---- a dangling declared fact reference is never invented -----------------

  @Test
  void aDeclaredFactReferenceOutsideTheSnapshotResolvesToNothing() {
    var news = news();
    var tree = snapshotTree(news);
    var fact = factFrom(tree, 0);
    var hypothesis = hypothesisFrom(tree);
    var dangling = UUID.randomUUID();
    tree.put(
      "variables",
      List.of(
        Map.of(
          "id",
          UUID.randomUUID().toString(),
          "name",
          "a variable",
          "type",
          "INFERENCE",
          "statement",
          "something changed",
          "reasoning",
          "because",
          "direction",
          "UNCHANGED",
          "factRefs",
          List.of(dangling.toString()),
          "sourceRefs",
          List.of()
        )
      )
    );
    var state = stateWith(news, List.of(fact), List.of(hypothesis));

    var artifacts = READER.variables(JSON.write(tree), state);

    assertTrue(
      artifacts
        .value()
        .stream()
        .allMatch(v -> !v.factRefs().contains(dangling)),
      "a reference to a fact outside the snapshot is never carried"
    );
  }

  // ---- historical snapshots stay readable ----------------------------------

  @Test
  void aSnapshotWithoutAnyOfTheNewerFieldsStillReads() {
    var news = news();
    var tree = snapshotTree(news);
    // The shape a pre-SCH snapshot has: none of the newer optional fields at all.
    for (var key : List.of(
      "predictions",
      "sourceAssessment",
      "protocolVersion",
      "provenance"
    )) tree.remove(key);
    for (var h : (List<Map<String, Object>>) tree.get("hypotheses")) {
      h.remove("status");
      h.remove("supportingFactRefs");
      h.remove("falsificationConditions");
    }
    var raw = JSON.write(tree);

    var envelope = SnapshotEnvelope.tryParse(raw, JSON);
    assertTrue(envelope.isPresent(), "an older snapshot must still parse");
    var facts = envelope.get().facts();
    assertFalse(facts.isEmpty(), "its facts are still readable");
    assertEquals(
      ProtocolModel.ProofStatus.REPORTED,
      facts.getFirst().verificationStatus(),
      "an absent verificationStatus reads as the conservative default"
    );
    assertTrue(
      envelope.get().predictions().isEmpty(),
      "no predictions means none, not an invented one"
    );
    assertTrue(
      envelope.get().mechanisms(facts).isEmpty(),
      "no mechanisms means none"
    );
  }
}
