package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-07 first-order effects: direct near-term consequences traceable to a mechanism or a reported consequence.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class FirstOrderEffectStage extends AbstractProtocolStage {

  static final String NAME = "FirstOrderEffect";
  static final String STEP = "AnalyzeMechanism";
  static final String PROMPT = "first-order-effects";

  public FirstOrderEffectStage(
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
      prompts.mechanisms(in) + prompts.facts(in),
      raw -> reader.firstOrderEffects(raw, in)
    );
    return in
      .withFirstOrderEffects(artifacts.value())
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
