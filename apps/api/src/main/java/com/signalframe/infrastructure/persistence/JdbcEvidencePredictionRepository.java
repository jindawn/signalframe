package com.signalframe.infrastructure.persistence;

import com.signalframe.contract.Evidence;
import com.signalframe.contract.Prediction;
import com.signalframe.research.domain.evidence.EvidenceRepository;
import com.signalframe.research.domain.evidence.PredictionRepository;
import com.signalframe.research.domain.evidence.ResearchReferences;
import com.signalframe.shared.ApplicationException;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL adapter for TASK-07's evidence and prediction ports.
 *
 * <p>Two invariants of the stored row shape are load-bearing and must not drift.
 *
 * <ol>
 *   <li><b>{@code payload} is exactly the generated contract record.</b>
 *       {@code JdbcResearchRepository} (integrator-owned, read-only for TASK-07)
 *       materialises {@code evidence.payload} as {@link Evidence} and
 *       {@code predictions.payload} as {@link Prediction} through a
 *       {@link JsonCodec} that enables {@code FAIL_ON_UNKNOWN_PROPERTIES}. Any extra
 *       key written here would break {@code GET /api/v1/hypotheses/{id}}.
 *   <li><b>Verification changes status and timestamp only.</b> {@link #resolve}
 *       rebuilds the stored {@link Prediction} from its own fields, so statement,
 *       observable, deadline and criteria are carried across byte for byte
 *       (STG-14.3, PR-17).
 * </ol>
 *
 * <p>The {@code hypotheses} and {@code sources} reads below are the read-only
 * dependencies declared for TASK-07. Nothing in this class writes a hypothesis.
 */
@Repository
public class JdbcEvidencePredictionRepository
  implements EvidenceRepository, PredictionRepository, ResearchReferences
{

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcEvidencePredictionRepository(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  // ---- evidence ---------------------------------------------------------

  @Override
  public Evidence save(Evidence evidence) {
    db.update(
      "INSERT INTO evidence(id,hypothesis_id,source_id,stance,strength,payload,created_at,analysis_id)" +
      " VALUES(?,?,?,?,?,?::jsonb,?,?::uuid)",
      evidence.id(),
      evidence.hypothesisId(),
      evidence.sourceId(),
      evidence.stance(),
      evidence.strength(),
      json.write(evidence),
      Timestamp.from(evidence.createdAt()),
      evidence.analysisId()
    );
    return evidence;
  }

  @Override
  public Optional<Evidence> findEvidence(UUID evidenceId) {
    return db
      .query(
        "SELECT payload::text FROM evidence WHERE id=?",
        (rs, n) -> json.read(rs.getString(1), Evidence.class),
        evidenceId
      )
      .stream()
      .findFirst();
  }

  @Override
  public List<Evidence> evidenceFor(UUID hypothesisId) {
    return db.query(
      "SELECT payload::text FROM evidence WHERE hypothesis_id=? ORDER BY created_at, id",
      (rs, n) -> json.read(rs.getString(1), Evidence.class),
      hypothesisId
    );
  }

  // ---- predictions ------------------------------------------------------

  @Override
  public Prediction save(Prediction prediction) {
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES(?,?,?,?,?::jsonb)",
      prediction.id(),
      prediction.hypothesisId(),
      Timestamp.from(prediction.expectedBy()),
      prediction.status(),
      json.write(prediction)
    );
    return prediction;
  }

  @Override
  public Optional<Prediction> findPrediction(UUID predictionId) {
    return db
      .query(
        "SELECT payload::text FROM predictions WHERE id=?",
        (rs, n) -> json.read(rs.getString(1), Prediction.class),
        predictionId
      )
      .stream()
      .findFirst();
  }

  @Override
  public List<Prediction> predictionsFor(UUID hypothesisId) {
    return db.query(
      "SELECT payload::text FROM predictions WHERE hypothesis_id=? ORDER BY expected_by, id",
      (rs, n) -> json.read(rs.getString(1), Prediction.class),
      hypothesisId
    );
  }

  @Override
  public List<Prediction> dueOpen(Instant asOf, int limit) {
    // `expected_by < asOf` is strict: a deadline equal to the instant being asked
    // about has not passed yet. Only OPEN rows are read — a passed deadline is
    // reported, never resolved automatically.
    return db.query(
      "SELECT payload::text FROM predictions" +
      " WHERE status='OPEN' AND expected_by < ? ORDER BY expected_by, id LIMIT ?",
      (rs, n) -> json.read(rs.getString(1), Prediction.class),
      Timestamp.from(asOf),
      limit
    );
  }

  @Override
  public Optional<Prediction> resolve(
    UUID predictionId,
    String status,
    Instant verifiedAt
  ) {
    List<String> rows = db.query(
      "SELECT payload::text FROM predictions WHERE id=? FOR UPDATE",
      (rs, n) -> rs.getString(1),
      predictionId
    );
    if (rows.isEmpty()) return Optional.empty();
    Prediction current = json.read(rows.get(0), Prediction.class);
    if (!"OPEN".equals(current.status())) return Optional.empty();

    // Every text field is carried across verbatim; only the outcome changes.
    Prediction resolved = new Prediction(
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
    int updated = db.update(
      "UPDATE predictions SET status=?, verified_at=?, payload=?::jsonb" +
      " WHERE id=? AND status='OPEN'",
      status,
      Timestamp.from(verifiedAt),
      json.write(resolved),
      predictionId
    );
    return updated == 1 ? Optional.of(resolved) : Optional.empty();
  }

  // ---- read-only references --------------------------------------------

  @Override
  public boolean hypothesisExists(UUID hypothesisId) {
    if (hypothesisId == null) return false;
    return !db.query(
      "SELECT 1 FROM hypotheses WHERE id=?",
      (rs, n) -> rs.getInt(1),
      hypothesisId
    ).isEmpty();
  }

  @Override
  public long hypothesisVersion(UUID hypothesisId) {
    return db
      .query(
        "SELECT version FROM hypotheses WHERE id=?",
        (rs, n) -> rs.getLong(1),
        hypothesisId
      )
      .stream()
      .findFirst()
      .orElseThrow(ApplicationException::missing);
  }

  @Override
  public UUID hypothesisAnalysisId(UUID hypothesisId) {
    return db
      .query(
        "SELECT analysis_id FROM hypotheses WHERE id=?",
        (rs, n) -> rs.getObject(1, UUID.class),
        hypothesisId
      )
      .stream()
      .findFirst()
      .orElseThrow(ApplicationException::missing);
  }

  @Override
  public boolean sourceExists(UUID sourceId) {
    if (sourceId == null) return false;
    return !db.query(
      "SELECT 1 FROM sources WHERE id=?",
      (rs, n) -> rs.getInt(1),
      sourceId
    ).isEmpty();
  }

  @Override
  public boolean analysisExists(UUID analysisId) {
    if (analysisId == null) return false;
    return !db.query(
      "SELECT 1 FROM analyses WHERE id=?",
      (rs, n) -> rs.getInt(1),
      analysisId
    ).isEmpty();
  }

  @Override
  public boolean evidenceExists(UUID evidenceId) {
    if (evidenceId == null) return false;
    return !db.query(
      "SELECT 1 FROM evidence WHERE id=?",
      (rs, n) -> rs.getInt(1),
      evidenceId
    ).isEmpty();
  }

  @Override
  public Set<UUID> factIds(UUID analysisId) {
    if (analysisId == null || !analysisExists(analysisId)) {
      throw ApplicationException.missing();
    }
    // The stored snapshot is the whole `Analysis` record, so its facts live at
    // `result.facts`, not at the payload root: `JdbcAnalysisRepository` writes
    // `json.write(analysis)`. Reading the root yields the empty set for every real
    // row, and PR-09 then rejects every evidence item that cites a fact — which is
    // exactly what the Wave 2B integration gate observed. The CASE keeps the lateral
    // function total for a legacy or incomplete payload.
    return new LinkedHashSet<>(
      db.query(
        "SELECT value->>'id' FROM analyses" +
        " CROSS JOIN LATERAL jsonb_array_elements(" +
        "   CASE WHEN jsonb_typeof(payload->'result'->'facts') = 'array'" +
        "        THEN payload->'result'->'facts' ELSE '[]'::jsonb END) AS value" +
        " WHERE analyses.id=?",
        (rs, n) -> parseUuid(rs.getString(1)),
        analysisId
      )
        .stream()
        .filter(Objects::nonNull)
        .toList()
    );
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException malformedId) {
      return null;
    }
  }
}
