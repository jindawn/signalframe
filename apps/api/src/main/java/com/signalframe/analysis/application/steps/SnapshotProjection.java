package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel;
import com.signalframe.contract.*;
import java.util.*;

/**
 * Projects validated protocol artifacts onto the frozen v0.1 {@code AnalysisResult}.
 *
 * <p>The projection never authors content: every field is a rendering of an
 * artifact that a stage produced and that Gate B/C/E accepted. Where the v0.1
 * contract has no field for a protocol value (fact refs, support levels, derived
 * refs, rivals, predictions, rubric dimensions), the value is rendered into the
 * item's free-text field by {@link Provenance} instead of being dropped, and the
 * remaining schema gap is stated as an explicit UNKNOWN item.
 *
 * <p>Two confidences exist and are never mixed (EP-09/EP-11): the snapshot
 * {@code confidenceAssessment} is the rubric at snapshot scope, each
 * {@code Hypothesis.confidence} is the rubric at hypothesis scope, and every
 * {@code Fact.confidence} is source-report reliability derived from D1, never a
 * probability that the world is as described.
 */
final class SnapshotProjection {

  private SnapshotProjection() {}

  static AnalysisResult project(
    PipelineState state,
    ConfidenceScore snapshotScore,
    ConfidenceInputs inputs,
    ConfidenceRubric rubric,
    String domainStrategyId
  ) {
    int factConfidence = Math.min(100, 20 * inputs.levelD1());
    var facts = state
      .facts()
      .stream()
      .map(f -> fact(f, factConfidence))
      .toList();
    var variables = state
      .variables()
      .stream()
      .map(SnapshotProjection::variable)
      .toList();
    var mechanisms = state
      .mechanisms()
      .stream()
      .map(SnapshotProjection::mechanism)
      .toList();
    var stakeholders = state
      .stakeholders()
      .stream()
      .map(SnapshotProjection::stakeholder)
      .toList();
    var firstOrder = state
      .firstOrderEffects()
      .stream()
      .map(e -> effect(e, false))
      .toList();
    var secondOrder = state
      .secondOrderEffects()
      .stream()
      .map(e -> effect(e, true))
      .toList();
    var hypotheses = state
      .hypotheses()
      .stream()
      .map(h -> hypothesis(h, state, rubric))
      .toList();
    var alternatives = state
      .alternativeExplanations()
      .stream()
      .map(SnapshotProjection::alternative)
      .toList();
    var counters = state
      .counterArguments()
      .stream()
      .map(SnapshotProjection::counterArgument)
      .toList();
    var conditions = state
      .falsificationConditions()
      .stream()
      .map(SnapshotProjection::condition)
      .toList();
    var signals = state
      .corroboratingSignals()
      .stream()
      .map(SnapshotProjection::signal)
      .toList();
    var plan = state
      .verificationPlan()
      .stream()
      .map(SnapshotProjection::planItem)
      .toList();
    var unknowns = state.unknowns().stream().map(SnapshotProjection::unknown).toList();
    return new AnalysisResult(
      bounded(state.summary(), "分析快照：未产生摘要。", 20000),
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrder,
      secondOrder,
      hypotheses,
      alternatives,
      counters,
      conditions,
      signals,
      plan,
      unknowns,
      new ConfidenceAssessment(
        snapshotScore.score(),
        confidenceReason(snapshotScore, state, domainStrategyId),
        false
      ),
      List.of(),
      false,
      state.demo()
    );
  }

  // ---- item renderings ---------------------------------------------------

  private static Fact fact(ProtocolModel.Fact fact, int reliability) {
    var fields = Provenance.fields();
    fields.put("protocolVersion", "0.1");
    fields.put("verificationStatus", fact.verificationStatus().name());
    return new Fact(
      fact.id(),
      ClaimType.FACT,
      fact.statement(),
      Provenance.append(fact.reasoning(), fields),
      Math.max(0, Math.min(100, reliability)),
      fact.sourceRefs()
    );
  }

