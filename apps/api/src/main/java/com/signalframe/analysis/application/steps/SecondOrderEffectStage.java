package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-08 second-order effects: consequences of the first-order effects, each naming its parent.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class SecondOrderEffectStage extends AbstractProtocolStage {

  static final String NAME = "SecondOrderEffect";
  static final String STEP = "AnalyzeMechanism";
  static final String PROMPT = "second-order-effects";

  public SecondOrderEffectStage(
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
    if (in.firstOrderEffects().isEmpty()) {
      return in
        .withUnknowns(
          merge(
            in.unknowns(),
            java.util.List.of(
              unknown(
                "No second-order effect was produced because no first-order effect exists to build on",
                "STG-08 forbids introducing a new premise at second order",
                "a first-order effect derived from the facts"
              )
            )
          )
        )
        .executed(NAME, null);
    }
    var artifacts = artifacts(
      in,
      PROMPT,
      ModelPurpose.DEEP_ANALYSIS,
      prompts.firstOrderEffects(in) + prompts.facts(in),
      raw -> reader.secondOrderEffects(raw, in)
    );
    return in
      .withSecondOrderEffects(artifacts.value())
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
