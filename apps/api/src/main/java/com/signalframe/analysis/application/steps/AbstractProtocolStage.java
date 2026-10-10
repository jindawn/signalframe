package com.signalframe.analysis.application.steps;

import com.signalframe.contract.ModelPurpose;
import java.util.*;

/**
 * Shared helpers for protocol stages.
 *
 * <p>Stage code stays short and honest: a stage that lacks its mandatory input
 * fails with {@link StageFailure.Kind#MISSING_ARTIFACT} instead of producing an
 * empty artifact that reads as "evaluated, nothing found" (protocol PR-05).
 *
 * <p>{@link #artifacts} is the single place where the run's call topology is
 * resolved: in {@link AnalysisPipelineMode.Mode#DRAFT} a stage reads its typed
 * artifact out of the already-validated draft snapshot, in
 * {@link AnalysisPipelineMode.Mode#STAGED} it performs its own typed model call.
 * The stages themselves are identical, so stage isolation, validation and gates
 * are topology-independent.
 */
abstract class AbstractProtocolStage implements ProtocolStage {

  protected final StageModelClient client;
  protected final PromptLibrary promptLibrary;
  protected final StageResponseReader reader;
  protected final StagePrompts prompts;
  protected final AnalysisPipelineMode pipelineMode;

  protected AbstractProtocolStage(
    StageModelClient client,
    PromptLibrary promptLibrary,
    StageResponseReader reader,
    StagePrompts prompts,
    AnalysisPipelineMode pipelineMode
  ) {
    this.client = client;
    this.promptLibrary = promptLibrary;
    this.reader = reader;
    this.prompts = prompts;
    this.pipelineMode = pipelineMode;
  }

  /**
   * Produces one stage artifact through the configured topology and validates it.
   * Both paths run the same typed parser, so a malformed or protocol-violating
   * artifact fails the same way in either mode.
   */
  protected <T> StageResponseReader.Artifacts<T> artifacts(
    PipelineState in,
    String promptDirectory,
    ModelPurpose purpose,
    String context,
    StageModelClient.StageParser<StageResponseReader.Artifacts<T>> parser
  ) {
    var prompt = promptLibrary.load(promptDirectory);
    if (!pipelineMode.staged()) {
      String draft = in.draftSnapshotJson();
      if (draft == null || draft.isBlank()) throw new StageFailure(
        name(),
        StageFailure.Kind.MISSING_ARTIFACT,
        "no draft snapshot exists for this run, so the stage has no artifact source (PR-05)"
      );
      return parser.parse(draft);
    }
    var call = new StageModelClient.StageCall(
      name(),
      in.jobId(),
      in.correlationId(),
      in.news(),
      purpose,
      prompt.version(),
      prompts.request(prompt.text(), in, context)
    );
    return client.invoke(call, parser);
  }

  /** Prompt version recorded for this stage, or null in draft topology. */
  protected String promptVersion(String promptDirectory) {
    return pipelineMode.staged()
      ? promptLibrary.load(promptDirectory).version()
      : null;
  }

  /** STG-03 facts are the mandatory input of every interpretive stage. */
  protected PipelineState requireFacts(PipelineState state) {
    if (state.facts().isEmpty()) throw new StageFailure(
      name(),
      StageFailure.Kind.MISSING_ARTIFACT,
      "the fact extraction stage produced no fact, so no interpretation is possible (EP-02/PR-05)"
    );
    return state;
  }

  /** STG-09 hypotheses are the mandatory input of the verification stages. */
  protected PipelineState requireHypotheses(PipelineState state) {
    if (state.hypotheses().isEmpty()) throw new StageFailure(
      name(),
      StageFailure.Kind.MISSING_ARTIFACT,
      "no hypothesis exists, so this stage has nothing to reason about (EP-02)"
    );
    return state;
  }

  protected static <T> List<T> merge(List<T> first, List<T> second) {
    if (first.isEmpty()) return List.copyOf(second);
    if (second.isEmpty()) return List.copyOf(first);
    var merged = new ArrayList<>(first);
    merged.addAll(second);
    return List.copyOf(merged);
  }

  protected static ProtocolModel.Unknown unknown(
    String statement,
    String reason,
    String wouldResolveWith
  ) {
    return new ProtocolModel.Unknown(
      UUID.randomUUID(),
      statement,
      reason,
      wouldResolveWith,
      List.of()
    );
  }
}
