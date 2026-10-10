package com.signalframe.research.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import com.signalframe.shared.JsonCodec;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * TASK-07 end to end over real HTTP, a real PostgreSQL 17.6 and real Spring
 * transactions, with TASK-06's frozen port supplied by a recording stand-in.
 *
 * <p>This is where the properties a unit test cannot reach are established:
 *
 * <ul>
 *   <li>the allocated routes exist with the statuses the freeze §2 fixes;</li>
 *   <li>a failing transition <em>rolls the caller's write back</em> — no evidence row, no
 *       decided prediction — because the port call happens inside the same transaction
 *       (freeze §4.2);</li>
 *   <li>idempotency survives the HTTP boundary: a replay returns the same
 *       {@code verificationId} and the same transition with {@code applied = false}.</li>
 * </ul>
 *
 * <p>The port is a stand-in only because TASK-06's implementation lands on a parallel
 * branch. It owns no database and computes no confidence; it records what TASK-07 sent.
 * No paid model is contacted anywhere in this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvidencePredictionHttpTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  static final FakeHypothesisTransitionPort ENGINE = new FakeHypothesisTransitionPort();

  @TestConfiguration
  static class FrozenPortConfiguration {

    /**
     * The recording stand-in for this test's context.
     *
     * <p>{@code @Primary} is required on the integrated revision: TASK-06's real
     * {@code HypothesisTransitionService} is now also a
     * {@link HypothesisTransitionPort} bean, so a context that substitutes the port
     * has two candidates. Without a primary the gateway's resolution is ambiguous and
     * every evidence/verification call fails with a 500 instead of exercising the
     * behaviour under test.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    HypothesisTransitionPort hypothesisTransitionPort() {
      return ENGINE;
    }
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

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();

  UUID analysisId;
  UUID hypothesisId;
  UUID sourceId;
  UUID factId;

  @BeforeEach
  void seed() {
    ENGINE.received.clear();
    ENGINE.recordedByOperation.clear();
    ENGINE.calls.set(0);
    ENGINE.failWith = null;

    db.update("DELETE FROM indicators");
    db.update("DELETE FROM predictions");
    db.update("DELETE FROM evidence");

    factId = UUID.randomUUID();
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
      // The stored snapshot is the whole `Analysis` record, so its facts are nested
      // under `result` — that is the shape JdbcAnalysisRepository writes and the only
      // shape `factIds` can resolve. A flattened {"facts":[...]} payload would model
      // a row no writer produces.
      "{\"result\":{\"facts\":[{\"id\":\"" + factId + "\"}]}}",
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
      2
    );
    ENGINE.hypothesisId = hypothesisId;
  }

  // ---- evidence ---------------------------------------------------------

  @Test
  void evidenceIsCreatedAndTheEngineIsNotified() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      evidenceBody(sourceId, "SUPPORTS", 65, analysisId, List.of(factId))
    );

    assertEquals(201, response.statusCode());
    Evidence created = json.read(response.body(), Evidence.class);
    assertEquals(hypothesisId, created.hypothesisId());
    assertEquals("SUPPORTS", created.stance());
    assertEquals(65, created.strength());
    assertEquals(sourceId, created.sourceId());
    assertEquals(analysisId, created.analysisId());
    assertEquals(List.of(factId), created.factRefs());

    assertEquals(1, rowCount("evidence"));
    assertEquals(1, ENGINE.calls.get());
    var command = ENGINE.lastCommand();
    assertEquals(HypothesisTransitionCause.EVIDENCE_ADDED, command.cause());
    assertEquals(created.id(), command.evidenceRef());
    assertEquals(2L, command.expectedVersion());
  }

  @Test
  void evidenceForAnUnknownHypothesisIs404AndStoresNothing() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + UUID.randomUUID() + "/evidence",
      evidenceBody(sourceId, "SUPPORTS", 50, null, List.of())
    );
    assertEquals(404, response.statusCode());
    assertEquals(0, rowCount("evidence"));
  }

  @Test
  void evidenceWithAnUnknownSourceIs404AndStoresNothing() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      evidenceBody(UUID.randomUUID(), "SUPPORTS", 50, null, List.of())
    );
    assertEquals(404, response.statusCode());
    assertEquals(0, rowCount("evidence"));
    assertEquals(0, ENGINE.calls.get());
  }

  @Test
  void evidenceWithAnInvalidStanceIs400() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      evidenceBody(sourceId, "OPPOSES", 50, null, List.of())
    );
    assertEquals(400, response.statusCode());
    assertEquals(0, rowCount("evidence"));
  }

  @Test
  void evidenceWithAnOutOfRangeStrengthIs400() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      evidenceBody(sourceId, "SUPPORTS", 101, null, List.of())
    );
    assertEquals(400, response.statusCode());
    assertEquals(0, rowCount("evidence"));
  }

  @Test
  void evidenceWithABlankReasonIs400() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      "{\"sourceId\":\"" +
      sourceId +
      "\",\"stance\":\"SUPPORTS\",\"strength\":50,\"reason\":\"   \"}"
    );
    assertEquals(400, response.statusCode());
    assertEquals(0, rowCount("evidence"));
  }

  @Test
  void evidenceWithAnUnresolvableFactRefIs422AndStoresNothing() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      evidenceBody(sourceId, "SUPPORTS", 50, analysisId, List.of(UUID.randomUUID()))
    );
    assertEquals(422, response.statusCode());
    assertEquals(0, rowCount("evidence"));
    assertEquals(0, ENGINE.calls.get());
  }

  @Test
  void aFailingTransitionRollsTheEvidenceBack() throws Exception {
    ENGINE.failWith = new com.signalframe.shared.ApplicationException(
      409,
      "VERSION_CONFLICT",
      "the hypothesis moved under us"
    );

    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/evidence",
      evidenceBody(sourceId, "SUPPORTS", 50, analysisId, List.of(factId))
    );

    assertEquals(409, response.statusCode());
    assertEquals(
      0,
      rowCount("evidence"),
      "the evidence insert must roll back with the failed transition"
    );
  }

  // ---- prediction creation ---------------------------------------------

  @Test
  void aPredictionIsCreatedOpen() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/predictions",
      predictionBody(Instant.now().plus(Duration.ofDays(30)), null)
    );

    assertEquals(201, response.statusCode());
    Prediction created = json.read(response.body(), Prediction.class);
    assertEquals("OPEN", created.status());
    assertEquals("PREDICTION", created.type());
    assertEquals(hypothesisId, created.hypothesisId());
    assertEquals(1, rowCount("predictions"));
    assertEquals(0, ENGINE.calls.get(), "creating a prediction is not a transition");
  }

  @Test
  void aPredictionWithAPastDeadlineIs422() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/predictions",
      predictionBody(Instant.now().minus(Duration.ofDays(1)), null)
    );
    assertEquals(422, response.statusCode());
    assertEquals(0, rowCount("predictions"));
  }

  @Test
  void aPredictionWhoseCriteriaRestateItIs422() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/predictions",
      predictionBody(
        Instant.now().plus(Duration.ofDays(1)),
        "subscriber growth will reverse"
      )
    );
    assertEquals(422, response.statusCode());
    assertEquals(0, rowCount("predictions"));
  }

  @Test
  void aPredictionForAnUnknownHypothesisIs404() throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + UUID.randomUUID() + "/predictions",
      predictionBody(Instant.now().plus(Duration.ofDays(1)), null)
    );
    assertEquals(404, response.statusCode());
    assertEquals(0, rowCount("predictions"));
  }

  // ---- due query --------------------------------------------------------

  @Test
  void dueReturnsOverdueOpenPredictionsAndResolvesNothing() throws Exception {
    UUID overdue = insertStoredPrediction(
      Instant.now().minus(Duration.ofDays(2)),
      "OPEN"
    );
    UUID future = insertStoredPredictionWithId(
      Instant.now().plus(Duration.ofDays(2)),
      "OPEN"
    );
    UUID decided = insertStoredPrediction(
      Instant.now().minus(Duration.ofDays(2)),
      "CONFIRMED"
    );

    var response = get("/api/v1/predictions/due");

    assertEquals(200, response.statusCode());
    DuePredictions due = json.read(response.body(), DuePredictions.class);
    assertEquals(List.of(overdue), due.predictions().stream().map(Prediction::id).toList());
    assertTrue(due.predictions().stream().allMatch(p -> "OPEN".equals(p.status())));
    assertFalse(due.predictions().stream().anyMatch(p -> p.id().equals(future)));
    assertFalse(due.predictions().stream().anyMatch(p -> p.id().equals(decided)));

    // Asking about a passed deadline never decides it (requirement 9).
    assertEquals(
      "OPEN",
      db.queryForObject(
        "SELECT status FROM predictions WHERE id=?",
        String.class,
        overdue
      )
    );
    assertNull(
      db.queryForObject(
        "SELECT verified_at FROM predictions WHERE id=?",
        Timestamp.class,
        overdue
      )
    );
  }

  @Test
  void dueIsEmptyWhenNothingHasPassedItsDeadline() throws Exception {
    post(
      "/api/v1/hypotheses/" + hypothesisId + "/predictions",
      predictionBody(Instant.now().plus(Duration.ofDays(5)), null)
    );
    DuePredictions due = json.read(
      get("/api/v1/predictions/due").body(),
      DuePredictions.class
    );
    assertTrue(due.predictions().isEmpty());
  }

  // ---- verification -----------------------------------------------------

  @Test
  void verificationRecordsTheOutcomeAndLeavesTheTextAlone() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));

    var response = post(
      "/api/v1/predictions/" + open.id() + "/verification",
      verificationBody(UUID.randomUUID(), "PARTIAL", null, null)
    );

    assertEquals(200, response.statusCode());
    var result = json.read(response.body(), PredictionVerificationResult.class);
    assertTrue(result.applied());
    assertEquals("PARTIAL", result.prediction().status());
    assertEquals(open.statement(), result.prediction().statement());
    assertEquals(open.verificationCriteria(), result.prediction().verificationCriteria());
    assertEquals(open.expectedBy(), result.prediction().expectedBy());
    assertEquals(
      HypothesisTransitionCause.PREDICTION_VERIFIED,
      ENGINE.lastCommand().cause()
    );
    assertEquals(open.id(), ENGINE.lastCommand().predictionRef());
    assertNotNull(result.verificationId());
    assertEquals(
      Instant.now().getEpochSecond(),
      db
        .queryForObject(
          "SELECT verified_at FROM predictions WHERE id=?",
          Timestamp.class,
          open.id()
        )
        .toInstant()
        .getEpochSecond(),
      "verification records when it was decided"
    );
  }

  @Test
  void verificationIsIdempotentOnTheOperationIdOverHttp() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));
    UUID operationId = UUID.randomUUID();
    String body = verificationBody(operationId, "CONFIRMED", null, null);

    var first = json.read(
      post("/api/v1/predictions/" + open.id() + "/verification", body).body(),
      PredictionVerificationResult.class
    );
    Instant firstVerifiedAt = db.queryForObject(
      "SELECT verified_at FROM predictions WHERE id=?",
      Timestamp.class,
      open.id()
    ).toInstant();

    var replay = json.read(
      post("/api/v1/predictions/" + open.id() + "/verification", body).body(),
      PredictionVerificationResult.class
    );

    assertTrue(first.applied());
    assertFalse(replay.applied());
    assertEquals(first.verificationId(), replay.verificationId());
    assertEquals(
      first.hypothesisTransition().eventId(),
      replay.hypothesisTransition().eventId()
    );
    assertEquals("CONFIRMED", replay.prediction().status());
    assertEquals(
      firstVerifiedAt,
      db.queryForObject(
        "SELECT verified_at FROM predictions WHERE id=?",
        Timestamp.class,
        open.id()
      ).toInstant(),
      "a replay does not move the verification timestamp"
    );
  }

  @Test
  void aSecondVerificationWithANewOperationIdIs422() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));
    post(
      "/api/v1/predictions/" + open.id() + "/verification",
      verificationBody(UUID.randomUUID(), "CONFIRMED", null, null)
    );

    var response = post(
      "/api/v1/predictions/" + open.id() + "/verification",
      verificationBody(UUID.randomUUID(), "REJECTED", null, null)
    );

    assertEquals(422, response.statusCode());
    assertEquals(
      "CONFIRMED",
      db.queryForObject(
        "SELECT status FROM predictions WHERE id=?",
        String.class,
        open.id()
      ),
      "the first outcome stands; a prediction is decided once"
    );
  }

  @Test
  void aFailingTransitionRollsTheVerificationBack() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));
    ENGINE.failWith = new com.signalframe.shared.ApplicationException(
      422,
      "UNPROCESSABLE_TRANSITION",
      "the transition is not allowed from the current status"
    );

    var response = post(
      "/api/v1/predictions/" + open.id() + "/verification",
      verificationBody(UUID.randomUUID(), "CONFIRMED", null, null)
    );

    assertEquals(422, response.statusCode());
    assertEquals(
      "OPEN",
      db.queryForObject(
        "SELECT status FROM predictions WHERE id=?",
        String.class,
        open.id()
      ),
      "the prediction must stay OPEN when the engine refuses the transition"
    );
    assertNull(
      db.queryForObject(
        "SELECT verified_at FROM predictions WHERE id=?",
        Timestamp.class,
        open.id()
      )
    );
  }

  @Test
  void verificationCarriesTheEvidenceCitationToTheEngine() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));
    Evidence evidence = json.read(
      post(
        "/api/v1/hypotheses/" + hypothesisId + "/evidence",
        evidenceBody(sourceId, "SUPPORTS", 70, analysisId, List.of(factId))
      ).body(),
      Evidence.class
    );

    post(
      "/api/v1/predictions/" + open.id() + "/verification",
      verificationBody(UUID.randomUUID(), "CONFIRMED", evidence.id(), null)
    );

    assertEquals(evidence.id(), ENGINE.lastCommand().evidenceRef());
    assertEquals(open.id(), ENGINE.lastCommand().predictionRef());
  }

  @Test
  void verificationWithAnUnknownEvidenceCitationIs404() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));

    var response = post(
      "/api/v1/predictions/" + open.id() + "/verification",
      verificationBody(UUID.randomUUID(), "CONFIRMED", UUID.randomUUID(), null)
    );

    assertEquals(404, response.statusCode());
    assertEquals(
      "OPEN",
      db.queryForObject(
        "SELECT status FROM predictions WHERE id=?",
        String.class,
        open.id()
      )
    );
  }

  @Test
  void verificationOfAnUnknownPredictionIs404() throws Exception {
    var response = post(
      "/api/v1/predictions/" + UUID.randomUUID() + "/verification",
      verificationBody(UUID.randomUUID(), "CONFIRMED", null, null)
    );
    assertEquals(404, response.statusCode());
  }

  @Test
  void verificationWithoutAnIdempotencyKeyIs400() throws Exception {
    Prediction open = createPrediction(Instant.now().plus(Duration.ofDays(10)));
    var response = post(
      "/api/v1/predictions/" + open.id() + "/verification",
      "{\"result\":\"CONFIRMED\",\"reason\":\"checked\"}"
    );
    assertEquals(400, response.statusCode());
    assertEquals(
      "OPEN",
      db.queryForObject(
        "SELECT status FROM predictions WHERE id=?",
        String.class,
        open.id()
      )
    );
  }

  @Test
  void aMalformedIdentifierIs400NotA500() throws Exception {
    assertEquals(400, post("/api/v1/hypotheses/not-a-uuid/evidence", evidenceBody(sourceId, "SUPPORTS", 50, null, List.of())).statusCode());
    assertEquals(400, post("/api/v1/predictions/not-a-uuid/verification", verificationBody(UUID.randomUUID(), "CONFIRMED", null, null)).statusCode());
  }

  // ---- legacy compatibility --------------------------------------------

  @Test
  void aLegacyPredictionRowIsIgnoredByTheDueQuery() throws Exception {
    // Pre-freeze row: CONFIRMED, so it cannot be due, and its payload is not a
    // Prediction. Reading the newest endpoint must not be broken by older data.
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES(?,?,?,'CONFIRMED','{\"legacy\":true}'::jsonb)",
      UUID.randomUUID(),
      hypothesisId,
      Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"))
    );

    var response = get("/api/v1/predictions/due");
    assertEquals(200, response.statusCode());
    assertTrue(
      json.read(response.body(), DuePredictions.class).predictions().isEmpty()
    );
  }

  // ---- helpers ----------------------------------------------------------

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create(base() + path))
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create(base() + path))
        .timeout(Duration.ofSeconds(15))
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  String base() {
    return "http://127.0.0.1:" + port;
  }

  Prediction createPrediction(Instant expectedBy) throws Exception {
    var response = post(
      "/api/v1/hypotheses/" + hypothesisId + "/predictions",
      predictionBody(expectedBy, null)
    );
    assertEquals(201, response.statusCode(), response.body());
    return json.read(response.body(), Prediction.class);
  }

  static String evidenceBody(
    UUID sourceId,
    String stance,
    int strength,
    UUID analysisId,
    List<UUID> factRefs
  ) {
    StringBuilder sb = new StringBuilder();
    sb
      .append("{\"sourceId\":\"")
      .append(sourceId)
      .append("\",\"stance\":\"")
      .append(stance)
      .append("\",\"strength\":")
      .append(strength)
      .append(",\"reason\":\"the filing contradicts the reported volume\"");
    sb.append(",\"factRefs\":[");
    for (int i = 0; i < factRefs.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(factRefs.get(i)).append('"');
    }
    sb.append(']');
    if (analysisId != null) {
      sb.append(",\"analysisId\":\"").append(analysisId).append('"');
    }
    return sb.append('}').toString();
  }

  static String predictionBody(Instant expectedBy, String criteriaOverride) {
    String criteria = criteriaOverride != null
      ? criteriaOverride
      : "the disclosed figure falls below 4.0 million in the Q3 filing";
    return (
      "{\"statement\":\"subscriber growth will reverse\"," +
      "\"observable\":\"quarterly subscriber count\"," +
      "\"expectedBy\":\"" +
      expectedBy +
      "\",\"verificationCriteria\":\"" +
      criteria +
      "\",\"whereToCheck\":\"the Q3 filing on the investor-relations site\"}"
    );
  }

  static String verificationBody(
    UUID operationId,
    String result,
    UUID evidenceRef,
    Instant verifiedAt
  ) {
    StringBuilder sb = new StringBuilder("{\"operationId\":\"").append(operationId);
    sb
      .append("\",\"result\":\"")
      .append(result)
      .append("\",\"reason\":\"checked against the published filing\"");
    if (evidenceRef != null) {
      sb.append(",\"evidenceRef\":\"").append(evidenceRef).append('"');
    }
    if (verifiedAt != null) {
      sb.append(",\"verifiedAt\":\"").append(verifiedAt).append('"');
    }
    return sb.append('}').toString();
  }

  int rowCount(String table) {
    return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  UUID insertStoredPrediction(Instant expectedBy, String status) {
    return insertStoredPredictionWithId(expectedBy, status);
  }

  UUID insertStoredPredictionWithId(Instant expectedBy, String status) {
    var prediction = new Prediction(
      UUID.randomUUID(),
      hypothesisId,
      "stored prediction",
      "PREDICTION",
      expectedBy,
      status,
      "criteria that distinguish confirmation from rejection",
      "observable",
      "where to check",
      List.of()
    );
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES(?,?,?,?,?::jsonb)",
      prediction.id(),
      hypothesisId,
      Timestamp.from(expectedBy),
      status,
      json.write(prediction)
    );
    return prediction.id();
  }
}
