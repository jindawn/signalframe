package com.signalframe.analysis.application.steps;

import com.signalframe.contract.JobStatus;
import com.signalframe.shared.confidence.ConfidenceRubric;
import org.springframework.stereotype.Component;

/**
 * STG-16 confidence assessment: the deterministic rubric, invoked after assembly.
 *
 * <p>The stage is model-free by construction. A model may state a number in its
 * own output, but that number is advisory input at most and is never read here:
 * the score, band, dimensions and reason are a pure function of the assembled
 * snapshot (EP-09/CF-01/CF-02).
 */
@Component
public class ConfidenceAssessmentStage implements ProtocolStage {

  static final String NAME = "ConfidenceAssessment";
  static final String STEP = "SynthesizeAnalysis";

  private final ConfidenceRubric rubric;

  public ConfidenceAssessmentStage(ConfidenceRubric rubric) {
    this.rubric = rubric;
  }

  @Override
  public PipelineState execute(PipelineState in) {
    if (in.sourceAssessment() == null) throw new StageFailure(
      NAME,
      StageFailure.Kind.MISSING_ARTIFACT,
      "no source assessment exists, so no rubric dimension is derivable (CF-06)"
    );
    var inputs = ConfidenceProjection.snapshotScope(in);
    var score = rubric.score(inputs);
    if (!score.isRubric()) throw new StageFailure(
      NAME,
      StageFailure.Kind.VALIDATION_FAILED,
      "the rubric failed closed on this snapshot (CF-06)"
    );
    return in.withConfidence(score).executed(NAME, null);
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
