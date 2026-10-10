package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import java.util.*;

/**
 * Builds the deterministic {@link ConfidenceInputs} projection (CF-04).
 *
 * <p>Everything here is read from the assembled snapshot only. Nothing is read
 * from a model, a clock, the network, a random source or the environment, and the
 * counts are exact set/count operations so a recomputation is byte-identical
 * (CF-02/CF-03).
 */
final class ConfidenceProjection {

  private ConfidenceProjection() {}

  static ConfidenceInputs snapshotScope(PipelineState state) {
    var source = state.sourceAssessment();
    int independent = independentSources(source);
    var main = state.hypotheses().isEmpty()
      ? null
      : state.hypotheses().getFirst();
    return new ConfidenceInputs(
      source,
      independent,
      independentConfirming(source),
      independent,
      nonIndependentAdditions(source),
      0,
      state.facts(),
      state.variables(),
      state.mechanisms(),
      state.counterArguments(),
      state.falsificationConditions(),
      state.corroboratingSignals(),
      state.verificationPlan(),
      main == null ? List.of() : main.assumptions(),
      coreQuantityStated(state, main == null ? List.of() : main.supportingFactRefs()),
      factsRestateSummaryOnly(state),
      main != null && restsOnDisputedFact(state, main),
      main == null ? 0 : main.contradictingEvidenceRefs().size()
    );
  }

  static ConfidenceInputs hypothesisScope(PipelineState state, Hypothesis hypothesis) {
    var source = state.sourceAssessment();
    int independent = independentSources(source);
    var supportingIds = new HashSet<>(hypothesis.supportingFactRefs());
    var facts = state
      .facts()
      .stream()
      .filter(f -> supportingIds.contains(f.id()))
      .toList();
    var variables = state
      .variables()
      .stream()
      .filter(v -> intersects(v.factRefs(), supportingIds))
      .toList();
    var mechanisms = state
      .mechanisms()
      .stream()
      .filter(m -> intersects(m.factRefs(), supportingIds))
      .toList();
    var counters = state
      .counterArguments()
      .stream()
      .filter(c -> hypothesis.id().equals(c.targetHypothesisRef()))
      .toList();
    var conditions = state
      .falsificationConditions()
      .stream()
      .filter(c -> hypothesis.id().equals(c.hypothesisRef()))
      .toList();
    var signals = state
      .corroboratingSignals()
      .stream()
      .filter(s -> hypothesis.id().equals(s.hypothesisRef()))
      .toList();
    var plan = state
      .verificationPlan()
      .stream()
      .filter(p -> hypothesis.id().equals(p.hypothesisRef()))
      .toList();
    return new ConfidenceInputs(
      source,
      independent,
      independentConfirming(source),
      independent,
      nonIndependentAdditions(source),
      0,
      facts,
      variables,
      mechanisms,
      counters,
      conditions,
      signals,
      plan,
      hypothesis.assumptions(),
      coreQuantityStated(state, hypothesis.supportingFactRefs()),
      false,
      restsOnDisputedFact(state, hypothesis),
      hypothesis.contradictingEvidenceRefs().size()
    );
  }

  private static boolean intersects(List<UUID> refs, Set<UUID> ids) {
    return refs.stream().anyMatch(ids::contains);
  }

  private static boolean restsOnDisputedFact(PipelineState state, Hypothesis h) {
    var ids = new HashSet<>(h.supportingFactRefs());
    return state
      .facts()
      .stream()
      .anyMatch(f ->
        ids.contains(f.id()) && f.verificationStatus() == ProofStatus.DISPUTED
      );
  }

  /**
   * D2 level 5: "a fact states the core quantity or relationship directly". A
   * referenced fact carrying a digit is the deterministic reading available to
   * v0.1; no unit conversion or invented number is involved.
   */
  private static boolean coreQuantityStated(
    PipelineState state,
    List<UUID> refs
  ) {
    var ids = new HashSet<>(refs);
    return state
      .facts()
      .stream()
      .anyMatch(f -> ids.contains(f.id()) && f.statement().matches(".*\\d.*"));
  }

  /**
   * D2 level 1: the snapshot states no claim beyond the facts themselves — no
   * directional variable, no mechanism and no hypothesis.
   */
  private static boolean factsRestateSummaryOnly(PipelineState state) {
    return (
      state.hypotheses().isEmpty() &&
      state.mechanisms().isEmpty() &&
      state.variables().stream().noneMatch(v -> v.direction() != Direction.UNKNOWN)
    );
  }

  /** MVP fact: one snapshot holds one source, so no independent source exists. */
  private static int independentSources(SourceAssessment source) {
    if (source == null) return 0;
    return source.independence() == Independence.INDEPENDENT_SET ? 1 : 0;
  }

  /**
   * Independent <em>confirmation of the core fact</em> cannot be proven from the
   * v0.1 snapshot: it holds one source and carries no substance-agreement record.
   * Reporting zero is the honest answer and keeps D3 at level 2 or below, so
   * CAP-A holds the ceiling at LOW for the single-source MVP (CF model §5/§6).
   */
  private static int independentConfirming(SourceAssessment source) {
    return 0;
  }

  private static int nonIndependentAdditions(SourceAssessment source) {
    if (source == null) return 0;
    return source.independence() == Independence.SAME_PUBLISHER_DUPLICATE ? 1 : 0;
  }
}
