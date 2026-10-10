package com.signalframe.research.evidence;

import com.signalframe.contract.Evidence;
import com.signalframe.contract.Prediction;
import com.signalframe.research.domain.evidence.EvidenceRepository;
import com.signalframe.research.domain.evidence.PredictionRepository;
import java.time.Instant;
import java.util.*;

/**
 * In-memory stand-in for TASK-07's persistence ports.
 *
 * <p>Used to test the application rules without a database. It reproduces the one
 * persistence behaviour the service depends on beyond storage: {@code resolve} only
 * succeeds on an {@code OPEN} prediction and carries every text field across
 * verbatim.
 */
class InMemoryEvidencePredictionRepository
  implements EvidenceRepository, PredictionRepository
{

  final Map<UUID, Evidence> evidenceRows = new LinkedHashMap<>();
  final Map<UUID, Prediction> predictionRows = new LinkedHashMap<>();
  int resolveCalls;

  @Override
  public Evidence save(Evidence evidence) {
    evidenceRows.put(evidence.id(), evidence);
    return evidence;
  }

  @Override
  public Optional<Evidence> findEvidence(UUID evidenceId) {
    return Optional.ofNullable(evidenceRows.get(evidenceId));
  }

  @Override
  public List<Evidence> evidenceFor(UUID hypothesisId) {
    return evidenceRows
      .values()
      .stream()
      .filter(e -> e.hypothesisId().equals(hypothesisId))
      .toList();
  }

  @Override
  public Prediction save(Prediction prediction) {
    predictionRows.put(prediction.id(), prediction);
    return prediction;
  }

  @Override
  public Optional<Prediction> findPrediction(UUID predictionId) {
    return Optional.ofNullable(predictionRows.get(predictionId));
  }

  @Override
  public List<Prediction> predictionsFor(UUID hypothesisId) {
    return predictionRows
      .values()
      .stream()
      .filter(p -> p.hypothesisId().equals(hypothesisId))
      .toList();
  }

  @Override
  public List<Prediction> dueOpen(Instant asOf, int limit) {
    return predictionRows
      .values()
      .stream()
      .filter(p -> "OPEN".equals(p.status()))
      .filter(p -> p.expectedBy().isBefore(asOf))
      .sorted(Comparator.comparing(Prediction::expectedBy))
      .limit(limit)
      .toList();
  }

  @Override
  public Optional<Prediction> resolve(
    UUID predictionId,
    String status,
    Instant verifiedAt
  ) {
    resolveCalls++;
    Prediction current = predictionRows.get(predictionId);
    if (current == null || !"OPEN".equals(current.status())) {
      return Optional.empty();
    }
    var resolved = new Prediction(
      current.id(),
      current.hypothesisId(),
      current.statement(),
      current.type(),
      current.expectedBy(),
      status,
      current.verificationCriteria(),
      current.observable(),
      current.whereToCheck(),
      current.basisFactRefs()
    );
    predictionRows.put(predictionId, resolved);
    return Optional.of(resolved);
  }
}
