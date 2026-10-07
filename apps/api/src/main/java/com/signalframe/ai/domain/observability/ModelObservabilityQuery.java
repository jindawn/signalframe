package com.signalframe.ai.domain.observability;

import com.signalframe.contract.ModelRun;
import java.util.List;

/**
 * Read-only port over the persisted model run audit. Implemented against the
 * application datasource; the audit table is append-only and is never mutated
 * through this port.
 */
public interface ModelObservabilityQuery {

  /**
   * @param filter selection applied in the database
   * @param limit hard bound on returned rows, oldest first
   * @return matching runs ordered by start time
   */
  List<ModelRun> runs(ModelRunFilter filter, int limit);
}
