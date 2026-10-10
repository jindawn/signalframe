package com.signalframe.research.hypotheses;

import static com.signalframe.research.hypotheses.HypothesisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.Analysis;
import com.signalframe.contract.ClaimType;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTimeline;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;
import com.signalframe.contract.ApiError;
import com.signalframe.shared.JsonCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The TASK-06 acceptance surface that only a real database can show.
 *
 * <p>Covers the criteria the unit tests cannot: a transition commits status,
 * confidence, version and timeline event atomically; two concurrent submissions of
 * the same version produce exactly one winner and one {@code 409}; a failure inside
 * the transaction rolls the whole write back; a stale version and an illegal
 * transition leave the row untouched; legacy rows stay readable and are never
 * rewritten; and the two allocated HTTP routes answer with the frozen status and
 * error codes.
 *
 * <p>Every fixture is inserted with raw SQL through {@link JdbcTemplate} so the test
 * depends on the real schema and the real migrations rather than on a builder that
 * could drift from them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HypothesisTransitionIntegrationTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort
  int port;

  @Autowired
  JdbcTemplate db;

  @Autowired
  JsonCodec json;

  @Autowired
  com.signalframe.research.application.hypotheses.HypothesisTransitionService transitions;

  @Autowired
  PlatformTransactionManager transactionManager;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  // ---- HTTP plumbing -----------------------------------------------------

  private HttpResponse<String> post(String path, Object body) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.write(body)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  // ---- fixtures ----------------------------------------------------------

  private record Seeded(UUID sourceId, UUID analysisId, UUID hypothesisId) {}

  /** Inserts source -> news -> job -> analysis -> hypothesis -> CREATED event. */
  private Seeded seed(String publisher, HypothesisStatus status, int confidence) {
    var sourceId = UUID.randomUUID();
    var newsId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var analysisId = UUID.randomUUID();
    var hypothesisId = UUID.randomUUID();
    var condition = falsificationCondition(
      "if the reported margin recovers without an input-cost movement",
      "the reported unit margin",
      "compare with the pre-decline quarter at the same definition",
      "below 30 percent for two consecutive quarters"
    );
    var factId = UUID.randomUUID();
    var result = snapshot(
      source(publisher, SNAPSHOT_AT, "PRIMARY", "SINGLE_SOURCE", "COMPLETE"),
      List.of(fact(factId, sourceId, "unit margin fell 30 percent on higher input cost")),
      List.of(mechanism(UUID.randomUUID(), List.of(factId), "PLAUSIBLE")),
      List.of(counterArgument(hypothesisId, List.of(factId))),
      List.of(condition),
      List.of(),
      List.of(planItem(hypothesisId))
    );
    var analysis = new Analysis(
      analysisId,
      newsId,
      jobId,
      null,
      null,
      result,
      SNAPSHOT_AT
    );
    var hypothesis = new Hypothesis(
      hypothesisId,
      "input costs squeezed unit margin",
      "input costs squeezed unit margin",
      status,
      PREVIOUS_RENDERING,
      SNAPSHOT_AT,
      SNAPSHOT_AT,
      ClaimType.HYPOTHESIS,
      "input costs squeezed unit margin",
      "the reported margin decline is consistent with an input-cost squeeze",
      confidence,
      List.of(),
      List.of(factId),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(condition),
      null
    );

    db.update(
      "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES (?,?,?,?,?::jsonb,?)",
      sourceId,
      "https://" + publisher + "/a",
      "unit margin fell 30 percent on higher input cost",
      "PASTED",
      "{}",
      Timestamp.from(SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO news_items(id,source_id,payload,created_at) VALUES (?,?,?::jsonb,?)",
      newsId,
      sourceId,
      "{}",
      Timestamp.from(SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO analysis_jobs(id,news_id,status,correlation_id,created_at,updated_at) VALUES (?,?,?,?,?,?)",
      jobId,
      newsId,
      "COMPLETED",
      "task-06-test",
      Timestamp.from(SNAPSHOT_AT),
      Timestamp.from(SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO analyses(id,news_id,job_id,payload,created_at) VALUES (?,?,?,?::jsonb,?)",
      analysisId,
      newsId,
      jobId,
      json.write(analysis),
      Timestamp.from(SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO hypotheses(id,analysis_id,payload,confidence,created_at,updated_at,version) VALUES (?,?,?::jsonb,?,?,?,0)",
      hypothesisId,
      analysisId,
      json.write(hypothesis),
      confidence,
      Timestamp.from(SNAPSHOT_AT),
      Timestamp.from(SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO hypothesis_events(id,hypothesis_id,payload,created_at) VALUES (?,?,?::jsonb,?)",
      UUID.randomUUID(),
      hypothesisId,
      json.write(
        new HypothesisEvent(
          UUID.randomUUID(),
          hypothesisId,
          "CREATED",
          null,
          confidence,
          PREVIOUS_RENDERING,
          SNAPSHOT_AT,
          null,
          status
        )
      ),
      Timestamp.from(SNAPSHOT_AT)
    );
    return new Seeded(sourceId, analysisId, hypothesisId);
  }

  /** An evidence row, optionally from a source of its own (independent). */
  private UUID seedEvidence(Seeded seeded, String stance, boolean independentSource) {
    var sourceId = independentSource ? UUID.randomUUID() : seeded.sourceId();
    if (independentSource) {
      db.update(
        "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES (?,?,?,?,?::jsonb,?)",
        sourceId,
        "https://independent.test/b",
        "an independent record",
        "PASTED",
        "{}",
        Timestamp.from(SNAPSHOT_AT)
      );
    }
    var evidenceId = UUID.randomUUID();
    db.update(
      "INSERT INTO evidence(id,hypothesis_id,source_id,stance,strength,payload,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
      evidenceId,
      seeded.hypothesisId(),
      sourceId,
      stance,
      60,
      "{}",
      Timestamp.from(SNAPSHOT_AT)
    );
    return evidenceId;
  }

  private UUID seedVerifiedPrediction(Seeded seeded, String status) {
    var predictionId = UUID.randomUUID();
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload,verified_at) VALUES (?,?,?,?,?::jsonb,?)",
      predictionId,
      seeded.hypothesisId(),
      Timestamp.from(SNAPSHOT_AT.plusSeconds(86_400 * 30)),
      status,
      "{}",
      Timestamp.from(Instant.now())
    );
    return predictionId;
  }

  private HypothesisTransitionCommand evidenceCommand(
    UUID evidenceId,
    long expectedVersion,
    UUID operationId
  ) {
    return new HypothesisTransitionCommand(
      operationId,
      expectedVersion,
      com.signalframe.contract.HypothesisTransitionCause.EVIDENCE_ADDED,
      "an independent filing bears on the margin claim",
      evidenceId,
      null,
      null
    );
  }

  private long version(UUID hypothesisId) {
    return db.queryForObject(
      "SELECT version FROM hypotheses WHERE id=?",
      Long.class,
      hypothesisId
    );
  }

  private int confidenceColumn(UUID hypothesisId) {
    return db.queryForObject(
      "SELECT confidence FROM hypotheses WHERE id=?",
      Integer.class,
      hypothesisId
    );
  }

  private Hypothesis storedHypothesis(UUID hypothesisId) {
    return json.read(
      db.queryForObject(
        "SELECT payload::text FROM hypotheses WHERE id=?",
        String.class,
        hypothesisId
      ),
      Hypothesis.class
    );
  }

  private List<HypothesisEvent> storedEvents(UUID hypothesisId) {
    return db.query(
      "SELECT payload::text FROM hypothesis_events WHERE hypothesis_id=? ORDER BY created_at, id",
      (rs, n) -> json.read(rs.getString(1), HypothesisEvent.class),
      hypothesisId
    );
  }

  private String analysisPayload(UUID analysisId) {
    return db.queryForObject(
      "SELECT payload::text FROM analyses WHERE id=?",
      String.class,
      analysisId
    );
  }

  // ---- atomic commit -----------------------------------------------------

  @Test
  void aTransitionCommitsStatusConfidenceVersionAndEventTogether() {
    var seeded = seed("atomic.test", HypothesisStatus.OPEN, 20);
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);

    var result = transitions.transition(
      seeded.hypothesisId(),
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );

    assertTrue(result.applied());
    assertEquals(1L, version(seeded.hypothesisId()));
    assertEquals(result.confidence(), confidenceColumn(seeded.hypothesisId()));
    var stored = storedHypothesis(seeded.hypothesisId());
    assertEquals(HypothesisStatus.STRENGTHENING, stored.status());
    assertEquals(result.confidence(), stored.confidence());
    assertEquals(result.confidenceBand(), stored.confidenceBand());
    // CAP-A holds the uncorroborated score at 49; the fixture is the same one the
    // unit tests use, so the number is the rubric's, not the database's.
    assertEquals(49, result.confidence());
    var events = storedEvents(seeded.hypothesisId());
    assertEquals(2, events.size(), "CREATED plus the transition");
    assertEquals(result.eventId(), events.getLast().id());
    assertEquals(20, events.getLast().previousConfidence());
    assertEquals(49, events.getLast().confidence());
    assertEquals(HypothesisStatus.STRENGTHENING, events.getLast().status());
    assertEquals(HypothesisStatus.OPEN, events.getLast().previousStatus());
  }

  // ---- concurrency -------------------------------------------------------

  @Test
  void twoConcurrentTransitionsOfTheSameVersionProduceOneWinnerAndOneConflict()
    throws Exception {
    var seeded = seed("concurrency.test", HypothesisStatus.OPEN, 20);
    var first = seedEvidence(seeded, "SUPPORTS", true);
    var second = seedEvidence(seeded, "SUPPORTS", true);
    var start = new CountDownLatch(1);
    var failures = new AtomicReference<Exception>();
    var statuses = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (var evidenceId : List.of(first, second)) {
        pool.submit(() -> {
          try {
            start.await(5, TimeUnit.SECONDS);
            transitions.transition(
              seeded.hypothesisId(),
              evidenceCommand(evidenceId, 0L, UUID.randomUUID())
            );
            statuses.add(200);
          } catch (com.signalframe.shared.ApplicationException e) {
            statuses.add(e.status());
          } catch (Exception e) {
            failures.set(e);
          }
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertNull(failures.get(), String.valueOf(failures.get()));
    assertEquals(
      List.of(200, 409),
      statuses.stream().sorted().toList(),
      "exactly one transition may win the lock; the other observes the new version"
    );
    assertEquals(1L, version(seeded.hypothesisId()), "one version bump, not two");
    assertEquals(
      2,
      storedEvents(seeded.hypothesisId()).size(),
      "one transition event, so no confidence event was silently overwritten"
    );
  }

  // ---- rollback ----------------------------------------------------------

  @Test
  void aFailureAfterTheWritesRollsBackTheWholeTransition() {
    var seeded = seed("rollback.test", HypothesisStatus.OPEN, 20);
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);
    var template = new TransactionTemplate(transactionManager);

    var thrown = assertThrows(IllegalStateException.class, () ->
      template.execute(status -> {
        transitions.transition(
          seeded.hypothesisId(),
          evidenceCommand(evidenceId, 0L, UUID.randomUUID())
        );
        // The transition has already appended its event and bumped the version;
        // this failure must undo all of it.
        throw new IllegalStateException("forced failure after the writes");
      })
    );
    assertEquals("forced failure after the writes", thrown.getMessage());

    assertEquals(0L, version(seeded.hypothesisId()), "the version bump rolled back");
    assertEquals(20, confidenceColumn(seeded.hypothesisId()), "the confidence rolled back");
    assertEquals(HypothesisStatus.OPEN, storedHypothesis(seeded.hypothesisId()).status());
    assertEquals(
      1,
      storedEvents(seeded.hypothesisId()).size(),
      "the appended event rolled back with the update"
    );
  }

  @Test
  void aRefusedTransitionLeavesTheRowAndTheTimelineUntouched() {
    var seeded = seed("refused.test", HypothesisStatus.OPEN, 20);
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);
    transitions.transition(
      seeded.hypothesisId(),
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );
    var before = analysisPayload(seeded.analysisId());
    var payloadBefore = storedHypothesis(seeded.hypothesisId());

    var conflict = assertThrows(com.signalframe.shared.ApplicationException.class, () ->
      transitions.transition(
        seeded.hypothesisId(),
        evidenceCommand(evidenceId, 0L, UUID.randomUUID())
      )
    );
    assertEquals(409, conflict.status());
    assertEquals("VERSION_CONFLICT", conflict.code());

    assertEquals(1L, version(seeded.hypothesisId()));
    assertEquals(payloadBefore, storedHypothesis(seeded.hypothesisId()));
    assertEquals(2, storedEvents(seeded.hypothesisId()).size());
    assertEquals(
      before,
      analysisPayload(seeded.analysisId()),
      "the immutable snapshot is never rewritten (PR-17)"
    );
  }

  // ---- idempotency against the real primary key --------------------------

  @Test
  void aReplayAcrossTransactionsAppendsNoSecondEvent() {
    var seeded = seed("replay.test", HypothesisStatus.OPEN, 20);
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);
    var operationId = UUID.randomUUID();

    var first = transitions.transition(
      seeded.hypothesisId(),
      evidenceCommand(evidenceId, 0L, operationId)
    );
    var replayed = transitions.transition(
      seeded.hypothesisId(),
      evidenceCommand(evidenceId, 0L, operationId)
    );

    assertTrue(first.applied());
    assertFalse(replayed.applied());
    assertEquals(first.eventId(), replayed.eventId());
    assertEquals(first.occurredAt(), replayed.occurredAt());
    assertEquals(1L, version(seeded.hypothesisId()));
    assertEquals(2, storedEvents(seeded.hypothesisId()).size());
  }

  // ---- legacy data -------------------------------------------------------

  @Test
  void aLegacyRowTransitionsWithoutRewritingItsStoredStatusOrSnapshot() {
    // A pre-protocol hypothesis: a legacy status and an unreadable snapshot
    // payload, which is exactly what CF-06 fail-closed exists for.
    var seeded = seed("legacy.test", HypothesisStatus.SUPPORTED, 42);
    db.update("UPDATE analyses SET payload='{\"legacy\":true}'::jsonb WHERE id=?", seeded.analysisId());
    var legacySnapshot = analysisPayload(seeded.analysisId());
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);

    var result = transitions.transition(
      seeded.hypothesisId(),
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );

    assertEquals(
      42,
      result.confidence(),
      "the stored number is carried: CF-06 never rewrites a judgment to zero"
    );
    assertEquals(HypothesisStatus.STRENGTHENING, result.previousStatus());
    var stored = storedHypothesis(seeded.hypothesisId());
    assertEquals(
      HypothesisStatus.SUPPORTED,
      stored.status(),
      "a stored legacy value is read and never rewritten when the reading does not move"
    );
    assertEquals(42, confidenceColumn(seeded.hypothesisId()));
    assertEquals(
      legacySnapshot,
      analysisPayload(seeded.analysisId()),
      "no migration or transition backfills a stored payload (PR-17)"
    );
    var facts = com.signalframe.research.domain.hypotheses.TransitionEventText
      .read(storedEvents(seeded.hypothesisId()).getLast().reason())
      .orElseThrow();
    assertEquals("MODEL_JUDGMENT", facts.method());
  }

  @Test
  void anUnreadableHypothesisPayloadIsRefusedRatherThanCrashing() {
    var seeded = seed("unreadable.test", HypothesisStatus.OPEN, 20);
    db.update("UPDATE hypotheses SET payload='{\"legacy\":true}'::jsonb WHERE id=?", seeded.hypothesisId());
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);

    var refused = assertThrows(com.signalframe.shared.ApplicationException.class, () ->
      transitions.transition(
        seeded.hypothesisId(),
        evidenceCommand(evidenceId, 0L, UUID.randomUUID())
      )
    );
    assertEquals(422, refused.status());
    assertEquals("UNPROCESSABLE_TRANSITION", refused.code());
  }

  // ---- the allocated HTTP routes ----------------------------------------

  @Test
  void theTimelineRouteExposesTheVersionTheNextTransitionMustCite() throws Exception {
    var seeded = seed("http.test", HypothesisStatus.OPEN, 20);
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);

    var before = json.read(
      get("/api/v1/hypotheses/" + seeded.hypothesisId() + "/timeline").body(),
      HypothesisTimeline.class
    );
    assertEquals(0L, before.version());
    assertEquals(HypothesisStatus.OPEN, before.status());
    assertEquals(1, before.items().size());
    assertEquals("CREATED", before.items().getFirst().eventType());

    var applied = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );
    assertEquals(200, applied.statusCode(), applied.body());
    var result = json.read(applied.body(), HypothesisTransitionResult.class);
    assertTrue(result.applied());
    assertEquals(HypothesisStatus.STRENGTHENING, result.status());

    var after = json.read(
      get("/api/v1/hypotheses/" + seeded.hypothesisId() + "/timeline").body(),
      HypothesisTimeline.class
    );
    assertEquals(1L, after.version());
    assertEquals(HypothesisStatus.STRENGTHENING, after.status());
    assertEquals(2, after.items().size());
    assertEquals(result.eventId(), after.items().getLast().id());
  }

  @Test
  void theTransitionRouteMapsFailuresToTheFrozenStatusAndCode() throws Exception {
    var seeded = seed("http-errors.test", HypothesisStatus.OPEN, 20);
    var evidenceId = seedEvidence(seeded, "SUPPORTS", false);
    post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );

    var stale = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );
    assertEquals(409, stale.statusCode());
    assertEquals("VERSION_CONFLICT", json.read(stale.body(), ApiError.class).code());

    var unknown = post(
      "/api/v1/hypotheses/" + UUID.randomUUID() + "/transitions",
      evidenceCommand(evidenceId, 0L, UUID.randomUUID())
    );
    assertEquals(404, unknown.statusCode());
    assertEquals("NOT_FOUND", json.read(unknown.body(), ApiError.class).code());

    var malformed = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      new HypothesisTransitionCommand(
        null,
        1L,
        com.signalframe.contract.HypothesisTransitionCause.EVIDENCE_ADDED,
        "missing the idempotency key",
        evidenceId,
        null,
        null
      )
    );
    assertEquals(400, malformed.statusCode());
    assertEquals("INVALID_REQUEST", json.read(malformed.body(), ApiError.class).code());

    assertEquals(
      404,
      get("/api/v1/hypotheses/" + UUID.randomUUID() + "/timeline").statusCode()
    );
  }

  @Test
  void anIllegalTransitionOverHttpIsUnprocessable() throws Exception {
    var seeded = seed("http-terminal.test", HypothesisStatus.OPEN, 20);
    var contradiction = seedEvidence(seeded, "CONTRADICTS", true);
    post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      evidenceCommand(contradiction, 0L, UUID.randomUUID())
    );
    var falsification = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      new HypothesisTransitionCommand(
        UUID.randomUUID(),
        1L,
        com.signalframe.contract.HypothesisTransitionCause.FALSIFICATION_OBSERVED,
        "the pre-registered condition was met",
        seedEvidence(seeded, "CONTRADICTS", true),
        null,
        null
      )
    );
    assertEquals(200, falsification.statusCode(), falsification.body());
    assertEquals(
      HypothesisStatus.REJECTED,
      json.read(falsification.body(), HypothesisTransitionResult.class).status()
    );

    var refused = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      evidenceCommand(seedEvidence(seeded, "SUPPORTS", true), 2L, UUID.randomUUID())
    );
    assertEquals(422, refused.statusCode());
    assertEquals(
      "UNPROCESSABLE_TRANSITION",
      json.read(refused.body(), ApiError.class).code()
    );
  }

  @Test
  void aConfirmedPredictionOverHttpConfirmsOnlyWithAVerificationRecord()
    throws Exception {
    var seeded = seed("http-confirmed.test", HypothesisStatus.OPEN, 20);
    var pending = UUID.randomUUID();
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES (?,?,?,?,?::jsonb)",
      pending,
      seeded.hypothesisId(),
      Timestamp.from(SNAPSHOT_AT.plusSeconds(86_400 * 30)),
      "OPEN",
      "{}"
    );

    var asserted = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      new HypothesisTransitionCommand(
        UUID.randomUUID(),
        0L,
        com.signalframe.contract.HypothesisTransitionCause.PREDICTION_VERIFIED,
        "asserting a confirmation that was never recorded",
        null,
        pending,
        com.signalframe.contract.VerificationOutcome.CONFIRMED
      )
    );
    assertEquals(422, asserted.statusCode(), asserted.body());
    assertTrue(
      json.read(asserted.body(), ApiError.class).message().contains("verification record")
    );

    var verified = seedVerifiedPrediction(seeded, "CONFIRMED");
    var applied = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions",
      new HypothesisTransitionCommand(
        UUID.randomUUID(),
        0L,
        com.signalframe.contract.HypothesisTransitionCause.PREDICTION_VERIFIED,
        "the pre-registered condition resolved in favour of the hypothesis",
        null,
        verified,
        com.signalframe.contract.VerificationOutcome.CONFIRMED
      )
    );
    assertEquals(200, applied.statusCode(), applied.body());
    assertEquals(
      HypothesisStatus.CONFIRMED,
      json.read(applied.body(), HypothesisTransitionResult.class).status()
    );
  }
}
