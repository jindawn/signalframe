package com.signalframe.analysis.application.steps;

import com.signalframe.ai.application.AnalysisResultValidator;
import com.signalframe.analysis.application.strategies.DomainStrategyResolver;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.DomainType;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import com.signalframe.shared.confidence.ConfidenceScore;
import java.util.List;

/**
 * The deterministic assembler: two validated stage results become one
 * {@link AnalysisResult} that the existing validator accepts.
 *
 * <p>It exists so that no model ever produces the final snapshot. It performs no
 * model call, adds no claim, and owns no conversion logic of its own: it folds the
 * validated artifacts into a {@link PipelineState} and then uses the very same
 * components the pipeline uses at the end of any analysis —
 * {@link ConfidenceProjection} for the rubric inputs, the single
 * {@link ConfidenceRubric} for the score, and {@link SnapshotProjection} for the
 * projection onto the contract. That is what keeps the two-stage path from
 * growing a second definition of {@code AnalysisResult} or a second scoring rule.
 *
 * <p>Gate order, mirroring the protocol: the assembled snapshot is only returned
 * after the full {@link AnalysisResultValidator} (Gate A plus the expressible
 * parts of Gate B/C) accepts it. A rejection here is a stage failure, never a
 * partial success, so a broken stage result can never be persisted as an analysis.
 */
final class TwoStageAssembly {

  private final AnalysisResultValidator validator;
  private final JsonCodec json;
  private final ConfidenceRubric rubric;
  private final List<DomainAnalysisStrategy> strategies;

  TwoStageAssembly(
    AnalysisResultValidator validator,
    JsonCodec json,
    ConfidenceRubric rubric,
    List<DomainAnalysisStrategy> strategies
  ) {
    this.validator = validator;
    this.json = json;
    this.rubric = rubric;
    this.strategies = List.copyOf(strategies);
  }

  /**
   * @param state the pipeline state carrying the validated stage artifacts and the
   *              deterministic material (source assessment, prompt versions)
   * @param stage stage name used in failure messages
   */
  AnalysisResult assemble(PipelineState state, String stage) {
    if (state.facts().isEmpty()) throw new StageFailure(
      stage,
      StageFailure.Kind.MISSING_ARTIFACT,
      "stage 1 produced no fact, so nothing downstream can be supported (STG-03)"
    );
    if (state.summary() == null || state.summary().isBlank()) throw new StageFailure(
      stage,
      StageFailure.Kind.MISSING_ARTIFACT,
      "stage 2 produced no summary, so the snapshot cannot be assembled (STG-16)"
    );

    // The domain is deterministic and is not yet on the state at this point in the
    // pipeline, so it is derived exactly as the draft stage derives it.
    var domain = state.domain() == null
      ? DomainClassificationStage.classify(
          state.news().source().text(),
          state.news().title()
        )
      : state.domain();
    var classified = state.withDomain(domain);
    var strategy = DomainStrategyResolver.resolve(domain, strategies);

    // Confidence comes from the one rubric, over the assembled artifacts. The
    // model never supplies it: no stage output carries a score (CF-01).
    ConfidenceScore score = rubric.score(
      ConfidenceProjection.snapshotScope(classified)
    );
    if (!score.isRubric()) throw new StageFailure(
      stage,
      StageFailure.Kind.VALIDATION_FAILED,
      "the rubric failed closed on the assembled snapshot (CF-06)"
    );

    var projected = SnapshotProjection.project(
      classified.withConfidence(score),
      rubric,
      DomainStrategyResolver.idOf(strategy)
    );
    try {
      return validator.parse(json.write(projected), state.news());
    } catch (RuntimeException invalid) {
      throw new StageFailure(
        stage,
        StageFailure.Kind.VALIDATION_FAILED,
        "the assembled snapshot was rejected by the AnalysisResult validator: " +
        invalid.getMessage()
      );
    }
  }

  /** Exposed for the offline budget report: the domain the assembler would use. */
  DomainType domainOf(PipelineState state) {
    return state.domain() == null
      ? DomainClassificationStage.classify(
          state.news().source().text(),
          state.news().title()
        )
      : state.domain();
  }
}
