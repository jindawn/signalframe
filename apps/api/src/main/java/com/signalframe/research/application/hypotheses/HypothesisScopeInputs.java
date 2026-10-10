package com.signalframe.research.application.hypotheses;

import com.signalframe.contract.Fact;
import com.signalframe.contract.Hypothesis;
import com.signalframe.research.domain.hypotheses.EvidenceStance;
import com.signalframe.research.domain.hypotheses.HypothesisSnapshot;
import com.signalframe.research.domain.hypotheses.StoredEvidence;
import com.signalframe.shared.confidence.ConfidenceInputs;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the deterministic hypothesis-scope {@link ConfidenceInputs} from stored
 * artifacts (CONFIDENCE_MODEL §9 and §8's hypothesis-scope row).
 *
 * <p>The projection is the whole reason evidence can move a hypothesis's
 * confidence <em>without</em> anyone choosing a delta: adding evidence changes the
 * inputs (D3 counts independent sources, D5 counts standing contradictions, D2
 * reads the facts the hypothesis rests on) and the rubric then recomputes the
 * score. There is no arithmetic on a previous score anywhere in Wave 2B
 * (CONFIDENCE_MODEL §10 Phase 2, freeze §4.1 invariant 2).
 *
 * <p>Everything read here is a generated contract record or a relational column.
 * Nothing comes from a model, a clock, the network or the environment, so a
 * recomputation over the same stored state is byte-identical (CF-02/CF-04) and
 * Gate D can reproduce a stored score.
 *
 * <h2>What evidence changes</h2>
 * <ul>
 *   <li><b>Independence.</b> An evidence row whose {@code source_id} differs from
 *       the snapshot's own source is independent corroboration (D3 levels 2-5);
 *       one pointing at the same source is a non-independent addition (D3 level 1).
 *       This is what can release CAP-A, which otherwise pins a single-source
 *       snapshot at {@code LOW}.</li>
 *   <li><b>Confirmation.</b> An independent source that carries a
 *       {@code SUPPORTS} evidence item is an independent <em>confirming</em>
 *       source (D3 levels 3-5, CAP-F).</li>
 *   <li><b>Context.</b> An independent source that does not confirm the core fact
 *       is contextual material (D1 level 4), never independent corroboration.
 *       D1 and D3 therefore stay independent by construction.</li>
 *   <li><b>Contradiction.</b> Every {@code CONTRADICTS} item is counted, which is
 *       the D5 input the snapshot scope cannot see.</li>
 * </ul>
 *
 * <h2>What is deliberately not derived</h2>
 * {@code independentMethodCount} is zero. D3 level 5 asks for two independent
 * sources <em>using different methods</em>, and no stored column records a method;
 * counting sources instead would silently promote source count into method
 * diversity. Zero keeps level 5 unreachable and the honest ceiling at level 4
 * (two independent confirming sources), which is a true statement about the data
 * we hold rather than a guess.
 */
final class HypothesisScopeInputs {

  private HypothesisScopeInputs() {}

  static ConfidenceInputs of(
    Hypothesis hypothesis,
    HypothesisSnapshot snapshot,
    List<StoredEvidence> evidence
  ) {
    var result = snapshot.result();
    var supporting = new HashSet<>(orEmpty(hypothesis.supportingFactRefs()));
    var facts = orEmpty(result.facts())
      .stream()
      .filter(fact -> supporting.contains(fact.id()))
      .toList();
    var variables = orEmpty(result.variables())
      .stream()
      .filter(variable -> intersects(orEmpty(variable.factRefs()), supporting))
      .toList();
    var mechanisms = orEmpty(result.mechanisms())
      .stream()
      .filter(mechanism -> intersects(orEmpty(mechanism.factRefs()), supporting))
      .toList();
    var counters = orEmpty(result.counterArguments())
      .stream()
      .filter(counter -> hypothesis.id().equals(counter.targetHypothesisRef()))
      .toList();
    var signals = orEmpty(result.corroboratingSignals())
      .stream()
      .filter(signal -> hypothesis.id().equals(signal.hypothesisRef()))
      .toList();
    var plan = orEmpty(result.verificationIndicators())
      .stream()
      .filter(item -> hypothesis.id().equals(item.hypothesisRef()))
      .toList();

    var counts = counts(evidence, snapshot.sourceId());

    return new ConfidenceInputs(
      result.sourceAssessment(),
      counts.independentSourceCount(),
      counts.independentConfirmingSourceCount(),
      counts.independentMethodCount(),
      counts.nonIndependentAdditionCount(),
      counts.contextualMaterialCount(),
      facts,
      variables,
      mechanisms,
      counters,
      orEmpty(hypothesis.falsificationConditions()),
      signals,
      plan,
      orEmpty(hypothesis.assumptions()),
      statesAQuantity(facts),
      // The hypothesis itself is the claim the facts are supposed to carry; it is
      // never "a restatement of the facts only" (D2 level 1) by construction.
      false,
      restsOnDisputedFact(facts),
      counts.contradictingEvidenceCount()
    );
  }

  /** The D1/D3/D5 counts evidence contributes. */
  private static Counts counts(List<StoredEvidence> evidence, UUID snapshotSource) {
    var independent = new LinkedHashSet<UUID>();
    var nonIndependent = new LinkedHashSet<UUID>();
    var confirming = new LinkedHashSet<UUID>();
    var supportingSources = new HashSet<UUID>();
    int contradictions = 0;
    for (var item : evidence) {
      if (item.stance() == EvidenceStance.CONTRADICTS) contradictions++;
      if (item.sourceId() == null) continue;
      if (item.stance() == EvidenceStance.SUPPORTS) supportingSources.add(
        item.sourceId()
      );
      if (item.sourceId().equals(snapshotSource)) nonIndependent.add(item.sourceId());
      else independent.add(item.sourceId());
    }
    int contextual = 0;
    for (var source : independent) {
      if (supportingSources.contains(source)) confirming.add(source);
      else contextual++;
    }
    return new Counts(
      independent.size(),
      confirming.size(),
      0,
      nonIndependent.size(),
      contextual,
      contradictions
    );
  }

  private static boolean intersects(List<UUID> refs, Set<UUID> ids) {
    for (var ref : refs) if (ids.contains(ref)) return true;
    return false;
  }

  /**
   * D2 level 5: "a fact states the core quantity or relationship directly". A
   * referenced fact carrying a digit is the deterministic reading available; no
   * unit conversion and no invented number is involved.
   */
  private static boolean statesAQuantity(List<Fact> facts) {
    return facts.stream().anyMatch(fact -> fact.statement().matches(".*\\d.*"));
  }

  /** CAP-B: a core fact the hypothesis rests on is disputed. */
  private static boolean restsOnDisputedFact(List<Fact> facts) {
    return facts.stream().anyMatch(fact ->
      "DISPUTED".equals(fact.verificationStatus())
    );
  }

  private static <T> List<T> orEmpty(List<T> values) {
    return values == null ? List.of() : values;
  }

  /** Kept as a named record so the constructor call above stays readable. */
  private record Counts(
    int independentSourceCount,
    int independentConfirmingSourceCount,
    int independentMethodCount,
    int nonIndependentAdditionCount,
    int contextualMaterialCount,
    int contradictingEvidenceCount
  ) {}
}
