package com.signalframe.research.evidence;

import com.signalframe.research.domain.evidence.ResearchReferences;
import com.signalframe.shared.ApplicationException;
import java.util.*;

/**
 * In-memory stand-in for TASK-07's read-only reference lookups.
 *
 * <p>{@code hypotheses} always have an analysis and a version, mirroring the
 * {@code NOT NULL} columns; unknown ids raise the same {@code 404} the JDBC adapter
 * raises.
 */
class InMemoryResearchReferences implements ResearchReferences {

  final Set<UUID> hypotheses = new LinkedHashSet<>();
  final Map<UUID, UUID> hypothesisAnalysis = new HashMap<>();
  final Map<UUID, Long> versions = new HashMap<>();
  final Set<UUID> sources = new LinkedHashSet<>();
  final Set<UUID> analyses = new LinkedHashSet<>();
  final Set<UUID> evidenceIds = new LinkedHashSet<>();
  final Map<UUID, Set<UUID>> factsByAnalysis = new HashMap<>();

  /** Registers a hypothesis with its analysis, version and snapshot fact ids. */
  UUID addHypothesis(UUID analysisId, long version, Set<UUID> factIds) {
    UUID id = UUID.randomUUID();
    hypotheses.add(id);
    hypothesisAnalysis.put(id, analysisId);
    versions.put(id, version);
    analyses.add(analysisId);
    factsByAnalysis.put(analysisId, new LinkedHashSet<>(factIds));
    return id;
  }

  UUID addSource() {
    UUID id = UUID.randomUUID();
    sources.add(id);
    return id;
  }

  UUID addEvidence() {
    UUID id = UUID.randomUUID();
    evidenceIds.add(id);
    return id;
  }

  @Override
  public boolean hypothesisExists(UUID hypothesisId) {
    return hypotheses.contains(hypothesisId);
  }

  @Override
  public long hypothesisVersion(UUID hypothesisId) {
    Long version = versions.get(hypothesisId);
    if (version == null) throw ApplicationException.missing();
    return version;
  }

  @Override
  public UUID hypothesisAnalysisId(UUID hypothesisId) {
    UUID analysisId = hypothesisAnalysis.get(hypothesisId);
    if (analysisId == null) throw ApplicationException.missing();
    return analysisId;
  }

  @Override
  public boolean sourceExists(UUID sourceId) {
    return sources.contains(sourceId);
  }

  @Override
  public boolean analysisExists(UUID analysisId) {
    return analyses.contains(analysisId);
  }

  @Override
  public boolean evidenceExists(UUID evidenceId) {
    return evidenceIds.contains(evidenceId);
  }

  @Override
  public Set<UUID> factIds(UUID analysisId) {
    if (!analyses.contains(analysisId)) throw ApplicationException.missing();
    return Set.copyOf(factsByAnalysis.getOrDefault(analysisId, Set.of()));
  }
}
