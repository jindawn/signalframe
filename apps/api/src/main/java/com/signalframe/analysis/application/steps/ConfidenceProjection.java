package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.SourceAssessment;
import com.signalframe.shared.confidence.ConfidenceInputs;
import java.util.*;

/**
 * Builds the deterministic {@link ConfidenceInputs} projection (CF-04).
 *
 * <p>Everything here is read from the assembled snapshot only. Nothing is read
 * from a model, a clock, the network, a random source or the environment, and the
 * counts are exact set/count operations so a recomputation is byte-identical
 * (CF-02/CF-03).
 *
 * <p>The projection is expressed in generated contract records (SCH-01…SCH-13)
 * because {@link ConfidenceInputs} lives in the neutral {@code shared.confidence}
 * package, which must not depend on {@code analysis}. Snapshot scope reads the
 * whole artifact set; hypothesis scope reads only the artifacts the hypothesis
 * references, so {@code Hypothesis.confidence} and the snapshot score remain
 * separate judgments over separate objects and are never averaged (§8).
 */
final class ConfidenceProjection {

  private ConfidenceProjection() {}

  /** Snapshot scope (§8): the whole assembled artifact set. */
  static ConfidenceInputs snapshotScope(PipelineState state) {
    return snapshotScope(state, SnapshotProjection.artifacts(state));
  }

  /** Snapshot scope over an already-projected artifact set. */
  static ConfidenceInputs snapshotScope(
    PipelineState state,
    SnapshotProjection.Artifacts artifacts
  ) {
    var source = SnapshotProjection.sourceAssessment(state);
    var main = state.hypotheses().isEmpty()
      ? null
      : state.hypotheses().getFirst();
    return new ConfidenceInputs(
      source,
      independentSources(source),
      independentConfirming(source),
      independentSources(source),
      nonIndependentAdditions(source),
      0,
      artifacts.facts(),
      artifacts.variables(),
      artifacts.mechanisms(),
      artifacts.counterArguments(),
      artifacts.falsificationConditions(),
      artifacts.signals(),
      artifacts.plan(),
      main == null ? List.of() : main.assumptions(),
      coreQuantityStated(state, main == null ? List.of() : main.supportingFactRefs()),
      factsRestateSummaryOnly(state),
      main != null && restsOnDisputedFact(state, main),
      main == null ? 0 : main.contradictingEvidenceRefs().size()
    );
  }

  /**
   * Hypothesis scope (§8): only the artifacts this hypothesis references. The
   * result is the hypothesis's own rubric judgment, never a share of the
   * snapshot's.
   */
  static ConfidenceInputs hypothesisScope(
    PipelineState state,
    Hypothesis hypothesis,
    SnapshotProjection.Artifacts artifacts
  ) {
    var source = SnapshotProjection.sourceAssessment(state);
    var supportingIds = new HashSet<>(hypothesis.supportingFactRefs());
    var facts = artifacts
      .facts()
      .stream()
      .filter(f -> supportingIds.contains(f.id()))
      .toList();
    var variables = artifacts
      .variables()
      .stream()
      .filter(v -> intersects(v.factRefs(), supportingIds))
      .toList();
    var mechanisms = artifacts
      .mechanisms()
      .stream()
      .filter(m -> intersects(m.factRefs(), supportingIds))
      .toList();
    var counters = state
      .counterArguments()
      .stream()
      .filter(c -> hypothesis.id().equals(c.targetHypothesisRef()))
      .map(SnapshotProjection::counterArgument)
      .toList();
    var conditions = state
      .falsificationConditions()
      .stream()
      .filter(c -> hypothesis.id().equals(c.hypothesisRef()))
      .map(SnapshotProjection::condition)
      .toList();
    var signals = state
      .corroboratingSignals()
      .stream()
      .filter(s -> hypothesis.id().equals(s.hypothesisRef()))
      .map(SnapshotProjection::signal)
      .toList();
    var plan = state
      .verificationPlan()
      .stream()
      .filter(p -> hypothesis.id().equals(p.hypothesisRef()))
      .map(SnapshotProjection::planItem)
      .toList();
    return new ConfidenceInputs(
      source,
      independentSources(source),
      independentConfirming(source),
      independentSources(source),
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

  /**
   * D1 over the source assessment alone, which is all a fact's source-report
   * reliability depends on (CONFIDENCE_MODEL §8). Fact confidence is a rendering of
   * D1, so resolving it must not require the fact list it is stamped on.
   */
  static int sourceQualityLevel(PipelineState state) {
    var source = SnapshotProjection.sourceAssessment(state);
    int independent = independentSources(source);
    return new ConfidenceInputs(
      source,
      independent,
      independentConfirming(source),
      independent,
      nonIndependentAdditions(source),
      0,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      false,
      false,
      false,
      0
    ).levelD1();
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
   * referenced fact carrying a digit is the deterministic reading available today;
   * no unit conversion or invented number is involved.
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
    return "INDEPENDENT_SET".equals(source.independence()) ? 1 : 0;
  }

  /**
   * Independent <em>confirmation of the core fact</em> cannot be proven from the
   * current snapshot: it holds one source and carries no substance-agreement
   * record. Reporting zero is the honest answer and keeps D3 at level 2 or below,
   * so CAP-A holds the ceiling at LOW for the single-source MVP (§5/§6).
   */
  private static int independentConfirming(SourceAssessment source) {
    return 0;
  }

  private static int nonIndependentAdditions(SourceAssessment source) {
    if (source == null) return 0;
    return "SAME_PUBLISHER_DUPLICATE".equals(source.independence()) ? 1 : 0;
  }
}
