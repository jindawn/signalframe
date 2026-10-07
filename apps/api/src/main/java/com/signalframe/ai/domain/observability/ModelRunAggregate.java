package com.signalframe.ai.domain.observability;

import com.signalframe.contract.ModelPurpose;
import java.util.UUID;

/**
 * Aggregated audit metrics for one group of model runs.
 *
 * <p>Usage and cost are never fabricated. {@code inputTokens},
 * {@code outputTokens}, {@code totalTokens} and {@code estimatedCost} are null
 * when no contributing run reported a value; a partial sum is additionally
 * flagged by {@code unknownUsageRuns} / {@code unknownCostRuns} so a caller
 * cannot mistake it for a complete total. Dimensions that do not participate in
 * the requested grouping are null.
 */
public record ModelRunAggregate(
  String group,
  String provider,
  String model,
  ModelPurpose purpose,
  String promptVersion,
  UUID jobId,
  long runs,
  long succeeded,
  long failed,
  long retries,
  long unknownUsageRuns,
  long unknownCostRuns,
  Long inputTokens,
  Long outputTokens,
  Long totalTokens,
  Double estimatedCost,
  long latencyMsTotal,
  long latencyMsMax
) {

  /** Attempt-level success rate in [0,1]; 0 when the group is empty. */
  public double successRate() {
    return runs == 0 ? 0.0 : (double) succeeded / runs;
  }

  /** Attempt-level error rate in [0,1]; 0 when the group is empty. */
  public double errorRate() {
    return runs == 0 ? 0.0 : (double) failed / runs;
  }

  /** Mean latency of the grouped attempts, 0 when the group is empty. */
  public long averageLatencyMs() {
    return runs == 0 ? 0L : latencyMsTotal / runs;
  }
}
