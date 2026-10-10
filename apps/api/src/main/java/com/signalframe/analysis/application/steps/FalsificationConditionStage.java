package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-12 falsification conditions: pre-registered observables with a comparison and a decision boundary.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class FalsificationConditionStage extends AbstractProtocolStage {

  static final String NAME = "FalsificationCondition";
  static final String STEP = "GenerateFalsificationConditions";
  static final String PROMPT = "falsification-conditions";

  public FalsificationConditionStage(
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
      ModelPurpose.COUNTER_ARGUMENT,
      prompts.hypotheses(in),
      raw -> reader.falsificationConditions(raw, in)
    );
    return in
      .withFalsificationConditions(artifacts.value())
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
