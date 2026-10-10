package com.signalframe.analysis.application.steps;

import com.signalframe.ai.application.AnalysisResultValidator;
import com.signalframe.contract.JobStatus;
import com.signalframe.shared.JsonCodec;
import org.springframework.stereotype.Component;

/**
 * Gate A and Gate D, then the projection onto the v0.1 snapshot.
 *
 * <p>Gate A is the project's structural validator (schema constraints plus
 * provenance): the projected snapshot is serialised and re-parsed through
 * {@link AnalysisResultValidator}, so the persisted object is exactly the object
 * that passed validation. Gate D recomputes the rubric and fails the job if the
 * stored score, band, dimension levels or points differ (CF-03/CF-08). Only after
 * both gates pass does the state carry a result the pipeline may persist.
 */
@Component
public class ProjectionStage implements ProtocolStage {

  static final String NAME = "Projection";
  static final String STEP = "SynthesizeAnalysis";

  private final AnalysisResultValidator gateA;
  private final JsonCodec json;
  private final ConfidenceRubric rubric;
  private final StrategySelector strategies;

  public ProjectionStage(
    AnalysisResultValidator gateA,
    JsonCodec json,
    ConfidenceRubric rubric,
    StrategySelector strategies
  ) {
    this.gateA = gateA;
    this.json = json;
    this.rubric = rubric;
    this.strategies = strategies;
  }

  @Override
  public PipelineState execute(PipelineState in) {
    if (in.summary() == null || in.summary().isBlank()) throw new StageFailure(
      NAME,
      StageFailure.Kind.MISSING_ARTIFACT,
      "no summary was produced, so the snapshot cannot be assembled (STG-16)"
    );
    var score = in.confidence();
    if (score == null || !score.isRubric()) throw new StageFailure(
      NAME,
      StageFailure.Kind.VALIDATION_FAILED,
      "no rubric-derived confidence exists for this snapshot (Gate D)"
    );
    var inputs = ConfidenceProjection.snapshotScope(in);
    if (!rubric.matchesStored(score, inputs)) throw new StageFailure(
      NAME,
      StageFailure.Kind.VALIDATION_FAILED,
      "Gate D: the stored confidence does not match a deterministic recomputation (CF-03/CF-08)"
    );
    var strategy = strategies.select(in.domain());
    var projected = SnapshotProjection.project(
      in,
      score,
      inputs,
      rubric,
      strategies.idOf(strategy)
    );
    try {
      var validated = gateA.parse(json.write(projected), in.news());
      return in.withResult(validated).executed(NAME, null);
    } catch (StageFailure failure) {
      throw failure;
    } catch (RuntimeException invalid) {
      throw new StageFailure(
        NAME,
        StageFailure.Kind.VALIDATION_FAILED,
        "Gate A rejected the assembled snapshot: " + invalid.getMessage()
      );
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.SYNTHESIZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
