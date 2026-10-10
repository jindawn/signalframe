package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-06 stakeholders: who is exposed and in which direction, at a granularity the input supports.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class StakeholderAnalysisStage extends AbstractProtocolStage {

  static final String NAME = "StakeholderAnalysis";
  static final String STEP = "AnalyzeStakeholders";
  static final String PROMPT = "stakeholder-analysis";

  public StakeholderAnalysisStage(
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
    requireFacts(in);
    var artifacts = artifacts(
      in,
      PROMPT,
      ModelPurpose.DEEP_ANALYSIS,
      prompts.variables(in) + prompts.mechanisms(in) + prompts.facts(in),
      raw -> reader.stakeholders(raw, in)
    );
    return in
      .withStakeholders(artifacts.value())
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
    return JobStatus.ANALYZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
