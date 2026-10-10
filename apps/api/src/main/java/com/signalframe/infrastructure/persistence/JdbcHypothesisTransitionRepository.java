package com.signalframe.infrastructure.persistence;

import com.signalframe.contract.Analysis;
import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.research.domain.hypotheses.EvidenceStance;
import com.signalframe.research.domain.hypotheses.HypothesisSnapshot;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionRepository;
import com.signalframe.research.domain.hypotheses.StoredEvidence;
import com.signalframe.research.domain.hypotheses.StoredHypothesis;
import com.signalframe.research.domain.hypotheses.StoredPrediction;
import com.signalframe.shared.ApplicationException;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL adapter for {@link HypothesisTransitionRepository} (TASK-06's one
 * granted persistence file).
 *
 * <p>It owns exactly the statements a transition needs and nothing else. The
 * hypothesis row is read with {@code SELECT ... FOR UPDATE}, which is the
 * optimistic-concurrency mechanism: the service opens the transaction, this class
 * takes the row lock, and a second concurrent transition of the same hypothesis
 * therefore observes the version the first one committed and reports a conflict
 * instead of overwriting its event.
 *
 * <p>Two reads deliberately avoid JSONB. Evidence and predictions are read from
 * their columns only ({@code stance}, {@code source_id}, {@code status},
 * {@code verified_at}), so hypothesis scoring cannot depend on the shape of
 * TASK-07's payload, and a change to that payload cannot break this task. The
 * snapshot is read from {@code analyses.payload} because that is where the
 * immutable analysis artifacts live and PR-17 forbids rewriting them.
 *
 * <p>The existing {@code JdbcResearchRepository} keeps its own read path; this
 * class adds no behaviour to it and changes no byte of it.
 */
