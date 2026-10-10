package com.signalframe.research.domain.evidence;

import java.util.Set;
import java.util.UUID;

/**
 * Read-only lookups that evidence and prediction writes need in order to verify
 * their foreign keys and resolve their in-snapshot references (TASK-07).
 *
 * <p>This is a narrow read port, not a hypothesis engine. It deliberately exposes
 * only existence, the optimistic-lock version and the fact ids of a snapshot, and
 * no way to change a hypothesis: the only write path to a hypothesis is
 * {@code HypothesisTransitionPort}, owned by TASK-06 (freeze §4.1 invariant 7).
 *
 * <p>{@code hypotheses} and {@code sources} are declared read-only dependencies of
 * TASK-07 (TASK-07's task file, freeze §7). Reading the stored version here is what
 * lets TASK-07 supply the port's required {@code expectedVersion}; the port still
 * decides whether the version is current.
 */
public interface ResearchReferences {
  /** True when the hypothesis exists. */
  boolean hypothesisExists(UUID hypothesisId);

  /** The stored optimistic-lock version of a hypothesis. */
  long hypothesisVersion(UUID hypothesisId);

  /**
   * The analysis a hypothesis belongs to.
   *
   * <p>PR-01: a reference resolves inside the snapshot it belongs to, so a
   * hypothesis's analysis is the default scope for its {@code factRefs}.
   */
  UUID hypothesisAnalysisId(UUID hypothesisId);

  /** True when the source exists — the mandatory provenance of an evidence item. */
  boolean sourceExists(UUID sourceId);

  /** True when the analysis exists. */
  boolean analysisExists(UUID analysisId);

  /** True when the evidence item exists. */
  boolean evidenceExists(UUID evidenceId);

  /**
   * The fact ids carried by an analysis snapshot.
   *
   * <p>Used to check PR-09 ("a ref that cannot be resolved is a validation failure,
   * not a warning") without silently dropping the unresolvable refs.
   */
  Set<UUID> factIds(UUID analysisId);
}
