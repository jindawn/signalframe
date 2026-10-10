package com.signalframe.research.domain.evidence;

import com.signalframe.contract.Evidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for sourced evidence (TASK-07).
 *
 * <p>Evidence is written once and never rewritten: a changed bearing on a
 * hypothesis is a new item plus a hypothesis transition, not an in-place edit
 * (PR-17). The implementation lives in
 * {@code infrastructure/persistence/JdbcEvidencePredictionRepository}, which
 * TASK-07 owns by name (freeze §7).
 */
public interface EvidenceRepository {
  /** Inserts one evidence item and returns it as persisted. */
  Evidence save(Evidence evidence);

  /** The evidence item with this id, when it exists. */
  Optional<Evidence> findEvidence(UUID evidenceId);

  /** Every evidence item attached to a hypothesis, oldest first. */
  List<Evidence> evidenceFor(UUID hypothesisId);
}
