package com.signalframe.ai.domain.observability;

/**
 * Totals across every run selected by a filter.
 *
 * <p>Null semantics match {@link ModelRunAggregate}: an unknown total stays
 * null instead of becoming a fabricated zero, and the number of runs that could
 * not contribute to a total is reported alongside it.
 */
public record ModelRunSummary(
  long runs,
  long succeeded,
  long failed,
  long retries,
  long jobs,
  long unknownUsageRuns,
  long unknownCostRuns,
  Long inputTokens,
  Long outputTokens,
  Long totalTokens,
  Double estimatedCost,
  long latencyMsTotal,
  long latencyMsMax
) {

  public double successRate() {
    return runs == 0 ? 0.0 : (double) succeeded / runs;
  }

  public double errorRate() {
    return runs == 0 ? 0.0 : (double) failed / runs;
  }

  public long averageLatencyMs() {
    return runs == 0 ? 0L : latencyMsTotal / runs;
  }
}
