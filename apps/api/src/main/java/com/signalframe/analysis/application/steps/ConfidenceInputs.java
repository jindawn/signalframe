package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import java.util.List;

/**
 * The derived projection the rubric scores (CONFIDENCE_MODEL_V0_1 §9).
 *
 * <p>Every component feeds a level rule: nothing here is read from a model, a
 * clock, the network or the environment (CF-04). The §9 record is reproduced in
 * full, plus the derived evidence-shape facts that §9 explicitly delegates to
 * "the same deterministic pass that builds the levels" — a documented
 * assumption, a contextual addition counted separately from independent
 * corroboration, a directly stated core quantity, a restatement-only fact set,
 * the main hypothesis resting on a disputed fact and reconcileable contradicting
 * evidence. Without those, several levels of §5 would not be derivable from the
 * snapshot, which is what CF-06 fail-closed exists to prevent.
 *
 * <p>D1 and D3 stay independent by construction: {@code contextualMaterialCount}
 * feeds D1 ("level 3 plus an additional secondary source adding context") and
 * never D3, so a snapshot can legitimately be D1 = 4 while D3 = 0 (the note in
 * §5 and worked Example 5).
 */
public record ConfidenceInputs(
  SourceAssessment source,
  int independentSourceCount,
  int independentConfirmingSourceCount,
  int independentMethodCount,
  int nonIndependentAdditionCount,
  int contextualMaterialCount,
  List<Fact> facts,
  List<Variable> variables,
  List<Mechanism> mechanisms,
  List<CounterArgument> counterArguments,
  List<FalsificationCondition> falsificationConditions,
  List<Signal> signals,
  List<PlanItem> plan,
  List<String> documentedAssumptions,
  boolean coreQuantityStatedInFact,
  boolean factsRestateSummaryOnly,
  boolean mainHypothesisRestsOnDisputedFact,
  int contradictingEvidenceCount
) {

  public ConfidenceInputs {
    facts = facts == null ? List.of() : List.copyOf(facts);
    variables = variables == null ? List.of() : List.copyOf(variables);
    mechanisms = mechanisms == null ? List.of() : List.copyOf(mechanisms);
    counterArguments = counterArguments == null
      ? List.of()
      : List.copyOf(counterArguments);
    falsificationConditions = falsificationConditions == null
      ? List.of()
      : List.copyOf(falsificationConditions);
    signals = signals == null ? List.of() : List.copyOf(signals);
    plan = plan == null ? List.of() : List.copyOf(plan);
    documentedAssumptions = documentedAssumptions == null
      ? List.of()
      : List.copyOf(documentedAssumptions);
  }

  // ---- level functions (CONFIDENCE_MODEL_V0_1 §5) ------------------------

  /** D1 Source Quality (20). */
  public int levelD1() {
    if (source == null) return 0;
    boolean publisherKnown =
      source.publisher() != null &&
      !source.publisher().isBlank() &&
      !"UNKNOWN".equals(source.publisher());
    boolean dated =
      source.publishedAt() != null &&
      !source.publishedAt().isBlank() &&
      !"UNKNOWN".equals(source.publishedAt());
    boolean complete = source.contentCompleteness() == Completeness.COMPLETE;
    boolean usable =
      source.contentCompleteness() == Completeness.COMPLETE ||
      source.contentCompleteness() == Completeness.PARTIAL;
    if (!publisherKnown || !usable) return 0;
    if (!dated || !complete) return 1;
    if (source.primaryOrSecondary() != SourceClass.PRIMARY) return 2;
    if (contextualMaterialCount < 1) return 3;
    // Deterministic proxy for "two or more independent primary-grade sources
    // using different methods": different methods, not merely two sources.
    if (independentMethodCount >= 2) return 5;
    return 4;
  }

  /** D2 Evidence Directness (25). */
  public int levelD2() {
    boolean anyFactRef =
      variables.stream().anyMatch(v -> !v.factRefs().isEmpty()) ||
      mechanisms.stream().anyMatch(m -> !m.factRefs().isEmpty());
    if (facts.isEmpty() || !anyFactRef) return 0;
    if (coreQuantityStatedInFact) return 5;
    boolean documentedMechanism = mechanisms
      .stream()
      .anyMatch(m ->
        m.supportLevel() != SupportLevel.SPECULATIVE && !m.factRefs().isEmpty()
      );
    if (documentedMechanism) return 4;
    if (documentedAssumptions.size() == 1) return 3;
    if (factsRestateSummaryOnly) return 1;
    return 2;
  }

  /** D3 Independent Corroboration (20). */
  public int levelD3() {
    if (independentSourceCount == 0) return nonIndependentAdditionCount > 0
      ? 1
      : 0;
    if (independentConfirmingSourceCount >= 2) return independentMethodCount >=
      2
      ? 5
      : 4;
    if (independentConfirmingSourceCount == 1) return 3;
    return 2;
  }

  /** D4 Mechanism Support (20). */
  public int levelD4() {
    if (mechanisms.isEmpty()) return 0;
    int best = 0;
    for (var m : mechanisms) {
      if (m.supportLevel() == SupportLevel.SPECULATIVE) {
        best = Math.max(best, 1);
        continue;
      }
      int level = m.factRefs().isEmpty() ? 2 : 3;
      if (
        m.supportLevel() == SupportLevel.SUPPORTED && m.factRefs().size() >= 2
      ) {
        level = 4;
        // §5 level 5: as level 4 plus an *independent corroborating signal*.
        // In the v0.1 projection the signal list is the only deterministic
        // evidence that the mechanism itself was corroborated; independent
        // sources are scored by D3 and must not silently raise D4 too.
        if (!signals.isEmpty()) level = 5;
      }
      best = Math.max(best, level);
    }
    return best;
  }

  /** D5 Counter-evidence Resilience (15). */
  public int levelD5() {
    boolean hasCounter = !counterArguments.isEmpty();
    boolean hasFalsification = !falsificationConditions.isEmpty();
    if (!hasCounter && !hasFalsification) return 0;
    if (hasCounter != hasFalsification) return 1;
    // §5 level 3 requires a fact/evidence reference on the strongest objection;
    // provenance alone (sourceRefs) does not raise the level.
    boolean counterCarriesFactRef = counterArguments
      .stream()
      .anyMatch(c -> !c.factRefs().isEmpty());
    if (!counterCarriesFactRef) return 2;
    boolean observable = falsificationConditions
      .stream()
      .allMatch(this::observable);
    boolean disputed = facts
      .stream()
      .anyMatch(f -> f.verificationStatus() == ProofStatus.DISPUTED);
    if (!observable || disputed) return 3;
    boolean timeBounded = falsificationConditions
      .stream()
      .allMatch(this::timeBounded);
    if (contradictingEvidenceCount > 0 && timeBounded) return 5;
    return 4;
  }

  private boolean observable(FalsificationCondition c) {
    return (
      nonBlank(c.observable()) &&
      nonBlank(c.comparison()) &&
      nonBlank(c.decisionBoundary())
    );
  }

  private boolean timeBounded(FalsificationCondition c) {
    String text = c.decisionBoundary() == null ? "" : c.decisionBoundary();
    return (
      text.matches(".*\\d{4}-\\d{2}-\\d{2}.*") ||
      text.matches(".*\\b(window|by|within|deadline)\\b.*")
    );
  }

  private static boolean nonBlank(String value) {
    return value != null && !value.isBlank();
  }

  // ---- caps (CONFIDENCE_MODEL_V0_1 §6) -----------------------------------

  /** Binding ceilings only, as `id -> ceiling`; the minimum is applied once. */
  public List<Cap> caps() {
    var caps = new java.util.ArrayList<Cap>();
    if (levelD3() <= 1) caps.add(
      new Cap("CAP-A", 49, "no independent corroboration")
    );
    if (mainHypothesisRestsOnDisputedFact) caps.add(
      new Cap("CAP-B", 69, "main hypothesis rests on a disputed fact")
    );
    if (!usablePlanItem()) caps.add(
      new Cap(
        "CAP-C",
        59,
        "no verification plan item with distinguishable results and a deadline"
      )
    );
    if (levelD4() <= 1) caps.add(
      new Cap("CAP-D", 59, "no documented mechanism")
    );
    if (levelD2() <= 1) caps.add(
      new Cap("CAP-E", 49, "facts do not carry the claim")
    );
    if (levelD3() <= 3) caps.add(
      new Cap("CAP-F", 84, "fewer than two independent confirming sources")
    );
    return List.copyOf(caps);
  }

  /** CAP-C: a plan item is only usable when its two outcomes are distinguishable. */
  public boolean usablePlanItem() {
    return plan
      .stream()
      .anyMatch(p ->
        p.deadline() != null &&
        nonBlank(p.supportingResult()) &&
        nonBlank(p.contradictingResult()) &&
        !p.supportingResult().equals(p.contradictingResult())
      );
  }

  /** One binding ceiling rule. */
  public record Cap(String id, int ceiling, String rationale) {}
}
