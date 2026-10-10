package com.signalframe.analysis.application;

import com.signalframe.analysis.application.steps.AnalysisStages;
import com.signalframe.analysis.application.steps.PipelineState;
import com.signalframe.analysis.application.steps.StageFailure;
import com.signalframe.jobs.domain.JobRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Runs the fixed analysis pipeline as a fold over immutable artifacts.
 *
 * <p>The pipeline owns the durable loop only: it reports one progress event per
 * fixed step and hands the state from stage to stage, replacing the reference
 * after each stage. No stage can reach into another stage's artifact, no stage
 * chooses a provider, and no stage owns hidden mutable state.
 *
 * <p>A step that cannot complete throws a {@link StageFailure}; the job then fails
 * explicitly with a classified reason instead of persisting a snapshot that only
 * looks complete (protocol PR-18).
 */
@Service
public class AnalysisPipeline {

  private final JobRepository jobs;
  private final AnalysisStages stages;

  public AnalysisPipeline(JobRepository jobs, AnalysisStages stages) {
    this.jobs = jobs;
    this.stages = stages;
  }

  /** The fixed durable step names, in execution order. */
  public List<String> stepNames() {
    return stages.steps().stream().map(AnalysisStages.DurableStep::name).toList();
  }

  /** Names of the protocol stages inside a durable step, for diagnostics. */
  public List<String> stageNames(String durableStep) {
    return stages
      .steps()
      .stream()
      .filter(step -> step.name().equals(durableStep))
      .findFirst()
      .map(step ->
        step.stages().stream().map(stage -> stage.name()).toList()
      )
      .orElseThrow(() ->
        new IllegalArgumentException("Unknown durable step: " + durableStep)
      );
  }

  public void run(PipelineContext context) {
    var state = PipelineState.initial(
      context.jobId,
      context.correlationId,
      context.news,
      Instant.now()
    );
    for (var step : stages.steps()) {
      jobs.progress(context.jobId, step.status(), step.name(), step.name());
      for (var stage : step.stages()) state = stage.execute(state);
    }
    if (state.result() == null) throw new StageFailure(
      "AnalysisPipeline",
      StageFailure.Kind.MISSING_ARTIFACT,
      "the pipeline completed without producing a validated snapshot"
    );
    context.domain = state.domain();
    context.score = state.newsValueScore();
    context.facts = state.result().facts();
    context.result = state.result();
  }
}
