package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.domain.AnalysisStep;
import com.signalframe.contract.JobStatus;

/**
 * One protocol stage behind the fixed pipeline boundaries.
 *
 * <p>This <em>extends</em> the existing {@link AnalysisStep} abstraction (it does
 * not change it): the type parameters are specialised to the owned immutable
 * {@link PipelineState} so that artifacts flow explicitly from stage to stage
 * instead of through a shared mutable context. {@link #name()} is the protocol
 * stage id, {@link #durableStep()} is the coarse durable boundary the stage
 * reports under, and {@link #status()} is the durable job status of that
 * boundary. Internal staging is finer than {@code JobStatus} on purpose: the
 * protocol mapping in ANALYSIS_PROTOCOL_V0_1 §4 keeps the 14 fixed step names and
 * the existing status sequence.
 */
public interface ProtocolStage extends AnalysisStep<PipelineState, PipelineState> {
  /** Fixed durable step name this stage reports under (ADR-004 names). */
  String durableStep();

  /** Durable job status reported while this stage runs. */
  @Override
  JobStatus status();
}
