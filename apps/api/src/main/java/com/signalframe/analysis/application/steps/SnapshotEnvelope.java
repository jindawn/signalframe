package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.SourceRef;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.FalsificationConditionText;
import java.util.*;

/**
 * Reference-fixture compatibility: reads a response that is a full
 * {@code AnalysisResult} snapshot instead of a stage envelope.
 *
 * <p>The offline adapter is the project's reference fixture and answers every
 * purpose with one complete demo snapshot; a provider that ignores the narrowed
 * stage schema does the same. Accepting that shape keeps TASK-04's gateway
 * contract and the offline end-to-end path intact without changing the adapter.
 *
 * <p>The v0.1 contract cannot express several protocol fields (fact refs, support
 * levels, nested falsification conditions, predictions). This class therefore
 * <em>derives</em> what is derivable and refuses to promote anything:
 *
 * <ul>
 *   <li>fact refs are resolved deterministically by source span overlap with the
 *       snapshot's facts — never invented;
 *   <li>a mechanism with no declared support level stays {@code SPECULATIVE} (the
 *       lowest level); nothing is upgraded by arrival;
 *   <li>a falsification condition or plan item whose comparison/decision boundary
 *       the snapshot does not carry keeps the field {@code UNKNOWN} and stays
 *       non-observable, so the rubric caps the score rather than pretending the
 *       condition was pre-registered;
 *   <li>an item whose refs cannot be resolved inside the snapshot is dropped by
 *       the caller and reported as UNKNOWN (EP-01), not silently kept.
 * </ul>
 */
final class SnapshotEnvelope {

  static final String NOT_DECLARED =
    "UNKNOWN (not declared by the v0.1 snapshot)";

  private final AnalysisResult result;

  private SnapshotEnvelope(AnalysisResult result) {
    this.result = result;
  }

  static Optional<SnapshotEnvelope> tryParse(String raw, JsonCodec json) {
    try {
      return Optional.of(new SnapshotEnvelope(json.read(raw, AnalysisResult.class)));
    } catch (RuntimeException notASnapshot) {
      return Optional.empty();
    }
  }

  AnalysisResult result() {
    return result;
  }

  boolean demo() {
    return Boolean.TRUE.equals(result.demo());
  }

  String summary() {
    return result.summary();
  }

  Integer advisoryConfidence() {
    return result.confidenceAssessment() == null
      ? null
      : result.confidenceAssessment().score();
  }

  List<Unknown> unknowns() {
    var list = new ArrayList<Unknown>();
    for (var s : ProtocolModel.orEmpty(result.unknowns())) {
      list.add(
        new Unknown(
          UUID.randomUUID(),
          s.statement(),
          s.reasoning(),
          NOT_DECLARED,
          s.sourceRefs()
        )
      );
    }
    return List.copyOf(list);
  }

  List<Fact> facts() {
    var list = new ArrayList<Fact>();
    for (var f : ProtocolModel.orEmpty(result.facts())) {
      list.add(
        new Fact(
          f.id(),
          f.statement(),
          blank(f.reasoning(), "Reported source claim."),
          f.sourceRefs(),
          proofStatus(f.verificationStatus())
        )
      );
    }
    return List.copyOf(list);
  }

