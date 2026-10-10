package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.DomainType;
import com.signalframe.contract.SourceRef;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gate B/C/E over the assembled snapshot.
 *
 * <p>A stage that never ran, or a mandatory artifact that is absent, fails the job:
 * a silent empty array must never read as "evaluated, nothing found" (PR-05). An
 * artifact the protocol cannot accept — an unfalsifiable hypothesis, an
 * unresolvable reference, a prediction with no usable plan — is demoted to an
 * explicit UNKNOWN item instead (EP-01/EP-02).
 */
class EpistemicGateTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  private final EpistemicGateStage gate = new EpistemicGateStage();
  private com.signalframe.contract.NewsItem news;
  private Fact fact;
  private Hypothesis hypothesis;
  private Alternative alternative;
  private FalsificationCondition condition;

  @BeforeEach
  void setUp() {
    news = PipelineFixtures.news();
    String quote = news.source().text().substring(0, 12);
    fact = new Fact(
      UUID.randomUUID(),
      quote,
      "Reported source claim, not independently verified.",
      List.of(new SourceRef(news.source().id(), quote, 0, quote.length())),
      ProofStatus.REPORTED
    );
    hypothesis = new Hypothesis(
      UUID.randomUUID(),
      "price cut drives volume",
      "the price cut is consistent with a volume response",
      "the facts are consistent with a documented elasticity",
      List.of(fact.id()),
      List.of(),
      List.of(),
      List.of("no independent evidence was checked"),
      List.of(),
      List.of(),
      "single source, no independent confirmation",
      30
    );
    alternative = new Alternative(
      UUID.randomUUID(),
      "a definitional change reduced the reported price",
      "the reporting basis may have changed",
      hypothesis.id(),
      List.of(fact.id()),
      List.of(),
      20
    );
    condition = new FalsificationCondition(
      UUID.randomUUID(),
      hypothesis.id(),
      "the next disclosure",
      "the reported volume",
      "volume below the pre-registered threshold by 2026-06-30",
      "if the disclosure contradicts the mechanism, reject the hypothesis"
    );
  }

  /** Minimal complete snapshot: every mandatory stage ran and each artifact exists. */
  private PipelineState complete() {
    var state = PipelineState.initial(UUID.randomUUID(), "corr", news, CREATED);
    for (var stage : EpistemicGateStage.MANDATORY_STAGES) state = state.executed(
      stage,
      null
    );
    return state
      .withSourceAssessment(
        new SourceAssessment(
          SourceType.NEWS_REPORT,
          "UNKNOWN",
          "UNKNOWN",
          SourceClass.UNKNOWN,
          Independence.SINGLE_SOURCE,
          Completeness.PARTIAL,
          List.of()
        )
      )
      .withDomain(DomainType.OTHER)
      .withFacts(List.of(fact))
      .withHypotheses(List.of(hypothesis))
      .withAlternativeExplanations(List.of(alternative))
      .withFalsificationConditions(List.of(condition));
  }

  @Test
  void aMandatoryStageThatNeverRanFailsTheJob() {
    var state = complete();
    var trimmed = PipelineState.initial(
      state.jobId(),
      state.correlationId(),
      news,
      CREATED
    );
    for (var stage : EpistemicGateStage.MANDATORY_STAGES) {
      if (!stage.equals("PredictionGeneration")) trimmed = trimmed.executed(
        stage,
        null
      );
    }
    var incomplete = trimmed
      .withSourceAssessment(state.sourceAssessment())
      .withFacts(state.facts())
      .withHypotheses(state.hypotheses())
      .withAlternativeExplanations(state.alternativeExplanations())
      .withFalsificationConditions(state.falsificationConditions());
    var failure = assertThrows(StageFailure.class, () -> gate.execute(incomplete));
    assertEquals(StageFailure.Kind.MISSING_ARTIFACT, failure.kind());
    assertTrue(failure.detail().contains("PredictionGeneration"));
  }

  @Test
  void anAnalysisWithoutFactsFailsTheJob() {
    var state = complete().withFacts(List.of());
    var failure = assertThrows(StageFailure.class, () -> gate.execute(state));
    assertEquals(StageFailure.Kind.MISSING_ARTIFACT, failure.kind());
  }

  @Test
  void aHypothesisWithoutAFalsificationConditionIsDemotedToUnknown() {
    var state = complete().withFalsificationConditions(List.of());
    var gated = gate.execute(state);
    assertTrue(gated.hypotheses().isEmpty());
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u ->
          u.statement().contains("Dropped hypothesis") &&
          u.unknownReason().contains("falsification")
        ),
      gated.unknowns().toString()
    );
  }

  @Test
  void aHypothesisWithoutARivalExplanationIsDemotedToUnknown() {
    var state = complete().withAlternativeExplanations(List.of());
    var gated = gate.execute(state);
    assertTrue(gated.hypotheses().isEmpty());
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u -> u.unknownReason().contains("alternative explanation"))
    );
  }

  @Test
  void aFalsifiableHypothesisKeepsItsConditionAndRivalReferences() {
    var gated = gate.execute(complete());
    assertEquals(1, gated.hypotheses().size());
    assertEquals(
      List.of(condition.id()),
      gated.hypotheses().getFirst().falsificationRefs()
    );
    assertEquals(
      List.of(alternative.id()),
      gated.hypotheses().getFirst().alternativeRefs()
    );
  }

  @Test
  void anUnknownItemCanNeverBeCitedAsSupport() {
    var unknownId = UUID.randomUUID();
    var variable = new Variable(
      UUID.randomUUID(),
      "price",
      "UNKNOWN",
      "lower",
      Direction.DOWN,
      "30%",
      "changes the cost base of every buyer",
      "price fell",
      "the fact states a decline",
      List.of(unknownId),
      List.of(),
      30
    );
    var state = complete()
      .withUnknowns(List.of(new Unknown(unknownId, "gap", "missing", "resolve")))
      .withVariables(List.of(variable));
    var failure = assertThrows(StageFailure.class, () -> gate.execute(state));
    assertEquals(StageFailure.Kind.VALIDATION_FAILED, failure.kind());
    assertTrue(failure.detail().contains("UNKNOWN"));
  }

  @Test
  void noDocumentedMechanismIsReportedAsUnknownRatherThanInvented() {
    var gated = gate.execute(complete());
    assertTrue(gated.mechanisms().isEmpty());
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("No causal mechanism"))
    );
  }

  @Test
  void aSingleSourceSnapshotMustStateWhatIsMissing() {
    var gated = gate.execute(complete());
    assertFalse(gated.unknowns().isEmpty());
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u ->
          u.statement().contains("No independent source corroborates")
        )
    );
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("publication date"))
    );
  }

  @Test
  void aHypothesisWithNeitherPredictionNorSignalIsFlagged() {
    var gated = gate.execute(complete());
    assertEquals(1, gated.hypotheses().size());
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("neither a prediction"))
    );
  }

  @Test
  void aPredictionWithoutAUsablePlanItemIsDemotedToUnknown() {
    var prediction = new Prediction(
      UUID.randomUUID(),
      hypothesis.id(),
      "the disclosure will show higher volume",
      "the reported volume in the next disclosure",
      CREATED.plusSeconds(86_400L * 90),
      "confirmed if volume rises, rejected if it falls",
      "the company's investor page",
      PredictionStatus.OPEN
    );
    var gated = gate.execute(complete().withPredictions(List.of(prediction)));
    assertTrue(gated.predictions().isEmpty());
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("Dropped prediction without"))
    );
  }

  @Test
  void aPredictionWithACoveringPlanItemSurvivesAndItsPlanItemIsKept() {
    var prediction = new Prediction(
      UUID.randomUUID(),
      hypothesis.id(),
      "the disclosure will show higher volume",
      "the reported volume in the next disclosure",
      CREATED.plusSeconds(86_400L * 90),
      "confirmed if volume rises, rejected if it falls",
      "the company's investor page",
      PredictionStatus.OPEN
    );
    var plan = new PlanItem(
      UUID.randomUUID(),
      "is the volume response visible?",
      "the next disclosure",
      "volume rises",
      "volume falls",
      PlanPriority.HIGH,
      CREATED.plusSeconds(86_400L * 30),
      hypothesis.id(),
      prediction.id(),
      "verify the mechanism",
      "decides whether the mechanism holds",
      List.of(),
      30
    );
    var gated = gate.execute(
      complete()
        .withPredictions(List.of(prediction))
        .withVerificationPlan(List.of(plan))
    );
    assertEquals(1, gated.predictions().size());
    assertEquals(1, gated.verificationPlan().size());
  }

  @Test
  void aCounterArgumentThatRestsOnNoFactIsFlagged() {
    var counter = new CounterArgument(
      UUID.randomUUID(),
      "the disclosure is self-selected",
      "a definitional change could explain the move",
      hypothesis.id(),
      List.of(),
      List.of(),
      20
    );
    var gated = gate.execute(complete().withCounterArguments(List.of(counter)));
    assertTrue(
      gated
        .unknowns()
        .stream()
        .anyMatch(u -> u.statement().contains("No counter-argument rests on a fact"))
    );
  }

  @Test
  void duplicatesInTheUnknownListAreCollapsed() {
    var gated = gate.execute(complete());
    var keys = new HashSet<String>();
    for (var unknown : gated.unknowns()) {
      assertTrue(
        keys.add(unknown.statement() + "::" + unknown.unknownReason()),
        "duplicate UNKNOWN item: " + unknown
      );
    }
  }
}
