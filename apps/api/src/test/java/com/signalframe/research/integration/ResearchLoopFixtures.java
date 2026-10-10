package com.signalframe.research.integration;

import com.signalframe.contract.Analysis;
import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.CausalLink;
import com.signalframe.contract.ClaimType;
import com.signalframe.contract.CorroboratingSignal;
import com.signalframe.contract.Fact;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
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
 * Stored-artifact builders for the Wave 2B integration gate.
 *
 * <p>These build the same shapes a real pipeline writes: a complete protocol
 * snapshot on {@code analyses.payload}, a hypothesis payload whose
 * {@code confidenceReason} is the exact rendering
 * {@code DeterministicConfidenceRubric.reason()} emits, and the {@code CREATED}
 * timeline event. A transition reads the previous dimension breakdown back out of
 * that rendering, so a fixture with prose in {@code confidenceReason} would not
 * exercise the dimension-movement path at all.
 *
 * <p>The values are chosen so the initial snapshot scores exactly 49:
 * {@code D1=3(12) + D2=5(25) + D3=0(0) + D4=3(12) + D5=4(12) = 61 raw}, with
 * {@code CAP-A=49} binding (D3 = 0) and {@code CAP-F=84}. That is the
 * CONFIDENCE_MODEL §7 Example-5 shape: a good primary source with no independent
 * corroboration pinned at {@code LOW}.
 */
final class ResearchLoopFixtures {

  /** Equal to the instant the seeded snapshot was produced. */
  static final Instant SNAPSHOT_AT = Instant.parse("2026-01-05T09:00:00Z");

  /** The publisher of the article the snapshot came from. */
  static final String ARTICLE_PUBLISHER = "Example Wire";

  /** A second, genuinely independent publisher used for SUPPORTS evidence. */
  static final String INDEPENDENT_PUBLISHER = "Independent Ledger";

  /** The rubric's own rendering of the seeded snapshot: 61 raw, capped to 49. */
  static final String INITIAL_CONFIDENCE_REASON =
    "RUBRIC 0.1 | method=RUBRIC | score=49 band=LOW raw=61 capped=true" +
    " | D1_SOURCE_QUALITY=3(12/20)" +
    " | D2_EVIDENCE_DIRECTNESS=5(25/25)" +
    " | D3_INDEPENDENT_CORROBORATION=0(0/20)" +
    " | D4_MECHANISM_SUPPORT=3(12/20)" +
    " | D5_COUNTER_EVIDENCE_RESILIENCE=4(12/15)" +
    " | caps=CAP-A=49,CAP-F=84" +
    " | Confidence Score is not a probability of truth.";

  /** The score {@link #INITIAL_CONFIDENCE_REASON} renders. */
  static final int INITIAL_CONFIDENCE = 49;

  /**
   * The score after an independent SUPPORTS evidence item releases CAP-A:
   * {@code D1=3(12) + D2=5(25) + D3=3(12) + D4=3(12) + D5=4(12) = 73 raw}, with
   * only {@code CAP-F=84} left, so 73 is not capped and the band is {@code MEDIUM}.
   */
  static final int CORROBORATED_CONFIDENCE = 73;

  private ResearchLoopFixtures() {}

  static SourceRef ref(UUID sourceId, String quote) {
    return new SourceRef(sourceId, quote, 0, quote.length());
  }

  static Fact fact(UUID id, UUID sourceId, String statement) {
    return new Fact(
      id,
      ClaimType.FACT,
      statement,
      "reported claim, reproduced with its span",
      20,
      List.of(ref(sourceId, statement)),
      "REPORTED"
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

  /** A verification plan item with distinguishable outcomes and a deadline (CAP-C). */
  static Indicator planItem(UUID hypothesisId) {    return new Indicator(
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
      SNAPSHOT_AT.plusSeconds(86_400L * 90),
      hypothesisId
    );
  }

  /**
   * A corroborating signal: an expected future observable with no stance, because
   * nothing has been observed yet (EPISTEMIC_TYPES §5.2). It must never become an
   * evidence row merely by being described.
   */
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

  static SourceAssessment source(
    String publisher,
    String primaryOrSecondary,
    String completeness
  ) {
    return new SourceAssessment(
      "NEWS_REPORT",
      publisher,
      SNAPSHOT_AT,
      primaryOrSecondary,
      "SINGLE_SOURCE",
      publisher,
      completeness,
      List.of()
    );
  }

  /** A complete but minimal protocol snapshot; unread artifacts are explicit empties. */
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

  static Analysis analysis(
    UUID analysisId,
    UUID newsId,
    UUID jobId,
    AnalysisResult result
  ) {
    return new Analysis(analysisId, newsId, jobId, null, null, result, SNAPSHOT_AT);
  }

  /**
   * A stored hypothesis payload at a given status and score.
   *
   * @param confidenceReason the previous rendering; pass {@link
   *     #INITIAL_CONFIDENCE_REASON} for a rubric-scored starting point, or a prose
   *     string to model a pre-protocol (legacy) payload
   */
  static Hypothesis hypothesis(
    UUID id,
    HypothesisStatus status,
    int confidence,
    String confidenceReason,
    List<UUID> supportingFactRefs,
    List<Statement> falsificationConditions
  ) {
    return new Hypothesis(
      id,
      "input costs squeezed unit margin",
      "input costs squeeze the reported unit margin",
      status,
      confidenceReason,
      SNAPSHOT_AT,
      SNAPSHOT_AT,
      ClaimType.HYPOTHESIS,
      "input costs squeezed unit margin",
      "the reported margin decline is consistent with an input-cost squeeze",
      confidence,
      List.of(),
      supportingFactRefs,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      falsificationConditions,
      null
    );
  }

  static HypothesisEvent createdEvent(
    UUID hypothesisId,
    HypothesisStatus status,
    int confidence,
    String confidenceReason
  ) {
    return new HypothesisEvent(
      UUID.randomUUID(),
      hypothesisId,
      "CREATED",
      null,
      confidence,
      confidenceReason,
      SNAPSHOT_AT,
      null,
      status
    );
  }
}
