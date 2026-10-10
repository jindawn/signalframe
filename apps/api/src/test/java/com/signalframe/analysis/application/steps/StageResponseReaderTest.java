package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.NewsItem;
import com.signalframe.contract.SourceRef;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stage output validation: the epistemic rules for one stage's typed artifact.
 *
 * <p>A stage envelope is validated strictly (a missing fact ref, a dangling ref, an
 * unobservable condition or an unsupported vocabulary value is rejected and gets
 * one repair), while a snapshot-shaped payload is treated as the reference-fixture
 * projection and anything the protocol cannot accept is demoted to UNKNOWN
 * instead of being kept (EP-01/EP-02).
 */
class StageResponseReaderTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  private final StageResponseReader reader = new StageResponseReader(
    PipelineFixtures.json()
  );
  private NewsItem news;
  private String quote;
  private Fact fact;
  private PipelineState base;

  @BeforeEach
  void setUp() {
    news = PipelineFixtures.news();
    quote = news.source().text().substring(0, 12);
    fact = new Fact(
      UUID.randomUUID(),
      quote,
      "Reported source claim, not independently verified.",
      List.of(
        new SourceRef(news.source().id(), quote, 0, quote.length())
      ),
      ProofStatus.REPORTED
    );
    base = PipelineState.initial(UUID.randomUUID(), "corr", news, CREATED)
      .withFacts(List.of(fact));
  }

  private String refJson(int start, int end, String text) {
    return (
      "{\"sourceId\":\"" +
      news.source().id() +
      "\",\"quote\":\"" +
      text +
      "\",\"startOffset\":" +
      start +
      ",\"endOffset\":" +
      end +
      "}"
    );
  }

  // ---- STG-03 facts -------------------------------------------------------

  @Test
  void factWithoutSourceRefIsRejected() {
    var raw =
      "{\"facts\":[{\"statement\":\"" +
      quote +
      "\",\"reasoning\":\"r\",\"sourceRefs\":[],\"verificationStatus\":\"REPORTED\"}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.facts(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("sourceRef"));
  }

  @Test
  void factWithANonVerbatimQuoteIsRejected() {
    var raw =
      "{\"facts\":[{\"statement\":\"" +
      quote +
      "\",\"reasoning\":\"r\",\"sourceRefs\":[" +
      refJson(0, 5, "wrong") +
      "],\"verificationStatus\":\"REPORTED\"}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.facts(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("verbatim"));
  }

  @Test
  void corroboratedFactIsRejectedWhileTheSnapshotHasOneSource() {
    var raw =
      "{\"facts\":[{\"statement\":\"" +
      quote +
      "\",\"reasoning\":\"r\",\"sourceRefs\":[" +
      refJson(0, quote.length(), quote) +
      "],\"verificationStatus\":\"CORROBORATED\"}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.facts(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("CORROBORATED"));
  }

  @Test
  void anEmptyFactSetFailsTheStage() {
    var failure = assertThrows(StageOutputException.class, () ->
      reader.facts("{\"facts\":[]}", base)
    );
    assertTrue(failure.hint().contains("no extracted fact"));
  }

  @Test
  void duplicateSpansCollapseToASingleFact() {
    var one =
      "{\"statement\":\"" +
      quote +
      "\",\"reasoning\":\"r\",\"sourceRefs\":[" +
      refJson(0, quote.length(), quote) +
      "],\"verificationStatus\":\"REPORTED\"}";
    var artifacts = reader.facts(
      "{\"facts\":[" + one + "," + one + "]}",
      base
    );
    assertEquals(1, artifacts.value().size());
  }

  // ---- STG-04+ inferences -------------------------------------------------

  @Test
  void inferenceWithoutAFactReferenceIsRejected() {
    var raw =
      "{\"variables\":[{\"name\":\"price\",\"direction\":\"DOWN\",\"currentState\":\"lower\",\"statement\":\"s\",\"reasoning\":\"r\"," +
      "\"whyItMatters\":\"changes the cost base\",\"factRefs\":[],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.variables(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("fact reference"));
  }

  @Test
  void aDanglingFactReferenceIsRejected() {
    var raw =
      "{\"variables\":[{\"name\":\"price\",\"direction\":\"DOWN\",\"currentState\":\"lower\",\"statement\":\"s\",\"reasoning\":\"r\"," +
      "\"whyItMatters\":\"changes the cost base\",\"factRefs\":[\"" +
      UUID.randomUUID() +
      "\"],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.variables(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("does not resolve"));
  }

  @Test
  void anUnsupportedInferenceFromASnapshotIsDemotedToUnknown() {
    // The variable's only source span does not overlap any fact in the snapshot,
    // so no fact reference can be derived: EP-01 requires UNKNOWN over guessing.
    String raw =
      "{\"summary\":\"s\",\"facts\":[],\"variables\":[{\"id\":\"" +
      UUID.randomUUID() +
      "\",\"name\":\"price\",\"direction\":\"DOWN\",\"type\":\"INFERENCE\",\"statement\":\"price fell\"," +
      "\"reasoning\":\"implied by context\",\"confidence\":30,\"sourceRefs\":[" +
      refJson(20, 24, news.source().text().substring(20, 24)) +
      "]}],\"unknowns\":[],\"mechanisms\":[],\"stakeholders\":[],\"firstOrderEffects\":[]," +
      "\"secondOrderEffects\":[],\"hypotheses\":[],\"alternativeExplanations\":[],\"counterArguments\":[]," +
      "\"falsificationConditions\":[],\"corroboratingSignals\":[],\"verificationIndicators\":[]," +
      "\"upcomingObservations\":[]}";
    var artifacts = reader.variables(raw, base);
    assertTrue(artifacts.value().isEmpty(), "unsupported inference is not kept");
    assertTrue(
      artifacts
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("Dropped variables")),
      artifacts.unknowns().toString()
    );
  }

  // ---- STG-05 mechanisms ---------------------------------------------------

  @Test
  void supportedMechanismNeedsEvidenceAtBothEnds() {
    var raw =
      "{\"mechanisms\":[{\"from\":\"" + fact.id() + "\",\"to\":\"" + fact.id() + "\",\"supportLevel\":\"SUPPORTED\"," +
      "\"explanation\":\"e\",\"reasoning\":\"r\",\"assumptions\":[],\"factRefs\":[\"" +
      fact.id() +
      "\"],\"sourceRefs\":[],\"confidence\":30}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.mechanisms(raw, base)
    );
    assertTrue(failure.hint().contains("SUPPORTED"));
  }

  @Test
  void speculativeMechanismMustNameACorroboratingSignal() {
    var raw =
      "{\"mechanisms\":[{\"from\":\"" + fact.id() + "\",\"to\":\"" + fact.id() + "\",\"supportLevel\":\"SPECULATIVE\"," +
      "\"explanation\":\"e\",\"reasoning\":\"r\",\"assumptions\":[],\"factRefs\":[],\"sourceRefs\":[]," +
      "\"corroboratingSignal\":\"UNKNOWN\",\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.mechanisms(raw, base)
    );
    assertTrue(failure.hint().contains("corroborating signal"));
  }

  @Test
  void freeFloatingCausalEndpointsAreRejected() {
    var raw =
      "{\"mechanisms\":[{\"from\":\"the market mood\",\"to\":\"investor sentiment\"," +
      "\"supportLevel\":\"PLAUSIBLE\",\"explanation\":\"e\",\"reasoning\":\"r\"," +
      "\"assumptions\":[\"momentum persists\"],\"factRefs\":[\"" +
      fact.id() +
      "\"],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.mechanisms(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("resolve to a variable or fact"));
  }

  @Test
  void plausibleMechanismNeedsStatedAssumptions() {
    var raw =
      "{\"mechanisms\":[{\"from\":\"" + fact.id() + "\",\"to\":\"" + fact.id() + "\",\"supportLevel\":\"PLAUSIBLE\"," +
      "\"explanation\":\"e\",\"reasoning\":\"r\",\"assumptions\":[],\"factRefs\":[\"" +
      fact.id() +
      "\"],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.mechanisms(raw, base)
    );
    assertTrue(failure.hint().contains("stated assumptions"));
  }

  // ---- STG-09/10/11 -------------------------------------------------------

  @Test
  void hypothesisNeedsASupportingFactAndANoteWhenContradictionIsEmpty() {
    var noFact =
      "{\"hypotheses\":[{\"title\":\"t\",\"statement\":\"s\",\"reasoning\":\"r\"," +
      "\"supportingFactRefs\":[],\"assumptions\":[\"nothing was checked\"],\"confidenceReason\":\"c\"}]}";
    var first = assertThrows(StageOutputException.class, () ->
      reader.hypotheses(noFact, base)
    );
    assertTrue(first.hint().contains("supporting fact reference"));

    var noNote =
      "{\"hypotheses\":[{\"title\":\"t\",\"statement\":\"s\",\"reasoning\":\"r\"," +
      "\"supportingFactRefs\":[\"" +
      fact.id() +
      "\"],\"contradictingEvidenceRefs\":[],\"assumptions\":[],\"confidenceReason\":\"c\"}]}";
    var second = assertThrows(StageOutputException.class, () ->
      reader.hypotheses(noNote, base)
    );
    assertTrue(second.hint().contains("assumption note"));
  }

  @Test
  void alternativeThatRestatesTheHypothesisIsRejected() {
    var hypothesis = new Hypothesis(
      UUID.randomUUID(),
      "title",
      "the price cut drove volume",
      "reasoning",
      List.of(fact.id()),
      List.of(),
      List.of(),
      List.of("nothing was checked"),
      List.of(),
      List.of(),
      "reason",
      30
    );
    var state = base.withHypotheses(List.of(hypothesis));
    var raw =
      "{\"alternatives\":[{\"statement\":\"the price cut drove volume\",\"reasoning\":\"r\"," +
      "\"rivalsHypothesisRef\":\"" +
      hypothesis.id() +
      "\",\"factRefs\":[\"" +
      fact.id() +
      "\"],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.alternativeExplanations(raw, state)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("restatement"));
  }

  @Test
  void alternativeMustStateTheFactsItExplains() {
    var hypothesis = new Hypothesis(
      UUID.randomUUID(),
      "title",
      "the price cut drove volume",
      "reasoning",
      List.of(fact.id()),
      List.of(),
      List.of(),
      List.of("nothing was checked"),
      List.of(),
      List.of(),
      "reason",
      30
    );
    var state = base.withHypotheses(List.of(hypothesis));
    var raw =
      "{\"alternatives\":[{\"statement\":\"a competitor bundle caused the drop\"," +
      "\"reasoning\":\"r\",\"rivalsHypothesisRef\":\"" +
      hypothesis.id() +
      "\",\"factRefs\":[],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.alternativeExplanations(raw, state)
    );
    assertTrue(failure.hint().contains("facts it explains"));
  }

  @Test
  void counterArgumentNeedsATargetHypothesis() {
    var raw =
      "{\"counterArguments\":[{\"statement\":\"the disclosure is self-selected\"," +
      "\"reasoning\":\"r\",\"targetHypothesisRef\":\"" +
      UUID.randomUUID() +
      "\",\"factRefs\":[],\"sourceRefs\":[],\"confidence\":20}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.counterArguments(raw, base)
    );
    assertTrue(failure.hint().contains("does not resolve"));
  }

  // ---- STG-12/13/14/15 ----------------------------------------------------

  @Test
  void falsificationConditionNeedsADecidableBoundary() {
    var hypothesisId = UUID.randomUUID();
    var state = base;
    var raw =
      "{\"falsificationConditions\":[{\"hypothesisRef\":\"" +
      hypothesisId +
      "\",\"observable\":\"the next disclosure\",\"comparison\":\"the reported volume\"," +
      "\"decisionBoundary\":\"if circumstances change\",\"statement\":\"s\"}]}";
    // PR-09 is checked before the boundary text: a dangling hypothesis ref fails.
    var failure = assertThrows(StageOutputException.class, () ->
      reader.falsificationConditions(raw, state)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
  }

  @Test
  void signalCannotBeMarkedAsAlreadyObserved() {
    var raw =
      "{\"signals\":[{\"signal\":\"a second buyer reports the price\",\"where\":\"procurement records\"," +
      "\"hypothesisRef\":\"" +
      UUID.randomUUID() +
      "\",\"window\":\"within two quarters\",\"status\":\"OBSERVED\"}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.corroboratingSignals(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
  }

  @Test
  void predictionMustBeTimeBoundedToTheFuture() {
    var raw =
      "{\"predictions\":[{\"hypothesisRef\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"s\",\"observable\":\"o\",\"expectedBy\":\"2025-01-01T00:00:00Z\"," +
      "\"verificationCriteria\":\"confirm or reject\",\"whereToCheck\":\"filings\",\"status\":\"OPEN\"}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.predictions(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
  }

  @Test
  void predictionStatusCannotBeAssertedAsConfirmed() {
    var raw =
      "{\"predictions\":[{\"hypothesisRef\":\"" +
      UUID.randomUUID() +
      "\",\"statement\":\"s\",\"observable\":\"o\",\"expectedBy\":\"2030-01-01T00:00:00Z\"," +
      "\"verificationCriteria\":\"confirm or reject\",\"whereToCheck\":\"filings\",\"status\":\"CONFIRMED\"}]}";
    var failure = assertThrows(StageOutputException.class, () ->
      reader.predictions(raw, base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
  }

  @Test
  void planItemNeedsADeadlineDistinguishableResultsAndARealVenue() {
    var hypothesisId = UUID.randomUUID();
    var noDeadline =
      "{\"plan\":[{\"whatToCheck\":\"w\",\"whereToCheck\":\"filings\",\"supportingResult\":\"up\"," +
      "\"contradictingResult\":\"down\",\"priority\":\"HIGH\",\"hypothesisRef\":\"" +
      hypothesisId +
      "\"}]}";
    assertThrows(StageOutputException.class, () -> reader.verificationPlan(noDeadline, base));

    var sameResults =
      "{\"plan\":[{\"whatToCheck\":\"w\",\"whereToCheck\":\"filings\",\"supportingResult\":\"same\"," +
      "\"contradictingResult\":\"same\",\"priority\":\"HIGH\",\"deadline\":\"2030-01-01T00:00:00Z\"," +
      "\"hypothesisRef\":\"" +
      hypothesisId +
      "\"}]}";
    assertThrows(StageOutputException.class, () -> reader.verificationPlan(sameResults, base));

    var vagueVenue =
      "{\"plan\":[{\"whatToCheck\":\"w\",\"whereToCheck\":\"further research\",\"supportingResult\":\"up\"," +
      "\"contradictingResult\":\"down\",\"priority\":\"HIGH\",\"deadline\":\"2030-01-01T00:00:00Z\"," +
      "\"hypothesisRef\":\"" +
      hypothesisId +
      "\"}]}";
    assertThrows(StageOutputException.class, () -> reader.verificationPlan(vagueVenue, base));
  }

  // ---- decoding -----------------------------------------------------------

  @Test
  void undecodableJsonIsMalformedOutput() {
    var failure = assertThrows(StageOutputException.class, () ->
      reader.facts("this is prose, not JSON", base)
    );
    assertEquals(StageFailure.Kind.MALFORMED_OUTPUT, failure.kind());
  }

  @Test
  void decodableButWronglyShapedJsonIsASchemaFailure() {
    var failure = assertThrows(StageOutputException.class, () ->
      reader.facts("{\"foo\":1}", base)
    );
    assertEquals(StageFailure.Kind.SCHEMA_INVALID, failure.kind());
    assertTrue(failure.hint().contains("expected a JSON object"));
  }
}
