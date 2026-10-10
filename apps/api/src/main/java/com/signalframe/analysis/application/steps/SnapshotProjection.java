package com.signalframe.analysis.application.steps;

import com.signalframe.contract.*;
import com.signalframe.shared.confidence.ConfidenceRubric;
import com.signalframe.shared.confidence.ConfidenceScore;
import com.signalframe.shared.confidence.FalsificationConditionText;
import java.time.Instant;
import java.util.*;

/**
 * Projects validated protocol artifacts onto the generated {@code AnalysisResult}
 * (SCH-01…SCH-13).
 *
 * <p>The projection never authors content: every field is a rendering of an
 * artifact that a stage produced and that Gate B/C/E accepted. Where the contract
 * has a structured field for a protocol value, the value is written to that field
 * — fact {@code verificationStatus}, {@code factRefs}, mechanism
 * {@code supportLevel}, hypothesis refs/assumptions/nested falsification
 * conditions, typed corroborating signals, verification-plan fields, predictions,
 * the {@code sourceAssessment}, the rubric's band/method/dimensions and the
 * {@code provenance} block.
 *
 * <p>Only one protocol value still has no schema slot: the three mandatory parts of
 * a falsification condition (observable, comparison, decision boundary). SCH-06
 * nests a condition as a {@code Statement}, so those parts are carried in its
 * {@code reasoning} through {@link FalsificationConditionText}, which is the same
 * convention the rubric reads back for D5.
 *
 * <p>Two confidences exist and are never mixed (EP-09/EP-11): the snapshot
 * {@code confidenceAssessment} is the rubric at snapshot scope, each
 * {@code Hypothesis.confidence} is the rubric at hypothesis scope, and every
 * {@code Fact.confidence} is source-report reliability derived from D1, never a
 * probability that the world is as described.
 */
final class SnapshotProjection {

  /** Protocol version stamped on every snapshot produced here (PR-06/PR-16). */
  static final String PROTOCOL_VERSION = "0.1";

  private SnapshotProjection() {}

  /**
   * Contract-shaped artifacts of one validated state. Built once and shared by the
   * snapshot score and every hypothesis-scope score, so the two judgments are
   * provably computed over the same objects.
   */
  record Artifacts(
    List<Fact> facts,
    List<Variable> variables,
    List<CausalLink> mechanisms,
    List<StakeholderImpact> stakeholders,
    List<Statement> firstOrderEffects,
    List<Statement> secondOrderEffects,
    List<Statement> alternativeExplanations,
    List<Statement> counterArguments,
    List<Statement> falsificationConditions,
    List<CorroboratingSignal> signals,
    List<Indicator> plan,
    List<Prediction> predictions,
    List<Statement> unknowns
  ) {}

  static Artifacts artifacts(PipelineState state) {
    // D1-only source-report reliability (CONFIDENCE_MODEL §8). D1 is a pure
    // function of the source assessment and the independence counts, so it can be
    // resolved before the fact list exists.
    int reliability = Math.min(
      100,
      20 * ConfidenceProjection.sourceQualityLevel(state)
    );
    return new Artifacts(
      state.facts().stream().map(f -> fact(f, reliability)).toList(),
      state.variables().stream().map(SnapshotProjection::variable).toList(),
      state.mechanisms().stream().map(SnapshotProjection::mechanism).toList(),
      state.stakeholders().stream().map(SnapshotProjection::stakeholder).toList(),
      state.firstOrderEffects().stream().map(e -> effect(e, false)).toList(),
      state.secondOrderEffects().stream().map(e -> effect(e, true)).toList(),
      state
        .alternativeExplanations()
        .stream()
        .map(SnapshotProjection::alternative)
        .toList(),
      state
        .counterArguments()
        .stream()
        .map(SnapshotProjection::counterArgument)
        .toList(),
      state
        .falsificationConditions()
        .stream()
        .map(SnapshotProjection::condition)
        .toList(),
      state
        .corroboratingSignals()
        .stream()
        .map(SnapshotProjection::signal)
        .toList(),
      state.verificationPlan().stream().map(SnapshotProjection::planItem).toList(),
      state.predictions().stream().map(SnapshotProjection::prediction).toList(),
      state.unknowns().stream().map(SnapshotProjection::unknown).toList()
    );
  }

  static AnalysisResult project(
    PipelineState state,
    ConfidenceRubric rubric,
    String domainStrategyId
  ) {
    var artifacts = artifacts(state);
    var inputs = ConfidenceProjection.snapshotScope(state, artifacts);
    var snapshotScore = rubric.score(inputs);
    var hypotheses = state
      .hypotheses()
      .stream()
      .map(h -> hypothesis(h, state, artifacts, rubric))
      .toList();
    return new AnalysisResult(
      bounded(state.summary(), "分析快照：未产生摘要。", 20000),
      artifacts.facts(),
      artifacts.variables(),
      artifacts.mechanisms(),
      artifacts.stakeholders(),
      artifacts.firstOrderEffects(),
      artifacts.secondOrderEffects(),
      hypotheses,
      artifacts.alternativeExplanations(),
      artifacts.counterArguments(),
      artifacts.falsificationConditions(),
      artifacts.signals(),
      artifacts.plan(),
      artifacts.unknowns(),
      confidenceAssessment(snapshotScore),
      List.of(),
      false,
      state.demo(),
      sourceAssessment(state),
      artifacts.predictions(),
      PROTOCOL_VERSION,
      provenance(state, domainStrategyId)
    );
  }

