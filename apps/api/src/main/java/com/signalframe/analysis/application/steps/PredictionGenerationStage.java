package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-14 predictions: dated, observable and verifiable claims derived from a hypothesis.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class PredictionGenerationStage extends AbstractProtocolStage {

  static final String NAME = "PredictionGeneration";
  static final String STEP = "GenerateVerificationPlan";
  static final String PROMPT = "prediction-generation";

  public PredictionGenerationStage(
    StageModelClient client,
    PromptLibrary promptLibrary,
    StageResponseReader reader,
    StagePrompts prompts,
    AnalysisPipelineMode pipelineMode
  ) {
    super(client, promptLibrary, reader, prompts, pipelineMode);
  }

  @Override
  public PipelineState execute(PipelineState in) {
    requireHypotheses(in);
    var artifacts = artifacts(
      in,
      PROMPT,
      ModelPurpose.DEEP_ANALYSIS,
      prompts.hypotheses(in),
      raw -> reader.predictions(raw, in)
    );
    return in
      .withPredictions(artifacts.value())
      .withUnknowns(merge(in.unknowns(), artifacts.unknowns()))
      .withDemo(in.demo() || artifacts.demo())
      .executed(NAME, promptVersion(PROMPT));
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.VERIFYING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