  List<Variable> variables(List<Fact> facts) {
    var list = new ArrayList<Variable>();
    for (var v : ProtocolModel.orEmpty(result.variables())) {
      list.add(
        new Variable(
          v.id(),
          v.name(),
          blank(v.previousState(), UNKNOWN_STATE),
          blank(v.currentState(), UNKNOWN_STATE),
          parseDirection(v.direction()),
          blank(v.magnitude(), UNKNOWN_STATE),
          blank(v.whyItMatters(), UNKNOWN_STATE),
          v.statement(),
          v.reasoning(),
          declaredFactRefs(v.factRefs(), facts),
          v.sourceRefs(),
          v.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  List<Mechanism> mechanisms(List<Fact> facts) {
    var list = new ArrayList<Mechanism>();
    for (var m : ProtocolModel.orEmpty(result.mechanisms())) {
      list.add(
        new Mechanism(
          m.id(),
          m.cause(),
          m.effect(),
          supportLevel(m.supportLevel()),
          blank(m.statement(), "UNKNOWN"),
          m.reasoning(),
          List.of(),
          declaredOrSpanFactRefs(m.factRefs(), m.sourceRefs(), facts),
          m.sourceRefs(),
          "UNKNOWN",
          m.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  List<Stakeholder> stakeholders(List<Fact> facts) {
    var list = new ArrayList<Stakeholder>();
    for (var s : ProtocolModel.orEmpty(result.stakeholders())) {
      list.add(
        new Stakeholder(
          s.id(),
          s.stakeholder(),
          parseStakeholderDirection(s.direction()),
          s.statement(),
          s.reasoning(),
          factRefsFor(s.sourceRefs(), facts),
          s.sourceRefs(),
          s.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  List<Effect> firstOrderEffects(List<Fact> facts) {
    return effects(ProtocolModel.orEmpty(result.firstOrderEffects()), facts, List.of());
  }

  List<Effect> secondOrderEffects(List<Fact> facts) {
    return effects(ProtocolModel.orEmpty(result.secondOrderEffects()), facts, List.of());
  }

  private static List<Effect> effects(
    List<com.signalframe.contract.Statement> statements,
    List<Fact> facts,
    List<UUID> derivedFrom
  ) {
    var list = new ArrayList<Effect>();
    for (var s : statements) {
      list.add(
        new Effect(
          UUID.randomUUID(),
          s.statement(),
          s.reasoning(),
          factRefsFor(s.sourceRefs(), facts),
          derivedFrom,
          s.sourceRefs(),
          s.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  List<Hypothesis> hypotheses(List<Fact> facts) {
    var list = new ArrayList<Hypothesis>();
    for (var h : ProtocolModel.orEmpty(result.hypotheses())) {
      var contradicting = List.<UUID>of();
      var assumptions = new ArrayList<String>();
      assumptions.add(
        blank(
          h.confidenceReason(),
          "No independent contradicting evidence was available in this snapshot."
        )
      );
      list.add(
        new Hypothesis(
          h.id(),
          h.title(),
          h.statement(),
          h.reasoning(),
          factRefsFor(h.sourceRefs(), facts),
          List.of(),
          contradicting,
          assumptions,
          List.of(),
          List.of(),
          h.confidenceReason(),
          h.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  /**
   * A v0.1 snapshot does not declare which hypothesis an alternative rivals. With
   * exactly one hypothesis the pairing is unambiguous; with several it is derived
   * positionally, and {@link StageResponses} records an UNKNOWN note when the
   * counts disagree so the ambiguity is visible rather than hidden.
   */
  Optional<UUID> rivalFor(int index, List<Hypothesis> hypotheses) {
    if (hypotheses.isEmpty()) return Optional.empty();
    if (hypotheses.size() == 1) return Optional.of(hypotheses.getFirst().id());
    return Optional.of(hypotheses.get(Math.min(index, hypotheses.size() - 1)).id());
  }

  boolean rivalPairingAmbiguous() {
    int alternatives =
      ProtocolModel.orEmpty(result.alternativeExplanations()).size() +
      ProtocolModel.orEmpty(result.counterArguments()).size();
    return !ProtocolModel.orEmpty(result.hypotheses()).isEmpty() &&
    ProtocolModel.orEmpty(result.hypotheses()).size() > 1 &&
    alternatives > 0;
  }

  Optional<UUID> soleHypothesis(List<Hypothesis> hypotheses) {
    return hypotheses.isEmpty()
      ? Optional.empty()
      : Optional.of(hypotheses.getFirst().id());
  }

  List<Alternative> alternatives(List<Fact> facts, List<Hypothesis> hypotheses) {
    var list = new ArrayList<Alternative>();
    var statements = ProtocolModel.orEmpty(result.alternativeExplanations());
    for (int i = 0; i < statements.size(); i++) {
      var s = statements.get(i);
      var rival = rivalFor(i, hypotheses);
      if (rival.isEmpty()) continue;
      list.add(
        new Alternative(
          UUID.randomUUID(),
          s.statement(),
          s.reasoning(),
          rival.get(),
          factRefsFor(s.sourceRefs(), facts),
          s.sourceRefs(),
          s.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  List<CounterArgument> counterArguments(
    List<Fact> facts,
    List<Hypothesis> hypotheses
  ) {
    var list = new ArrayList<CounterArgument>();
    var target = soleHypothesis(hypotheses);
    if (target.isEmpty()) return List.of();
    for (var s : ProtocolModel.orEmpty(result.counterArguments())) {
      list.add(
        new CounterArgument(
          UUID.randomUUID(),
          s.statement(),
          s.reasoning(),
          target.get(),
          factRefsFor(s.sourceRefs(), facts),
          s.sourceRefs(),
          s.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  List<FalsificationCondition> falsificationConditions(
    List<Hypothesis> hypotheses
  ) {
    var target = soleHypothesis(hypotheses);
    if (target.isEmpty()) return List.of();
    var list = new ArrayList<FalsificationCondition>();
    for (var s : ProtocolModel.orEmpty(result.falsificationConditions())) {
      list.add(
        new FalsificationCondition(
          UUID.randomUUID(),
          target.get(),
          blank(FalsificationConditionText.parts(s).get(FalsificationConditionText.OBSERVABLE), s.statement()),
          blank(FalsificationConditionText.parts(s).get(FalsificationConditionText.COMPARISON), NOT_DECLARED),
          blank(FalsificationConditionText.parts(s).get(FalsificationConditionText.DECISION_BOUNDARY), NOT_DECLARED),
          s.statement()
        )
      );
    }
    return List.copyOf(list);
  }

  List<Signal> signals(List<Hypothesis> hypotheses) {
    var target = soleHypothesis(hypotheses);
    if (target.isEmpty()) return List.of();
    var list = new ArrayList<Signal>();
    for (var s : ProtocolModel.orEmpty(result.corroboratingSignals())) {
      list.add(
        new Signal(
          UUID.randomUUID(),
          s.statement(),
          blank(s.whereToCheck(), NOT_DECLARED),
          target.get(),
          blank(s.window(), NOT_DECLARED),
          SignalStatus.NOT_OBSERVED
        )
      );
    }
    return List.copyOf(list);
  }

  /**
   * The snapshot's own predictions, mapped into the stage vocabulary.
   *
   * <p>The contract carries them (SCH-07); the earlier behaviour of returning an
   * empty list with an "SCH-07 pending" note was stale and silently discarded a
   * protocol artifact the model had produced.
   */
  List<Prediction> predictions() {
    var list = new ArrayList<Prediction>();
    for (var p : ProtocolModel.orEmpty(result.predictions())) {
      list.add(
        new Prediction(
          p.id(),
          p.hypothesisId(),
          p.statement(),
          p.observable(),
          p.expectedBy(),
          p.verificationCriteria(),
          p.whereToCheck(),
          predictionStatus(p.status()),
          ProtocolModel.orEmpty(p.basisFactRefs())
        )
      );
    }
    return List.copyOf(list);
  }

  /** The declared prediction status, or OPEN when it is absent or unrecognized. */
  static PredictionStatus predictionStatus(String declared) {
    if (declared == null || declared.isBlank()) return PredictionStatus.OPEN;
    try {
      return PredictionStatus.valueOf(declared.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unrecognized) {
      return PredictionStatus.OPEN;
    }
  }

  List<PlanItem> plan(
    List<Fact> facts,
    List<Hypothesis> hypotheses,
    List<Prediction> predictions
  ) {
    var target = soleHypothesis(hypotheses);
    var list = new ArrayList<PlanItem>();
    for (var i : ProtocolModel.orEmpty(result.verificationIndicators())) {
      list.add(
        new PlanItem(
          i.id(),
          blank(i.name(), blank(i.statement(), "UNKNOWN")),
          blank(i.whereToCheck(), blank(i.measurement(), NOT_DECLARED)),
          blank(i.supportingResult(), NOT_DECLARED),
          blank(i.contradictingResult(), NOT_DECLARED),
          planPriority(i.priority()),
          i.deadline(),
          hypothesisFor(i, predictions).orElse(target.orElse(null)),
          i.predictionId(),
          i.statement(),
          i.reasoning(),
          i.sourceRefs(),
          i.confidence()
        )
      );
    }
    return List.copyOf(list);
  }

  // ---- deterministic helpers --------------------------------------------

  /**
   * The declared fact ids, filtered to the facts this snapshot actually holds.
   *
   * <p>A declared reference is the model's own statement of its basis, so it is
   * preferred over the span-derived one. It is filtered rather than trusted: a ref
   * to a fact that is not in this snapshot can neither be invented nor resolved, so
   * it is dropped (the reader rejects dangling refs) and the span derivation is used
   * as the fallback. Nothing is promoted and nothing is fabricated.
   */
  static List<UUID> declaredFactRefs(List<UUID> declared, List<Fact> facts) {
    if (declared == null || declared.isEmpty()) return List.of();
    var known = new LinkedHashSet<UUID>();
    for (var fact : facts) known.add(fact.id());
    var resolved = new ArrayList<UUID>();
    for (var id : declared) if (id != null && known.contains(id)) resolved.add(id);
    return List.copyOf(resolved);
  }

  /** A declared fact basis, or the span-derived one when nothing resolves. */
  static List<UUID> declaredOrSpanFactRefs(
    List<UUID> declared,
    List<SourceRef> refs,
    List<Fact> facts
  ) {
    var resolved = declaredFactRefs(declared, facts);
    return resolved.isEmpty() ? factRefsFor(refs, facts) : resolved;
  }

  /** The declared proof status, or REPORTED when it is absent or unrecognized. */
  static ProofStatus proofStatus(String declared) {
    if (declared == null || declared.isBlank()) return ProofStatus.REPORTED;
    try {
      return ProofStatus.valueOf(declared.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unrecognized) {
      return ProofStatus.REPORTED;
    }
  }

  /** The declared support level, or SPECULATIVE when it is absent or unrecognized. */
  static SupportLevel supportLevel(String declared) {
    if (declared == null || declared.isBlank()) return SupportLevel.SPECULATIVE;
    try {
      return SupportLevel.valueOf(declared.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unrecognized) {
      return SupportLevel.SPECULATIVE;
    }
  }

  /** The declared plan priority, or UNKNOWN when it is absent or unrecognized. */
  static PlanPriority planPriority(String declared) {
    if (declared == null || declared.isBlank()) return PlanPriority.UNKNOWN;
    try {
      return PlanPriority.valueOf(declared.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unrecognized) {
      return PlanPriority.UNKNOWN;
    }
  }

  /**
   * The hypothesis a plan item serves, taken from the prediction it is attached to.
   *
   * <p>The generated {@code Indicator} carries {@code predictionId} but no
   * {@code hypothesisRef} (see the contract gap in the validation report), so the
   * link is followed through the prediction rather than guessed.
   */
  static Optional<UUID> hypothesisFor(
    com.signalframe.contract.Indicator indicator,
    List<Prediction> predictions
  ) {
    if (indicator.predictionId() == null) return Optional.empty();
    return predictions
      .stream()
      .filter(p -> indicator.predictionId().equals(p.hypothesisRef()))
      .map(Prediction::hypothesisRef)
      .findFirst();
  }

  private static final String UNKNOWN_STATE = "UNKNOWN";

  /**
   * Resolves the snapshot's fact ids for a span list by exact-span overlap. This
   * is provenance resolution, not new evidence: only facts already inside the
   * snapshot can be returned, and an unresolvable span yields no ref.
   */
  static List<UUID> factRefsFor(List<SourceRef> refs, List<Fact> facts) {
    var ids = new LinkedHashSet<UUID>();
    for (var ref : refs) {
      for (var fact : facts) {
        for (var factRef : fact.sourceRefs()) {
          if (
            factRef.sourceId().equals(ref.sourceId()) &&
            overlaps(
              factRef.startOffset(),
              factRef.endOffset(),
              ref.startOffset(),
              ref.endOffset()
            )
          ) ids.add(fact.id());
        }
      }
    }
    return List.copyOf(ids);
  }

  private static boolean overlaps(
    int aStart,
    int aEnd,
    int bStart,
    int bEnd
  ) {
    return aStart < bEnd && bStart < aEnd;
  }

  private static String blank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Direction parseDirection(String raw) {
    try {
      return Direction.valueOf(raw);
    } catch (RuntimeException invalid) {
      return Direction.UNKNOWN;
    }
  }

  private static StakeholderDirection parseStakeholderDirection(String raw) {
    try {
      return StakeholderDirection.valueOf(raw);
    } catch (RuntimeException invalid) {
      return StakeholderDirection.UNKNOWN;
    }
  }
}
