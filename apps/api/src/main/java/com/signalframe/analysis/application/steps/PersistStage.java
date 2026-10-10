package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.domain.AnalysisRepository;
import com.signalframe.contract.Analysis;
import com.signalframe.contract.JobStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pipeline step `Persist`: the immutable snapshot write.
 *
 * <p>Persistence is the last stage and it writes exactly the validated result.
 * The transactional completion (snapshot plus research objects in one
 * transaction) belongs to {@link AnalysisRepository#complete}, which already
 * refuses a job that reached a terminal state.
 */
@Component
public class PersistStage implements ProtocolStage {

  static final String NAME = "Persist";
  static final String STEP = "Persist";

  private final AnalysisRepository analyses;

  public PersistStage(AnalysisRepository analyses) {
    this.analyses = analyses;
  }

  @Override
  public PipelineState execute(PipelineState in) {
    if (in.result() == null) throw new StageFailure(
      NAME,
      StageFailure.Kind.MISSING_ARTIFACT,
      "no validated snapshot exists to persist (Gate A/D must pass first)"
    );
    analyses.complete(
      new Analysis(
        UUID.randomUUID(),
        in.news().id(),
        in.jobId(),
        in.domain(),
        in.newsValueScore(),
        in.result(),
        Instant.now()
      )
    );
    return in.executed(NAME, null);
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