  // ---- snapshot / item renderings ----------------------------------------

  /**
   * STG-02 reports an absent publication date as the literal `UNKNOWN` because it
   * may not infer one; SCH-01 represents absence as a null `Instant`. An
   * unparsable value is also null — a date is never invented.
   */
  private static Instant instant(String value) {
    if (value == null || value.isBlank() || "UNKNOWN".equals(value)) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException notATimestamp) {
      return null;
    }
  }

  static SourceAssessment sourceAssessment(PipelineState state) {
    var value = state.sourceAssessment();
    if (value == null) return null;
    return new SourceAssessment(
      value.sourceType().name(),
      value.publisher(),
      instant(value.publishedAt()),
      value.primaryOrSecondary().name(),
      value.independence().name(),
      null,
      value.contentCompleteness().name(),
      value.notes()
    );
  }

  private static ConfidenceAssessment confidenceAssessment(ConfidenceScore score) {
    return new ConfidenceAssessment(
      score.score(),
      score.reason(),
      false,
      score.band(),
      score.method(),
      score.rubricVersion(),
      score.dimensions(),
      score.advisoryScore()
    );
  }

  /**
   * PR-06/PR-16 provenance. {@code modelRunIds} is empty because the pipeline state
   * does not carry them: the audit rows exist in {@code model_runs} keyed by job id,
   * and linking them by id is a TASK-03 follow-up rather than something this
   * projection may invent.
   */
  private static Provenance provenance(
    PipelineState state,
    String domainStrategyId
  ) {
    return new Provenance(
      PROTOCOL_VERSION,
      domainStrategyId,
      ConfidenceRubric.VERSION,
      List.copyOf(new LinkedHashSet<>(state.promptVersions())),
      List.of()
    );
  }

  private static Fact fact(ProtocolModel.Fact fact, int reliability) {
    return new Fact(
      fact.id(),
      ClaimType.FACT,
      fact.statement(),
      fact.reasoning(),
      reliability,
      fact.sourceRefs(),
      fact.verificationStatus().name()
    );
  }

  private static Variable variable(ProtocolModel.Variable value) {
    return new Variable(
      value.id(),
      value.name(),
      value.direction().name(),
      ClaimType.INFERENCE,
      value.statement(),
      value.reasoning(),
      value.confidence(),
      value.sourceRefs(),
      value.previousState(),
      value.currentState(),
      value.magnitude(),
      value.whyItMatters(),
      value.factRefs()
    );
  }

  private static CausalLink mechanism(ProtocolModel.Mechanism value) {
    return new CausalLink(
      value.id(),
      value.from(),
      value.to(),
      ClaimType.INFERENCE,
      value.explanation(),
      trail(
        value.reasoning(),
        "assumptions",
        join(value.assumptions()),
        "corroboratingSignal",
        value.corroboratingSignal()
      ),
      value.confidence(),
      value.sourceRefs(),
      value.supportLevel().name(),
      value.factRefs()
    );
  }

  private static StakeholderImpact stakeholder(ProtocolModel.Stakeholder value) {
    return new StakeholderImpact(
      value.id(),
      value.stakeholder(),
      value.direction().name(),
      ClaimType.INFERENCE,
      value.statement(),
      value.reasoning(),
      value.confidence(),
      value.sourceRefs(),
      value.factRefs()
    );
  }

  private static Statement effect(ProtocolModel.Effect value, boolean secondOrder) {
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      value.reasoning(),
      value.confidence(),
      value.sourceRefs(),
      value.factRefs(),
      secondOrder ? value.derivedFromRefs() : List.of(),
      null,
      null
    );
  }

  private static Hypothesis hypothesis(
    ProtocolModel.Hypothesis value,
    PipelineState state,
    Artifacts artifacts,
    ConfidenceRubric rubric
  ) {
    var scopeScore = rubric.score(
      ConfidenceProjection.hypothesisScope(state, value, artifacts)
    );
    var sourceRefs = artifacts
      .facts()
      .stream()
      .filter(f -> value.supportingFactRefs().contains(f.id()))
      .flatMap(f -> f.sourceRefs().stream())
      .distinct()
      .toList();
    var conditions = artifacts
      .falsificationConditions()
      .stream()
      .filter(c -> value.id().equals(c.targetHypothesisRef()))
      .toList();
    return new Hypothesis(
      value.id(),
      value.title(),
      bounded(value.statement(), "UNKNOWN", 20000),
      HypothesisStatus.OPEN,
      scopeScore.reason(),
      state.snapshotCreatedAt(),
      state.snapshotCreatedAt(),
      ClaimType.HYPOTHESIS,
      value.statement(),
      value.reasoning(),
      scopeScore.score(),
      sourceRefs,
      value.supportingFactRefs(),
      value.supportingEvidenceRefs(),
      value.contradictingEvidenceRefs(),
      value.assumptions(),
      value.alternativeRefs(),
      conditions,
      scopeScore.band()
    );
  }

  private static Statement alternative(ProtocolModel.Alternative value) {
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      value.reasoning(),
      value.confidence(),
      value.sourceRefs(),
      value.factRefs(),
      List.of(),
      null,
      value.rivalsHypothesisRef()
    );
  }

  static Statement counterArgument(ProtocolModel.CounterArgument value) {
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      value.reasoning(),
      value.confidence(),
      value.sourceRefs(),
      value.factRefs(),
      List.of(),
      value.targetHypothesisRef(),
      null
    );
  }

  static Statement condition(ProtocolModel.FalsificationCondition value) {
    return new Statement(
      ClaimType.INFERENCE,
      bounded(value.statement(), value.observable(), 20000),
      FalsificationConditionText.append(
        "Pre-registered falsification condition.",
        value.observable(),
        value.comparison(),
        value.decisionBoundary()
      ),
      0,
      List.of(),
      List.of(),
      List.of(),
      value.hypothesisRef(),
      null
    );
  }

  static CorroboratingSignal signal(ProtocolModel.Signal value) {
    return new CorroboratingSignal(
      ClaimType.INFERENCE,
      value.signal(),
      "Expected observable, not yet observed.",
      0,
      List.of(),
      value.hypothesisRef(),
      bounded(value.where(), "UNKNOWN", 20000),
      value.window(),
      value.status().name()
    );
  }

  static Indicator planItem(ProtocolModel.PlanItem value) {
    return new Indicator(
      value.id(),
      value.predictionRef(),
      value.whatToCheck(),
      bounded(value.whereToCheck(), "UNKNOWN", 20000),
      value.deadline() == null
        ? value.priority().name()
        : value.priority().name() + " " + value.deadline(),
      ClaimType.INFERENCE,
      "Supporting: " +
      value.supportingResult() +
      " | Contradicting: " +
      value.contradictingResult(),
      value.reasoning(),
      value.confidence(),
      value.sourceRefs(),
      value.whereToCheck(),
      value.supportingResult(),
      value.contradictingResult(),
      value.priority().name(),
      value.deadline(),
      value.hypothesisRef()
    );
  }

  private static Prediction prediction(ProtocolModel.Prediction value) {
    return new Prediction(
      value.id(),
      value.hypothesisRef(),
      value.statement(),
      ClaimType.PREDICTION.name(),
      value.expectedBy(),
      value.status().name(),
      value.verificationCriteria(),
      value.observable(),
      value.whereToCheck(),
      value.basisFactRefs()
    );
  }

  /**
   * UNKNOWN is a first-class output (EP-01/EP-06): it carries the type
   * {@code UNKNOWN}, never a mislabelled INFERENCE, and the reason and the
   * resolving action stay readable in the reasoning field.
   */
  static Statement unknown(ProtocolModel.Unknown value) {
    return new Statement(
      ClaimType.UNKNOWN,
      value.statement(),
      trail(
        "UNKNOWN.",
        "unknownReason",
        value.unknownReason(),
        "wouldResolveWith",
        value.wouldResolveWith()
      ),
      0,
      value.sourceRefs(),
      List.of(),
      List.of(),
      null,
      null
    );
  }

  // ---- helpers -----------------------------------------------------------

  /**
   * Deterministic audit trailer for the protocol values the frozen schema still
   * has no slot for (mechanism assumptions, UNKNOWN reason/resolution). It renders
   * values that already exist inside the snapshot and is never used to validate or
   * to author content.
   */
  private static String trail(String text, String... keyValues) {
    var parts = new ArrayList<String>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      String value = keyValues[i + 1];
      if (value == null || value.isBlank()) continue;
      parts.add(
        keyValues[i] +
        "=" +
        value.replaceAll("\\s+", " ").replace(";", ",").trim()
      );
    }
    if (parts.isEmpty()) return text;
    return (text == null ? "" : text) + " | protocol: " + String.join("; ", parts);
  }

  private static String join(List<String> values) {
    return values.isEmpty() ? "" : String.join(" / ", values);
  }

  private static String bounded(String value, String fallback, int max) {
    String text = value == null || value.isBlank() ? fallback : value;
    if (text == null || text.isBlank()) return "UNKNOWN";
    return text.length() <= max ? text : text.substring(0, max);
  }
}
