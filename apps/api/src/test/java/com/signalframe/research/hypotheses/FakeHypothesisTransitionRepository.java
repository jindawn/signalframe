package com.signalframe.research.hypotheses;

import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.research.domain.hypotheses.HypothesisSnapshot;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionRepository;
import com.signalframe.research.domain.hypotheses.StoredEvidence;
import com.signalframe.research.domain.hypotheses.StoredHypothesis;
import com.signalframe.research.domain.hypotheses.StoredPrediction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@link HypothesisTransitionRepository} for the transition rules.
 *
 * <p>The point of these tests is the engine's logic, not SQL, so the fake mirrors
 * the one behaviour the real adapter guarantees: {@link #update} applies only when
 * the stored version still equals {@code expectedVersion}, and it bumps the
 * version by exactly one. The append-only event list and the primary-key check in
 * {@link #append} mirror the real {@code hypothesis_events} table. The PostgreSQL
 * adapter's own behaviour is covered separately by the integration test.
 */
final class FakeHypothesisTransitionRepository
  implements HypothesisTransitionRepository
{

  final Map<UUID, StoredHypothesis> hypotheses = new LinkedHashMap<>();
  final List<HypothesisEvent> events = new ArrayList<>();
  final Map<UUID, StoredEvidence> evidence = new LinkedHashMap<>();
  final Map<UUID, StoredPrediction> predictions = new LinkedHashMap<>();
  HypothesisSnapshot snapshot;

  /** Counts writes so a test can assert that a refused transition wrote nothing. */
  int appended;
  int updated;

  void put(UUID hypothesisId, UUID analysisId, Hypothesis payload, int version) {
    hypotheses.put(
      hypothesisId,
      new StoredHypothesis(hypothesisId, payload, payload.confidence(), version)
    );
  }

  void putEvidence(StoredEvidence row) {
    evidence.put(row.id(), row);
  }

  void putPrediction(StoredPrediction row) {
    predictions.put(row.id(), row);
  }

  @Override
  public Optional<StoredHypothesis> lockById(UUID hypothesisId) {
    return Optional.ofNullable(hypotheses.get(hypothesisId));
  }

  @Override
  public Optional<StoredHypothesis> findById(UUID hypothesisId) {
    return lockById(hypothesisId);
  }

  @Override
  public Optional<HypothesisEvent> event(UUID eventId) {
    return events.stream().filter(e -> e.id().equals(eventId)).findFirst();
  }

  @Override
  public List<HypothesisEvent> timeline(UUID hypothesisId) {
    return events
      .stream()
      .filter(e -> e.hypothesisId().equals(hypothesisId))
      .sorted(Comparator.comparing(HypothesisEvent::createdAt))
      .toList();
  }

  @Override
  public Optional<HypothesisSnapshot> snapshot(UUID hypothesisId) {
    return Optional.ofNullable(snapshot);
  }

  @Override
  public List<StoredEvidence> evidenceFor(UUID hypothesisId) {
    return evidence
      .values()
      .stream()
      .filter(e -> e.hypothesisId().equals(hypothesisId))
      .toList();
  }

  @Override
  public Optional<StoredEvidence> evidence(UUID evidenceId) {
    return Optional.ofNullable(evidence.get(evidenceId));
  }

  @Override
  public Optional<StoredPrediction> prediction(UUID predictionId) {
    return Optional.ofNullable(predictions.get(predictionId));
  }

  @Override
  public List<StoredPrediction> predictionsFor(UUID hypothesisId) {
    return predictions
      .values()
      .stream()
      .filter(p -> p.hypothesisId().equals(hypothesisId))
      .toList();
  }

  @Override
  public Optional<Instant> latestEventAt(UUID hypothesisId) {
    return timeline(hypothesisId)
      .stream()
      .map(HypothesisEvent::createdAt)
      .max(Comparator.naturalOrder());
  }

  @Override
  public void append(HypothesisEvent event) {
    if (events.stream().anyMatch(e -> e.id().equals(event.id()))) {
      throw new IllegalStateException(
        "duplicate hypothesis_events primary key " + event.id()
      );
    }
    events.add(event);
    appended++;
  }

  @Override
  public boolean update(
    UUID hypothesisId,
    Hypothesis payload,
    int confidence,
    Instant updatedAt,
    long expectedVersion
  ) {
    var current = hypotheses.get(hypothesisId);
    if (current == null || current.version() != expectedVersion) return false;
    hypotheses.put(
      hypothesisId,
      new StoredHypothesis(
        hypothesisId,
        payload,
        confidence,
        expectedVersion + 1
      )
    );
    updated++;
    return true;
  }
}
