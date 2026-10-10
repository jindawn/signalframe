package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.NewsItem;
import com.signalframe.contract.NewsValueScore;
import com.signalframe.contract.DomainType;
import java.time.Instant;
import java.util.*;

/**
 * The immutable artifact flow of one analysis run.
 *
 * <p>Every protocol stage receives a state and returns a new state carrying
 * exactly one added artifact. A stage cannot reach into another stage's output
 * because there is no shared mutable carrier: the fold in
 * {@code AnalysisPipeline} replaces the reference after each step. Stages are
 * therefore independently testable with a hand-built state.
 *
 * <p>{@code executedStages} and {@code promptVersions} are the run's audit
 * trail: Gate E uses the first to prove that a stage either produced an artifact
 * or an explicit UNKNOWN (protocol PR-05), and PR-16 uses the second to record
 * which prompt versions produced the snapshot.
 */
public record PipelineState(
  UUID jobId,
  String correlationId,
  NewsItem news,
  Instant snapshotCreatedAt,
  String draftSnapshotJson,
  SourceAssessment sourceAssessment,
  DomainType domain,
  NewsValueScore newsValueScore,
  List<Fact> facts,
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
  List<Prediction> predictions,
  List<PlanItem> verificationPlan,
  List<Unknown> unknowns,
  List<String> executedStages,
  List<String> promptVersions,
  boolean demo,
  ConfidenceScore confidence,
  String summary,
  AnalysisResult result
) {

  public static PipelineState initial(
    UUID jobId,
    String correlationId,
    NewsItem news,
    Instant snapshotCreatedAt
  ) {
    return new PipelineState(
      jobId,
      correlationId,
      news,
      snapshotCreatedAt,
      null,
      null,
      null,
      null,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      false,
      null,
      null,
      null
    );
  }

  public PipelineState {
    facts = List.copyOf(facts);
    variables = List.copyOf(variables);
    mechanisms = List.copyOf(mechanisms);
    stakeholders = List.copyOf(stakeholders);
    firstOrderEffects = List.copyOf(firstOrderEffects);
    secondOrderEffects = List.copyOf(secondOrderEffects);
    hypotheses = List.copyOf(hypotheses);
    alternativeExplanations = List.copyOf(alternativeExplanations);
    counterArguments = List.copyOf(counterArguments);
    falsificationConditions = List.copyOf(falsificationConditions);
    corroboratingSignals = List.copyOf(corroboratingSignals);
    predictions = List.copyOf(predictions);
    verificationPlan = List.copyOf(verificationPlan);
    unknowns = List.copyOf(unknowns);
    executedStages = List.copyOf(executedStages);
    promptVersions = List.copyOf(promptVersions);
  }

  /** Records that a stage ran and which prompt version it used (PR-15/PR-16). */
  public PipelineState executed(String stage, String promptVersion) {
    var stages = new ArrayList<>(executedStages);
    stages.add(stage);
    var versions = new ArrayList<>(promptVersions);
    if (promptVersion != null && !promptVersion.isBlank()) versions.add(
      promptVersion
    );
    return new PipelineState(
      jobId,
      correlationId,
      news,
      snapshotCreatedAt,
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      stages,
      versions,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withDraftSnapshot(String value) {
    return copy(
      value,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withSourceAssessment(SourceAssessment value) {
    return copy(
      draftSnapshotJson,
      value,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withDomain(DomainType value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      value,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withNewsValueScore(NewsValueScore value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      value,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withFacts(List<Fact> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      value,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withVariables(List<Variable> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      value,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withMechanisms(List<Mechanism> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      value,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withStakeholders(List<Stakeholder> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      value,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withFirstOrderEffects(List<Effect> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      value,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withSecondOrderEffects(List<Effect> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      value,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withHypotheses(List<Hypothesis> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      value,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withAlternativeExplanations(List<Alternative> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      value,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withCounterArguments(List<CounterArgument> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      value,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withFalsificationConditions(
    List<FalsificationCondition> value
  ) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      value,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withCorroboratingSignals(List<Signal> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      value,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withPredictions(List<Prediction> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      value,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withVerificationPlan(List<PlanItem> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      value,
      unknowns,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withUnknowns(List<Unknown> value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      value,
      demo,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withDemo(boolean value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      value,
      confidence,
      summary,
      result
    );
  }

  public PipelineState withConfidence(ConfidenceScore value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      value,
      summary,
      result
    );
  }

  public PipelineState withSummary(String value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      value,
      result
    );
  }

  public PipelineState withResult(AnalysisResult value) {
    return copy(
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      demo,
      confidence,
      summary,
      value
    );
  }

  private PipelineState copy(
    String draftSnapshotJson,
    SourceAssessment sourceAssessment,
    DomainType domain,
    NewsValueScore newsValueScore,
    List<Fact> facts,
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
    List<Prediction> predictions,
    List<PlanItem> verificationPlan,
    List<Unknown> unknowns,
    boolean demo,
    ConfidenceScore confidence,
    String summary,
    AnalysisResult result
  ) {
    return new PipelineState(
      jobId,
      correlationId,
      news,
      snapshotCreatedAt,
      draftSnapshotJson,
      sourceAssessment,
      domain,
      newsValueScore,
      facts,
      variables,
      mechanisms,
      stakeholders,
      firstOrderEffects,
      secondOrderEffects,
      hypotheses,
      alternativeExplanations,
      counterArguments,
      falsificationConditions,
      corroboratingSignals,
      predictions,
      verificationPlan,
      unknowns,
      executedStages,
      promptVersions,
      demo,
      confidence,
      summary,
      result
    );
  }
}
