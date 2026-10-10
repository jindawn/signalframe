package com.signalframe.research.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.Evidence;
import com.signalframe.contract.Prediction;
import com.signalframe.infrastructure.persistence.JdbcEvidencePredictionRepository;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL behaviour of TASK-07's granted JDBC adapter.
 *
 * <p>Real PostgreSQL 17.6 with the real Flyway migrations, reached through the
 * production {@link JsonCodec} (which enables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}). That strictness is the point of several
 * assertions here: the stored {@code payload} must remain exactly the generated
 * contract record, because {@code JdbcResearchRepository} — read-only for TASK-07 —
 * materialises it as {@link Evidence} / {@link Prediction} for
 * {@code GET /api/v1/hypotheses/{id}}.
 */
class JdbcEvidencePredictionRepositoryTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static final JsonCodec JSON = new JsonCodec();

  static JdbcTemplate db;
  static JdbcEvidencePredictionRepository repository;

  UUID analysisId;
  UUID hypothesisId;
  UUID sourceId;
  UUID factA;
  UUID factB;

  @BeforeAll
  static void startDatabase() {
    postgres.start();
    var dataSource = new DriverManagerDataSource(
      postgres.getJdbcUrl(),
      postgres.getUsername(),
      postgres.getPassword()
    );
    Flyway.configure()
      .dataSource(dataSource)
      .locations("classpath:db/migration")
      .load()
      .migrate();
    db = new JdbcTemplate(dataSource);
    repository = new JdbcEvidencePredictionRepository(db, JSON);
  }

  @BeforeEach
  void seedGraph() {
    // The due query is deliberately global (every hypothesis's overdue predictions), so
    // rows from another method would otherwise be visible to it. The container is shared
    // across the class, so the research tables are cleared between methods.
    db.update("DELETE FROM indicators");
    db.update("DELETE FROM predictions");
    db.update("DELETE FROM evidence");

    factA = UUID.randomUUID();
    factB = UUID.randomUUID();
    sourceId = UUID.randomUUID();
    UUID newsId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    analysisId = UUID.randomUUID();
    hypothesisId = UUID.randomUUID();

    db.update(
      "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES(?,?,?,?,?::jsonb,?)",
      sourceId,
      "https://example.test/article",
      "body",
      "PASTED",
      "{}",
      Timestamp.from(Instant.now())
    );
    db.update(
      "INSERT INTO news_items(id,source_id,payload,created_at) VALUES(?,?,?::jsonb,?)",
      newsId,
      sourceId,
      "{}",
      Timestamp.from(Instant.now())
    );
    db.update(
      "INSERT INTO analysis_jobs(id,news_id,status,correlation_id,created_at,updated_at) VALUES(?,?,?,?,?,?)",
      jobId,
      newsId,
      "COMPLETED",
      "correlation",
      Timestamp.from(Instant.now()),
      Timestamp.from(Instant.now())
    );
    db.update(
      "INSERT INTO analyses(id,news_id,job_id,payload,created_at) VALUES(?,?,?,?::jsonb,?)",
      analysisId,
      newsId,
      jobId,
      factsSnapshot(factA, factB),
      Timestamp.from(Instant.now())
    );
    db.update(
      "INSERT INTO hypotheses(id,analysis_id,payload,confidence,created_at,updated_at,version) VALUES(?,?,?::jsonb,?,?,?,?)",
      hypothesisId,
      analysisId,
      "{}",
      30,
      Timestamp.from(Instant.now()),
      Timestamp.from(Instant.now()),
      4
    );
  }

  // ---- evidence ---------------------------------------------------------

  @Test
  void evidenceRoundTripsAsTheGeneratedContractRecord() {
    var evidence = new Evidence(
      UUID.randomUUID(),
      hypothesisId,
      "CONTRADICTS",
      55,
      sourceId,
      "the filing discloses a lower figure",
      Instant.now(),
      List.of(factA),
      analysisId
    );

    assertEquals(evidence, repository.save(evidence));
    assertEquals(Optional.of(evidence), repository.findEvidence(evidence.id()));
    assertEquals(List.of(evidence), repository.evidenceFor(hypothesisId));
  }

  @Test
  void evidenceWithoutAnAnalysisOrDefaultFactRefsIsStillReadable() {
    var evidence = new Evidence(
      UUID.randomUUID(),
      hypothesisId,
      "NEUTRAL",
      0,
      sourceId,
      "context only",
      Instant.now(),
      null,
      null
    );

    repository.save(evidence);
    assertEquals(Optional.of(evidence), repository.findEvidence(evidence.id()));
  }

  @Test
  void anUnknownSourceIsRejectedByTheDatabaseForeignKey() {
    var orphan = new Evidence(
      UUID.randomUUID(),
      hypothesisId,
      "SUPPORTS",
      50,
      UUID.randomUUID(),
      "no such source",
      Instant.now(),
      List.of(),
      null
    );

    assertThrows(Exception.class, () -> repository.save(orphan));
    assertTrue(repository.findEvidence(orphan.id()).isEmpty());
  }

  @Test
  void anUnknownAnalysisIsRejectedByTheDatabaseForeignKey() {
    var orphan = new Evidence(
      UUID.randomUUID(),
      hypothesisId,
      "SUPPORTS",
      50,
      sourceId,
      "no such analysis",
      Instant.now(),
      List.of(),
      UUID.randomUUID()
    );

    assertThrows(Exception.class, () -> repository.save(orphan));
  }

  @Test
  void anUnknownHypothesisIsRejectedByTheDatabaseForeignKey() {
    var orphan = new Evidence(
      UUID.randomUUID(),
      UUID.randomUUID(),
      "SUPPORTS",
      50,
      sourceId,
      "no such hypothesis",
      Instant.now(),
      List.of(),
      null
    );

    assertThrows(Exception.class, () -> repository.save(orphan));
  }

  // ---- predictions ------------------------------------------------------

  @Test
  void aPredictionRoundTripsAndIsCreatedOpen() {
    Prediction prediction = openPrediction(Instant.now().plusSeconds(3600));
    repository.save(prediction);

    var stored = repository.findPrediction(prediction.id()).orElseThrow();
    assertEquals(prediction, stored);
    assertEquals("OPEN", stored.status());
    assertEquals(List.of(prediction), repository.predictionsFor(hypothesisId));
    assertNull(
      db.queryForObject(
        "SELECT verified_at FROM predictions WHERE id=?",
        Timestamp.class,
        prediction.id()
      ),
      "a prediction is not verified at creation"
    );
  }

  @Test
  void theDatabaseRejectsAStatusOutsideTheWidenedVocabulary() {
    assertThrows(Exception.class, () ->
      db.update(
        "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES(?,?,?,'BOGUS','{}'::jsonb)",
        UUID.randomUUID(),
        hypothesisId,
        Timestamp.from(Instant.now().plusSeconds(60))
      )
    );
  }

  @Test
  void dueReturnsOnlyStrictlyOverdueOpenPredictions() {
    Instant asOf = Instant.parse("2026-06-01T12:00:00Z");
    Prediction overdue = insertPrediction(
      asOf.minusSeconds(1),
      "OPEN",
      "overdue"
    );
    Prediction exactlyAtDeadline = insertPrediction(asOf, "OPEN", "exactly at");
    Prediction future = insertPrediction(asOf.plusSeconds(1), "OPEN", "future");
    Prediction decidedPast = insertPrediction(
      asOf.minusSeconds(1),
      "CONFIRMED",
      "already decided"
    );

    List<UUID> due = repository
      .dueOpen(asOf, 100)
      .stream()
      .map(Prediction::id)
      .toList();

    assertEquals(List.of(overdue.id()), due);
    assertFalse(due.contains(exactlyAtDeadline.id()), "the boundary is strict");
    assertFalse(due.contains(future.id()));
    assertFalse(due.contains(decidedPast.id()));
  }

  @Test
  void dueIsOrderedByDeadlineAndHonoursTheLimit() {
    Instant asOf = Instant.parse("2026-06-01T12:00:00Z");
    Prediction later = insertPrediction(asOf.minusSeconds(10), "OPEN", "later");
    Prediction earlier = insertPrediction(asOf.minusSeconds(100), "OPEN", "earlier");

    assertEquals(
      List.of(earlier.id(), later.id()),
      repository.dueOpen(asOf, 100).stream().map(Prediction::id).toList()
    );
    assertEquals(
      List.of(earlier.id()),
      repository.dueOpen(asOf, 1).stream().map(Prediction::id).toList()
    );
  }

  @Test
  void aLegacyPredictionRowDoesNotBreakTheDueQueryAndIsNeverReported() {
    // V1-era row: the pre-freeze CHECK could not hold OPEN and the payload predates the
    // SCH-07 shape, so it is unreadable as a Prediction. It must not surface as due.
    UUID legacy = UUID.randomUUID();
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES(?,?,?,'CONFIRMED','{\"legacy\":true}'::jsonb)",
      legacy,
      hypothesisId,
      Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"))
    );

    assertTrue(repository.dueOpen(Instant.now(), 100).isEmpty());
  }

  @Test
  void resolveRecordsTheOutcomeAndLeavesThePredictionTextUntouched() {
    Prediction open = openPrediction(Instant.now().plusSeconds(3600));
    repository.save(open);
    Instant decidedAt = Instant.parse("2026-07-01T08:15:00Z");

    var resolved = repository
      .resolve(open.id(), "PARTIAL", decidedAt)
      .orElseThrow();

    assertEquals(open.statement(), resolved.statement());
    assertEquals(open.observable(), resolved.observable());
    assertEquals(open.expectedBy(), resolved.expectedBy());
    assertEquals(open.verificationCriteria(), resolved.verificationCriteria());
    assertEquals(open.whereToCheck(), resolved.whereToCheck());
    assertEquals(open.type(), resolved.type());
    assertEquals(open.basisFactRefs(), resolved.basisFactRefs());
    assertEquals(open.hypothesisId(), resolved.hypothesisId());
    assertEquals("PARTIAL", resolved.status());

    assertEquals(resolved, repository.findPrediction(open.id()).orElseThrow());
    assertEquals(
      decidedAt,
      db.queryForObject(
        "SELECT verified_at FROM predictions WHERE id=?",
        Timestamp.class,
        open.id()
      ).toInstant()
    );
  }

  @Test
  void aPredictionIsResolvedExactlyOnce() {
    Prediction open = openPrediction(Instant.now().plusSeconds(3600));
    repository.save(open);

    assertTrue(repository.resolve(open.id(), "CONFIRMED", Instant.now()).isPresent());
    assertTrue(
      repository.resolve(open.id(), "REJECTED", Instant.now()).isEmpty(),
      "a decided prediction is not decided twice"
    );
    assertEquals(
      "CONFIRMED",
      repository.findPrediction(open.id()).orElseThrow().status()
    );
  }

  @Test
  void resolvingAnUnknownPredictionIsEmpty() {
    assertTrue(repository.resolve(UUID.randomUUID(), "CONFIRMED", Instant.now()).isEmpty());
  }

  // ---- read-only references --------------------------------------------

  @Test
  void referencesAnswerExistenceAndTheOptimisticLockVersion() {
    assertTrue(repository.hypothesisExists(hypothesisId));
    assertFalse(repository.hypothesisExists(UUID.randomUUID()));
    assertEquals(4L, repository.hypothesisVersion(hypothesisId));
    assertEquals(analysisId, repository.hypothesisAnalysisId(hypothesisId));
    assertTrue(repository.sourceExists(sourceId));
    assertFalse(repository.sourceExists(UUID.randomUUID()));
    assertTrue(repository.analysisExists(analysisId));
    assertFalse(repository.analysisExists(UUID.randomUUID()));
    assertFalse(repository.evidenceExists(UUID.randomUUID()));
  }

  @Test
  void factIdsAreReadFromTheSnapshotPayload() {
    assertEquals(Set.of(factA, factB), repository.factIds(analysisId));
  }

  @Test
  void aSnapshotWithoutAFactsArrayYieldsTheEmptySetRatherThanFailing() {
    UUID legacyNews = db.queryForObject(
      "SELECT news_id FROM analyses WHERE id=?",
      UUID.class,
      analysisId
    );
    UUID jobId = UUID.randomUUID();
    UUID legacyAnalysis = UUID.randomUUID();
    db.update(
      "INSERT INTO analysis_jobs(id,news_id,status,correlation_id,created_at,updated_at) VALUES(?,?,?,?,?,?)",
      jobId,
      legacyNews,
      "COMPLETED",
      "correlation",
      Timestamp.from(Instant.now()),
      Timestamp.from(Instant.now())
    );
    db.update(
      "INSERT INTO analyses(id,news_id,job_id,payload,created_at) VALUES(?,?,?,?::jsonb,?)",
      legacyAnalysis,
      legacyNews,
      jobId,
      "{\"legacy\":true}",
      Timestamp.from(Instant.now())
    );

    assertTrue(repository.factIds(legacyAnalysis).isEmpty());
  }

  @Test
  void factIdsForAnUnknownAnalysisIsNotFound() {
    var failure = assertThrows(
      com.signalframe.shared.ApplicationException.class,
      () -> repository.factIds(UUID.randomUUID())
    );
    assertEquals(404, failure.status());
  }

  // ---- helpers ----------------------------------------------------------

  static String factsSnapshot(UUID... factIds) {
    StringBuilder json = new StringBuilder("{\"facts\":[");
    for (int i = 0; i < factIds.length; i++) {
      if (i > 0) json.append(',');
      json.append("{\"id\":\"").append(factIds[i]).append("\"}");
    }
    return json.append("]}").toString();
  }

  Prediction openPrediction(Instant expectedBy) {
    return new Prediction(
      UUID.randomUUID(),
      hypothesisId,
      "subscriber growth will reverse",
      "PREDICTION",
      expectedBy,
      "OPEN",
      "the disclosed figure falls below 4.0 million in the Q3 filing",
      "quarterly subscriber count",
      "the Q3 filing on the company investor-relations site",
      List.of()
    );
  }

  Prediction insertPrediction(Instant expectedBy, String status, String label) {
    Prediction prediction = new Prediction(
      UUID.randomUUID(),
      hypothesisId,
      label,
      "PREDICTION",
      expectedBy,
      status,
      "criteria that distinguish confirmation from rejection: " + label,
      "observable " + label,
      "where to check " + label,
      List.of()
    );
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES(?,?,?,?,?::jsonb)",
      prediction.id(),
      hypothesisId,
      Timestamp.from(expectedBy),
      status,
      JSON.write(prediction)
    );
    return prediction;
  }
}
