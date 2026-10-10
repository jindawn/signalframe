package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.domain.NewsValueScorer;
import com.signalframe.contract.JobStatus;
import org.springframework.stereotype.Component;

/**
 * Pipeline step `ScoreNews`: news value is a routing/attention score, never an
 * epistemic artifact.
 *
 * <p>It carries no confidence and no claim, and it never feeds the confidence
 * rubric (CONFIDENCE_MODEL_V0_1 §8 lists the scoped objects: {@code NewsValueScore}
 * is not one of them).
 */
@Component
public class NewsScoringStage implements ProtocolStage {

  static final String NAME = "NewsScoring";
  static final String STEP = "ScoreNews";

  private final NewsValueScorer scorer;

  public NewsScoringStage(NewsValueScorer scorer) {
    this.scorer = scorer;
  }

  @Override
  public PipelineState execute(PipelineState in) {
    return in
      .withNewsValueScore(scorer.score(in.news()))
      .executed(NAME, null);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.ANALYZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
