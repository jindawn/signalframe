package com.signalframe.research.hypotheses;

import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.CausalLink;
import com.signalframe.contract.ClaimType;
import com.signalframe.contract.CorroboratingSignal;
import com.signalframe.contract.Fact;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.Indicator;
import com.signalframe.contract.SourceAssessment;
import com.signalframe.contract.SourceRef;
import com.signalframe.contract.Statement;
import com.signalframe.shared.confidence.FalsificationConditionText;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builders for the stored shapes a hypothesis transition reasons about.
 *
 * <p>Only what the transition and the rubric actually read is populated; the rest
 * is an explicit empty list or null, which is what the protocol says an absent
 * artifact means ("evaluated, none exist" versus "not evaluated" is the stage's
 * job, not the fixture's).
 */
final class HypothesisFixtures {

  static final Instant SNAPSHOT_AT = Instant.parse("2026-01-05T09:00:00Z");

  /**
   * A previous score's rendering, in the exact grammar
   * {@code DeterministicConfidenceRubric.reason()} emits — which is what a
   * pipeline-created hypothesis stores. A transition reads it back to name the
   * dimensions that moved, so the fixture must look like the real thing rather
   * than like prose.
   */
  static final String PREVIOUS_RENDERING =
    "RUBRIC 0.1 | method=RUBRIC | score=20 band=VERY_LOW raw=20 capped=false | " +
    "D1_SOURCE_QUALITY=1(4/20) | D2_EVIDENCE_DIRECTNESS=2(10/25) | " +
    "D3_INDEPENDENT_CORROBORATION=0(0/20) | D4_MECHANISM_SUPPORT=0(0/20) | " +
    "D5_COUNTER_EVIDENCE_RESILIENCE=2(6/15) | caps=CAP-A=49 | " +
    "Confidence Score is not a probability of truth.";

  private HypothesisFixtures() {}

  static SourceRef ref(UUID sourceId, String quote) {
    return new SourceRef(sourceId, quote, 0, quote.length());
  }

  static Fact fact(UUID id, UUID sourceId, String statement) {
    return fact(id, sourceId, statement, "REPORTED");
  }

  static Fact fact(
    UUID id,
    UUID sourceId,
    String statement,
    String verificationStatus
  ) {
    return new Fact(
      id,
      ClaimType.FACT,
      statement,
      "reported claim, reproduced with its span",
      20,
      List.of(ref(sourceId, statement)),
      verificationStatus
    );
  }

  /** STG-12's three mandatory parts, in the frozen schema's canonical trailer. */
  static Statement falsificationCondition(
    String text,
    String observable,
    String comparison,
    String boundary
  ) {
    return new Statement(
      ClaimType.INFERENCE,
      text,
      FalsificationConditionText.append(text, observable, comparison, boundary),
      20,
      List.of(),
      List.of(),
      List.of(),
      null,
      null
    );
  }

  static Statement counterArgument(UUID targetHypothesis, List<UUID> factRefs) {
    return new Statement(
      ClaimType.INFERENCE,
      "the reported figure is a self-selected disclosure",
      "a definitional change would explain the movement without the mechanism",
      20,
      List.of(),
      factRefs,
      List.of(),
      targetHypothesis,
      null
    );
  }

  static CausalLink mechanism(UUID id, List<UUID> factRefs, String supportLevel) {
    return new CausalLink(
      id,
      "input cost",
      "unit margin",
      ClaimType.INFERENCE,
      "input costs pass through to unit margin",
      "the pass-through is documented in the filing",
      20,
      List.of(),
      supportLevel,
      factRefs
    );
  }

  static Indicator planItem(UUID hypothesisId) {
    return new Indicator(
      UUID.randomUUID(),
      null,
      "next quarterly disclosure",
      "reported unit margin",
      "quarterly",
      ClaimType.INFERENCE,
      "watch the next filing",
      "a filing that restates the margin",
      20,
      List.of(),
      "the issuer's filings page",
      "margin holds or improves",
      "margin falls further",
      "HIGH",
      SNAPSHOT_AT.plusSeconds(86_400 * 90),
      hypothesisId
    );
  }

  static CorroboratingSignal signal(UUID hypothesisId) {
    return new CorroboratingSignal(
      ClaimType.INFERENCE,
      "an independent supplier should report the same volume",
      "a second observable would corroborate the mechanism",
      20,
      List.of(),
      hypothesisId,
      "supplier disclosures",
      "within two quarters",
      "NOT_OBSERVED"
    );
  }

  /**
   * A source assessment. {@code independence} is the snapshot's own reading of how
   * many sources it holds; evidence independence is counted separately from the
   * evidence rows.
   */
  static SourceAssessment source(
    String publisher,
    Instant publishedAt,
    String primaryOrSecondary,
    String independence,
    String completeness
  ) {
    return new SourceAssessment(
      "NEWS_REPORT",
      publisher,
      publishedAt,
      primaryOrSecondary,
      independence,
      publisher,
      completeness,
      List.of()
    );
  }

  /** A minimal but complete snapshot envelope; unread fields are explicit empties. */
  static AnalysisResult snapshot(
    SourceAssessment source,
    List<Fact> facts,
    List<CausalLink> mechanisms,
    List<Statement> counterArguments,
    List<Statement> falsificationConditions,
    List<CorroboratingSignal> signals,
    List<Indicator> plan
  ) {
    return new AnalysisResult(
      "the snapshot's summary",
      facts,
      List.of(),
      mechanisms,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      counterArguments,
      falsificationConditions,
      signals,
      plan,
      List.of(),
      null,
      List.of(),
      false,
      true,
      source,
      List.of(),
      "0.1",
      null
    );
  }

  /** A stored hypothesis payload at a given status and score. */
  static Hypothesis hypothesis(
    UUID id,
    UUID analysisId,
    HypothesisStatus status,
    int confidence
  ) {
    return new Hypothesis(
      id,
      "input costs squeezed unit margin",
      "input costs squeezed unit margin",
      status,
      PREVIOUS_RENDERING,
      SNAPSHOT_AT,
      SNAPSHOT_AT,
      ClaimType.HYPOTHESIS,
      "input costs squeezed unit margin",
      "the reported margin decline is consistent with an input-cost squeeze",
      confidence,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      null
    );
  }
}
