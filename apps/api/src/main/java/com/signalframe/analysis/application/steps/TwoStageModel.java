package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import java.util.List;

/**
 * The intermediate contracts of the two-stage synthesis path.
 *
 * <p>These are <strong>not</strong> contract records and not a second
 * {@code AnalysisResult}: they are the narrowed shapes two smaller model calls
 * are allowed to produce, and they exist so each call carries a small prompt and
 * can be validated on its own before anything is assembled. Every type they use
 * is the existing handwritten stage-artifact vocabulary
 * ({@link ProtocolModel}), and every array they carry is validated by the
 * existing {@link StageResponseReader} — the same validators the staged topology
 * already uses, so the protocol rules are not reimplemented here.
 *
 * <p>{@code facts} is deliberately absent from {@link Reasoning}: only stage 1 may
 * mint a fact or a source span, so stage 2 structurally cannot invent one.
 */
public final class TwoStageModel {

  private TwoStageModel() {}

  /**
   * Stage 1: what the source states, reproduced with provenance.
   *
   * @param facts    every distinct fact span; validated by {@code StageResponseReader.facts}
   * @param unknowns what the source does not say (PR-05: never silence)
   */
  public record Facts(List<Fact> facts, List<Unknown> unknowns) {}

  /**
   * Stage 2: the analytical artifacts that reason over stage-1 fact ids.
   *
   * <p>Fields are nullable on purpose: an absent array must be distinguishable
   * from an empty one so the stage validator can reject a reply that simply
   * omitted a required artifact instead of evaluating it (PR-05).
   */
  public record Reasoning(
    String summary,
    List<Variable> variables,
    List<Mechanism> mechanisms,
    List<Stakeholder> stakeholders,
    List<Effect> firstOrderEffects,
    List<Effect> secondOrderEffects,
    List<Hypothesis> hypotheses,
    List<Alternative> alternativeExplanations,
    List<CounterArgument> counterArguments,
    List<FalsificationCondition> falsificationConditions,
    List<Signal> corroboratingSignals,
    List<PlanItem> verificationIndicators,
    List<Prediction> predictions,
    List<Unknown> unknowns
  ) {}
}
