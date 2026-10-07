package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.PipelineContext;
import com.signalframe.analysis.domain.AnalysisStep;
import com.signalframe.contract.JobStatus;
import java.util.function.Consumer;

/** Default replaceable implementation for a single named stage. */
public record FoundationStep(
  String name,
  JobStatus status,
  Consumer<PipelineContext> action
) implements AnalysisStep<PipelineContext, PipelineContext> {
  public PipelineContext execute(PipelineContext context) {
    action.accept(context);
    return context;
  }
}
