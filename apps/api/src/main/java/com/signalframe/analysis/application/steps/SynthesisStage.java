package com.signalframe.analysis.application.steps;

import com.signalframe.ai.application.ModelAnalysisService;
import com.signalframe.analysis.application.strategies.DomainStrategyResolver;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.JobStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * STG-16 assembly companion: the snapshot's summary.
 *
 * <p>The protocol forbids a synthesis stage from authoring claims: it "may
 * repair, reject and assemble; it may not author claims that no stage produced"
 * (§4). This stage therefore asks the existing synthesis call — the one
 * foundation entry point that is already schema-validated, audited and mock-aware
 * — for a summary only, after handing it the validated artifacts as guidance. The
 * returned claim artifacts are ignored: facts, variables, mechanisms, hypotheses,
 * signals and the plan come from their own stages, and confidence comes from the
 * rubric.
 *
 * <p>The call goes through {@link ModelAnalysisService} unchanged, so the offline
 * adapter and every provider keep working exactly as before; the model run it
 * records carries the {@code synthesis-v1} prompt version. In the default draft
 * topology the summary comes from the single draft call instead, so an analysis
 * never issues two provider runs.
 */
@Component
public class SynthesisStage implements ProtocolStage {

  static final String NAME = "Synthesis";
  static final String STEP = "SynthesizeAnalysis";
  static final String PROMPT_DIRECTORY = "synthesis";

  /** Bound on the artifact context handed to the summary call. */
  static final int MAX_GUIDANCE_CHARS = 6000;

  private final ModelAnalysisService model;
  private final PromptLibrary promptLibrary;
  private final List<DomainAnalysisStrategy> strategies;
  private final AnalysisPipelineMode pipelineMode;

  public SynthesisStage(
    ModelAnalysisService model,
    PromptLibrary promptLibrary,
    List<DomainAnalysisStrategy> strategies,
    AnalysisPipelineMode pipelineMode
  ) {
    this.model = model;
    this.promptLibrary = promptLibrary;
    this.strategies = List.copyOf(strategies);
    this.pipelineMode = pipelineMode;
  }

  @Override
  public PipelineState execute(PipelineState in) {
    if (!pipelineMode.staged()) {
      // The draft topology already holds the model-authored summary; calling the
      // provider again would add a second audited run per analysis.
      if (in.summary() == null || in.summary().isBlank()) throw new StageFailure(
        NAME,
        StageFailure.Kind.MISSING_ARTIFACT,
        "the draft snapshot carries no summary (STG-16)"
      );
      return in.executed(NAME, null);
    }
    var strategy = DomainStrategyResolver.resolve(in.domain(), strategies);
    var guidance =
      "Summarize ONLY the validated artifacts below. Do not introduce facts, causal claims or sources that are not listed. " +
      "Every artifact is already labelled; keep FACT/INFERENCE/HYPOTHESIS separation.\n" +
      "DOMAIN GUIDANCE: " +
      strategy.guidance() +
      "\n" +
      renderArtifacts(in);
    var result = model.synthesize(
      in.jobId(),
      in.correlationId(),
      in.news(),
      bound(guidance, MAX_GUIDANCE_CHARS)
    );
    if (result.summary() == null || result.summary().isBlank()) throw new StageFailure(
      NAME,
      StageFailure.Kind.MALFORMED_OUTPUT,
      "the synthesis call returned an empty summary"
    );
    String promptVersion = promptLibrary.load(PROMPT_DIRECTORY).version();
    return in
      .withSummary(result.summary())
      .withDemo(in.demo() || Boolean.TRUE.equals(result.demo()))
      .executed(NAME, promptVersion);
  }

  /**
   * Deterministic rendering of the assembled artifacts. It lists only what exists
   * and states explicitly what does not, so the summary call cannot invent
   * content for an absent stage.
   */
  private static String renderArtifacts(PipelineState in) {
    var lines = new ArrayList<String>();
    lines.add("domain=" + in.domain());
    lines.add("facts=" + in.facts().size());
    for (var f : in.facts()) lines.add(
      "  FACT [" + f.verificationStatus() + "] " + f.statement()
    );
    lines.add("variables=" + in.variables().size());
    for (var v : in.variables()) lines.add(
      "  VARIABLE " + v.name() + " direction=" + v.direction()
    );
    lines.add("mechanisms=" + in.mechanisms().size());
    for (var m : in.mechanisms()) lines.add(
      "  MECHANISM " + m.from() + " -> " + m.to() + " [" + m.supportLevel() + "]"
    );
    lines.add("hypotheses=" + in.hypotheses().size());
    for (var h : in.hypotheses()) lines.add(
      "  HYPOTHESIS " + h.title() + ": " + h.statement()
    );
    lines.add("alternativeExplanations=" + in.alternativeExplanations().size());
    lines.add("counterArguments=" + in.counterArguments().size());
    lines.add("falsificationConditions=" + in.falsificationConditions().size());
    lines.add("corroboratingSignals=" + in.corroboratingSignals().size());
    lines.add("predictions=" + in.predictions().size());
    lines.add("verificationPlan=" + in.verificationPlan().size());
    lines.add(
      "confidence=" +
      (in.confidence() == null
          ? "not yet computed"
          : in.confidence().score() + " (" + in.confidence().band() + ")")
    );
    lines.add(
      "unknowns=" +
      in.unknowns().size() +
      (in.unknowns().isEmpty() ? "" : " (stated as UNKNOWN items)")
    );
    return String.join("\n", lines);
  }

  private static String bound(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.SYNTHESIZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
