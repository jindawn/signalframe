package com.signalframe.analysis.application.steps;

import com.signalframe.ai.application.ModelAnalysisService;
import com.signalframe.analysis.application.strategies.DomainStrategyResolver;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.JobStatus;
import com.signalframe.shared.JsonCodec;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Obtains the run's model reading once, before any artifact stage runs.
 *
 * <p>In the default {@link AnalysisPipelineMode.Mode#DRAFT} topology this is the
 * only provider call of an analysis: the existing, schema-validated and audited
 * synthesis entry point produces a draft snapshot, and every protocol stage then
 * extracts its own typed artifact from that draft and validates it against the
 * protocol rules. This keeps the foundation's operational contract — one audited
 * provider run per analysis, exactly as the live-provider audit test asserts and
 * as Wave 2A recommends while per-stage model purposes remain integrator-owned.
 *
 * <p>In {@link AnalysisPipelineMode.Mode#STAGED} the stage performs no call: each
 * model-backed stage issues its own typed, versioned and audited call instead.
 *
 * <p>The draft's own confidence number is never used as the snapshot score; the
 * rubric is the only source of the stored score (CF-01).
 */
@Component
public class SnapshotDraftStage implements ProtocolStage {

  static final String NAME = "SnapshotDraft";
  static final String STEP = "ExtractFacts";
  static final String PROMPT_DIRECTORY = "synthesis";

  private final ModelAnalysisService model;
  private final JsonCodec json;
  private final PromptLibrary promptLibrary;
  private final List<DomainAnalysisStrategy> strategies;
  private final AnalysisPipelineMode pipelineMode;

  public SnapshotDraftStage(
    ModelAnalysisService model,
    JsonCodec json,
    PromptLibrary promptLibrary,
    List<DomainAnalysisStrategy> strategies,
    AnalysisPipelineMode pipelineMode
  ) {
    this.model = model;
    this.json = json;
    this.promptLibrary = promptLibrary;
    this.strategies = List.copyOf(strategies);
    this.pipelineMode = pipelineMode;
  }

  @Override
  public PipelineState execute(PipelineState in) {
    if (pipelineMode.staged()) return in.executed(NAME, null);
    var domain = DomainClassificationStage.classify(
      in.news().source().text(),
      in.news().title()
    );
    var strategy = DomainStrategyResolver.resolve(domain, strategies);
    var draft = model.synthesize(
      in.jobId(),
      in.correlationId(),
      in.news(),
      strategy.guidance()
    );
    if (draft.summary() == null || draft.summary().isBlank()) throw new StageFailure(
      NAME,
      StageFailure.Kind.MALFORMED_OUTPUT,
      "the draft snapshot carries no summary, so the snapshot cannot be assembled"
    );
    return in
      .withDraftSnapshot(json.write(draft))
      .withSummary(draft.summary())
      .withDemo(Boolean.TRUE.equals(draft.demo()))
      .executed(NAME, promptLibrary.load(PROMPT_DIRECTORY).version());
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
