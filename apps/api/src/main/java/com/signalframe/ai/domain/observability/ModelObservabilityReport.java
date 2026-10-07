package com.signalframe.ai.domain.observability;

import java.time.Instant;
import java.util.List;

/**
 * One audit report: the applied selection, the unattributed totals and the
 * requested grouping.
 *
 * <p>Contains no prompt text, no request or response body and no secret. The
 * audit table stores identifiers, status and usage only.
 */
public record ModelObservabilityReport(
  ModelRunFilter filter,
  ModelRunGrouping grouping,
  Instant generatedAt,
  int rowLimit,
  boolean truncated,
  ModelRunSummary summary,
  List<ModelRunAggregate> groups
) {}