@Repository
public class JdbcHypothesisTransitionRepository
  implements HypothesisTransitionRepository
{

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcHypothesisTransitionRepository(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  @Override
  public Optional<StoredHypothesis> lockById(UUID hypothesisId) {
    return row(
      "SELECT payload::text, confidence, version FROM hypotheses WHERE id=? FOR UPDATE",
      hypothesisId
    );
  }

  @Override
  public Optional<StoredHypothesis> findById(UUID hypothesisId) {
    return row(
      "SELECT payload::text, confidence, version FROM hypotheses WHERE id=?",
      hypothesisId
    );
  }

  private Optional<StoredHypothesis> row(String sql, UUID hypothesisId) {
    return db
      .query(
        sql,
        (rs, n) -> {
          String stored = rs.getString(1);
          Hypothesis payload;
          try {
            payload = json.read(stored, Hypothesis.class);
          } catch (RuntimeException e) {
            // JsonCodec fails on unknown properties, so a payload this task cannot
            // read is a row no transition can be derived from. Refusing it as
            // unprocessable is honest; a 500 would blame the server, and guessing
            // at the status would invent state.
            throw new ApplicationException(
              422,
              "UNPROCESSABLE_TRANSITION",
              "The stored hypothesis payload is not a readable hypothesis, so no transition can be applied."
            );
          }
          return new StoredHypothesis(
            hypothesisId,
            payload,
            rs.getInt(2),
            rs.getLong(3)
          );
        },
        hypothesisId
      )
      .stream()
      .findFirst();
  }

  @Override
  public Optional<HypothesisEvent> event(UUID eventId) {
    return db
      .query(
        "SELECT payload::text FROM hypothesis_events WHERE id=?",
        (rs, n) -> json.read(rs.getString(1), HypothesisEvent.class),
        eventId
      )
      .stream()
      .findFirst();
  }

  /**
   * Oldest first. {@code created_at} is the primary order — the same order the
   * existing read path uses — and {@code id} breaks a tie so the sequence is total
   * even for events written before this task made timestamps strictly increasing.
   */
  @Override
  public List<HypothesisEvent> timeline(UUID hypothesisId) {
    return db.query(
      "SELECT payload::text FROM hypothesis_events WHERE hypothesis_id=? ORDER BY created_at, id",
      (rs, n) -> json.read(rs.getString(1), HypothesisEvent.class),
      hypothesisId
    );
  }

  @Override
  public Optional<HypothesisSnapshot> snapshot(UUID hypothesisId) {
    var rows = db.query(
      "SELECT a.payload::text AS payload, n.source_id AS source_id" +
      " FROM hypotheses h" +
      " JOIN analyses a ON a.id = h.analysis_id" +
      " JOIN news_items n ON n.id = a.news_id" +
      " WHERE h.id = ?",
      (rs, n) -> new Object[] {
        rs.getString("payload"),
        rs.getObject("source_id", UUID.class),
      },
      hypothesisId
    );
    if (rows.isEmpty()) return Optional.empty();
    return scorableSnapshot((String) rows.getFirst()[0]).map(result ->
      new HypothesisSnapshot(result, (UUID) rows.getFirst()[1])
    );
  }

  /**
   * The snapshot of a stored analysis payload, or empty when the payload is not a
   * readable protocol snapshot.
   *
   * <p>A legacy row is expected here, not exceptional: {@code JsonCodec} fails on
   * unknown properties, so a pre-protocol payload cannot be read as the current
   * contract. Reading it as "no scorable snapshot" is what lets the scorer take
   * its CF-06 fail-closed branch and carry the stored number unchanged, instead of
   * failing the request or inventing a score.
   */
  private Optional<AnalysisResult> scorableSnapshot(String payload) {
    if (payload == null) return Optional.empty();
    try {
      var analysis = json.read(payload, Analysis.class);
      return analysis == null || analysis.result() == null
        ? Optional.empty()
        : Optional.of(analysis.result());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<StoredEvidence> evidenceFor(UUID hypothesisId) {
    return db.query(
      "SELECT id, hypothesis_id, source_id, stance FROM evidence WHERE hypothesis_id=? ORDER BY created_at, id",
      JdbcHypothesisTransitionRepository::evidence,
      hypothesisId
    );
  }

  @Override
  public Optional<StoredEvidence> evidence(UUID evidenceId) {
    return db
      .query(
        "SELECT id, hypothesis_id, source_id, stance FROM evidence WHERE id=?",
        JdbcHypothesisTransitionRepository::evidence,
        evidenceId
      )
      .stream()
      .findFirst();
  }

  private static StoredEvidence evidence(
    java.sql.ResultSet rs,
    int rowNumber
  ) throws java.sql.SQLException {
    return new StoredEvidence(
      rs.getObject("id", UUID.class),
      rs.getObject("hypothesis_id", UUID.class),
      rs.getObject("source_id", UUID.class),
      EvidenceStance.parse(rs.getString("stance"))
    );
  }

  @Override
  public Optional<StoredPrediction> prediction(UUID predictionId) {
    return db
      .query(
        "SELECT id, hypothesis_id, status, verified_at FROM predictions WHERE id=?",
        JdbcHypothesisTransitionRepository::prediction,
        predictionId
      )
      .stream()
      .findFirst();
  }

  @Override
  public List<StoredPrediction> predictionsFor(UUID hypothesisId) {
    return db.query(
      "SELECT id, hypothesis_id, status, verified_at FROM predictions WHERE hypothesis_id=? ORDER BY expected_by, id",
      JdbcHypothesisTransitionRepository::prediction,
      hypothesisId
    );
  }

  private static StoredPrediction prediction(
    java.sql.ResultSet rs,
    int rowNumber
  ) throws java.sql.SQLException {
    return new StoredPrediction(
      rs.getObject("id", UUID.class),
      rs.getObject("hypothesis_id", UUID.class),
      rs.getString("status"),
      instant(rs.getObject("verified_at", OffsetDateTime.class))
    );
  }

  @Override
  public Optional<Instant> latestEventAt(UUID hypothesisId) {
    // A bare `max()` always returns one row, NULL when there is no event; taking
    // it through a list avoids the null-hostile Optional.of of findFirst().
    var rows = db.query(
      "SELECT max(created_at) AS latest FROM hypothesis_events WHERE hypothesis_id=?",
      (rs, n) -> rs.getObject("latest", OffsetDateTime.class),
      hypothesisId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.ofNullable(instant(rows.getFirst()));
  }

  @Override
  public void append(HypothesisEvent event) {
    db.update(
      "INSERT INTO hypothesis_events(id, hypothesis_id, payload, created_at) VALUES(?,?,?::jsonb,?)",
      event.id(),
      event.hypothesisId(),
      json.write(event),
      Timestamp.from(event.createdAt())
    );
  }

  @Override
  public boolean update(
    UUID hypothesisId,
    Hypothesis payload,
    int confidence,
    Instant updatedAt,
    long expectedVersion
  ) {
    return (
      db.update(
        "UPDATE hypotheses SET payload=?::jsonb, confidence=?, version=version+1, updated_at=?" +
        " WHERE id=? AND version=?",
        json.write(payload),
        confidence,
        Timestamp.from(updatedAt),
        hypothesisId,
        expectedVersion
      ) == 1
    );
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
