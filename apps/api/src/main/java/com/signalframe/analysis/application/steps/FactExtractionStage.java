package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.ModelPurpose;
import org.springframework.stereotype.Component;

/**
 * STG-03 fact extraction: atomic facts with verbatim source spans, never independently verified truth.
 *
 * <p>The artifact source follows {@link AnalysisPipelineMode}: a validated draft
 * snapshot in the default topology, or this stage's own versioned, audited model
 * call in the staged topology. In both cases the same typed parser and the same
 * protocol rules decide whether the artifact is acceptable.
 */
@Component
public class FactExtractionStage extends AbstractProtocolStage {

  static final String NAME = "FactExtraction";
  static final String STEP = "ExtractFacts";
  static final String PROMPT = "fact-extraction";

  public FactExtractionStage(
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
    var artifacts = artifacts(
      in,
      PROMPT,
      ModelPurpose.FACT_EXTRACTION,
      "No upstream artifact exists yet: extract facts from the source text alone.",
      raw -> reader.facts(raw, in)
    );
    return in
      .withFacts(artifacts.value())
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
    return JobStatus.EXTRACTING_FACTS;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
