package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-09 hypotheses: the best current explanation of the fact set, held provisionally with assumptions.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class HypothesisGenerationStage extends AbstractProtocolStage {

  static final String NAME = "HypothesisGeneration";
  static final String STEP = "GenerateHypotheses";
  static final String PROMPT = "hypothesis-generation";

  public HypothesisGenerationStage(
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
      prompts.facts(in) + prompts.variables(in) + prompts.mechanisms(in),
      raw -> reader.hypotheses(raw, in)
    );
    return in
      .withHypotheses(artifacts.value())
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
    return JobStatus.GENERATING_HYPOTHESES;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