  private static Variable variable(ProtocolModel.Variable value) {
    var fields = Provenance.fields();
    fields.put("factRefs", Provenance.ids(value.factRefs()));
    fields.put("previousState", value.previousState());
    fields.put("currentState", value.currentState());
    fields.put("magnitude", value.magnitude());
    fields.put("whyItMatters", value.whyItMatters());
    return new Variable(
      value.id(),
      value.name(),
      value.direction().name(),
      ClaimType.INFERENCE,
      value.statement(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static CausalLink mechanism(ProtocolModel.Mechanism value) {
    var fields = Provenance.fields();
    fields.put("supportLevel", value.supportLevel().name());
    fields.put("factRefs", Provenance.ids(value.factRefs()));
    fields.put("assumptions", Provenance.texts(value.assumptions()));
    fields.put("corroboratingSignal", value.corroboratingSignal());
    return new CausalLink(
      value.id(),
      value.from(),
      value.to(),
      ClaimType.INFERENCE,
      value.explanation(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static StakeholderImpact stakeholder(ProtocolModel.Stakeholder value) {
    var fields = Provenance.fields();
    fields.put("factRefs", Provenance.ids(value.factRefs()));
    return new StakeholderImpact(
      value.id(),
      value.stakeholder(),
      value.direction().name(),
      ClaimType.INFERENCE,
      value.statement(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static Statement effect(ProtocolModel.Effect value, boolean secondOrder) {
    var fields = Provenance.fields();
    fields.put("factRefs", Provenance.ids(value.factRefs()));
    if (secondOrder) fields.put(
      "derivedFromRefs",
      Provenance.ids(value.derivedFromRefs())
    );
    fields.put("order", secondOrder ? "SECOND" : "FIRST");
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static Hypothesis hypothesis(
    ProtocolModel.Hypothesis value,
    PipelineState state,
    ConfidenceRubric rubric
  ) {
    var scopeInputs = ConfidenceProjection.hypothesisScope(state, value);
    var scopeScore = rubric.score(scopeInputs);
    var fields = Provenance.fields();
    fields.put("supportingFactRefs", Provenance.ids(value.supportingFactRefs()));
    fields.put("supportingEvidenceRefs", Provenance.ids(value.supportingEvidenceRefs()));
    fields.put(
      "contradictingEvidenceRefs",
      Provenance.ids(value.contradictingEvidenceRefs())
    );
    fields.put("alternativeRefs", Provenance.ids(value.alternativeRefs()));
    fields.put("falsificationRefs", Provenance.ids(value.falsificationRefs()));
    fields.put("assumptions", Provenance.texts(value.assumptions()));
    var sourceRefs = state
      .facts()
      .stream()
      .filter(f -> value.supportingFactRefs().contains(f.id()))
      .flatMap(f -> f.sourceRefs().stream())
      .distinct()
      .toList();
    return new Hypothesis(
      value.id(),
      value.title(),
      bounded(value.statement(), "UNKNOWN", 20000),
      "OPEN",
      confidenceReason(scopeScore, state, null),
      state.snapshotCreatedAt(),
      state.snapshotCreatedAt(),
      ClaimType.HYPOTHESIS,
      value.statement(),
      Provenance.append(value.reasoning(), fields),
      scopeScore.score(),
      sourceRefs
    );
  }

  private static Statement alternative(ProtocolModel.Alternative value) {
    var fields = Provenance.fields();
    fields.put("factRefs", Provenance.ids(value.factRefs()));
    fields.put("rivalsHypothesisRef", Provenance.id(value.rivalsHypothesisRef()));
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static Statement counterArgument(ProtocolModel.CounterArgument value) {
    var fields = Provenance.fields();
    fields.put("factRefs", Provenance.ids(value.factRefs()));
    fields.put("targetHypothesisRef", Provenance.id(value.targetHypothesisRef()));
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static Statement condition(ProtocolModel.FalsificationCondition value) {
    var fields = Provenance.fields();
    fields.put("hypothesisRef", Provenance.id(value.hypothesisRef()));
    fields.put("observable", value.observable());
    fields.put("comparison", value.comparison());
    fields.put("decisionBoundary", value.decisionBoundary());
    return new Statement(
      ClaimType.INFERENCE,
      bounded(value.statement(), value.observable(), 20000),
      Provenance.append(
        "Pre-registered falsification condition.",
        fields
      ),
      0,
      List.of()
    );
  }

  private static Statement signal(ProtocolModel.Signal value) {
    var fields = Provenance.fields();
    fields.put("hypothesisRef", Provenance.id(value.hypothesisRef()));
    fields.put("where", value.where());
    fields.put("window", value.window());
    fields.put("status", value.status().name());
    return new Statement(
      ClaimType.INFERENCE,
      value.signal(),
      Provenance.append("Expected observable, not yet observed.", fields),
      0,
      List.of()
    );
  }

  private static Indicator planItem(ProtocolModel.PlanItem value) {
    var fields = Provenance.fields();
    fields.put("hypothesisRef", Provenance.id(value.hypothesisRef()));
    fields.put("predictionRef", Provenance.id(value.predictionRef()));
    fields.put("deadline", value.deadline() == null ? "" : value.deadline().toString());
    fields.put("priority", value.priority().name());
    return new Indicator(
      value.id(),
      null,
      value.whatToCheck(),
      value.whereToCheck(),
      value.deadline() == null
        ? value.priority().name()
        : value.priority().name() + " " + value.deadline(),
      ClaimType.INFERENCE,
      "Supporting: " +
      value.supportingResult() +
      " | Contradicting: " +
      value.contradictingResult(),
      Provenance.append(value.reasoning(), fields),
      value.confidence(),
      value.sourceRefs()
    );
  }

  private static Statement unknown(ProtocolModel.Unknown value) {
    var fields = Provenance.fields();
    fields.put("unknownReason", value.unknownReason());
    fields.put("wouldResolveWith", value.wouldResolveWith());
    return new Statement(
      ClaimType.INFERENCE,
      value.statement(),
      Provenance.append("UNKNOWN.", fields),
      0,
      value.sourceRefs()
    );
  }

  // ---- helpers -----------------------------------------------------------

  /**
   * PR-06/PR-16: the snapshot records the protocol version, the rubric version,
   * the domain strategy id and the prompt versions that produced it. The v0.1
   * contract has no provenance block (SCH-12 pending), so they are rendered into
   * the confidence reason, which Gate D already depends on.
   */
  private static String confidenceReason(
    ConfidenceScore score,
    PipelineState state,
    String domainStrategyId
  ) {
    var fields = Provenance.fields();
    fields.put("protocolVersion", "0.1");
    fields.put("rubricVersion", score.rubricVersion());
    fields.put("method", score.method().name());
    fields.put("domainStrategy", domainStrategyId == null ? "" : domainStrategyId);
    fields.put(
      "promptVersions",
      String.join(",", new LinkedHashSet<>(state.promptVersions()))
    );
    fields.put("isProbability", "false");
    return Provenance.append(score.reason(), fields);
  }

  private static String bounded(String value, String fallback, int max) {
    String text = value == null || value.isBlank() ? fallback : value;
    return text.length() <= max ? text : text.substring(0, max);
  }
}
