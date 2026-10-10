package com.signalframe.research.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.AnalysisResult;
import com.signalframe.contract.Evidence;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.Prediction;
import com.signalframe.research.application.evidence.HypothesisTransitionGateway;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared harness for the Wave 2B integration gate: the real Spring context, a real
 * PostgreSQL 17.6 migrated by the real Flyway V1→V4 chain, real JDBC repositories
 * and <em>no port override</em>.
 *
 * <p>That last point is the whole reason this harness exists. TASK-06's and
 * TASK-07's own suites each replace the other side of the seam — TASK-07 installs a
 * recording {@code FakeHypothesisTransitionPort}, TASK-06 seeds predictions with
 * raw SQL — so neither can observe whether the two halves actually agree at
 * runtime. Here both halves are the production beans, and every write goes through
 * the real service, the real transaction and the real rubric.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class Wave2BIntegrationSupport {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort
  int port;

  @Autowired
  JdbcTemplate db;

  @Autowired
  JsonCodec json;

  /** The production port bean; the harness deliberately declares no replacement. */
  @Autowired
  HypothesisTransitionPort transitionPort;

  /** TASK-07's only route to the engine. */
  @Autowired
  HypothesisTransitionGateway gateway;

  @Autowired
  ApplicationContext context;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();

  // ---- HTTP --------------------------------------------------------------

  HttpResponse<String> post(String path, Object body) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.write(body)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  <T> T body(HttpResponse<String> response, Class<T> type) {
    return json.read(response.body(), type);
  }

  // ---- seeding -----------------------------------------------------------

  record Seeded(UUID sourceId, UUID hypothesisId, UUID analysisId, UUID factId) {}

  /**
   * Seeds source → news → job → analysis → hypothesis → CREATED event, with a
   * snapshot the deterministic rubric scores at {@value
   * ResearchLoopFixtures#INITIAL_CONFIDENCE}.
   */
  Seeded seed() {
    return seedWith(HypothesisStatus.OPEN, ResearchLoopFixtures.INITIAL_CONFIDENCE_REASON);
  }

  /** The same snapshot, but with a prose (pre-protocol) previous rendering. */
  Seeded seedLegacy() {
    return seedWith(HypothesisStatus.OPEN, "model said this looked fairly likely");
  }

  /** The same snapshot, but the hypothesis is already decided. */
  Seeded seedWithStatus(HypothesisStatus status) {
    return seedWith(status, ResearchLoopFixtures.INITIAL_CONFIDENCE_REASON);
  }

  /**
   * A hypothesis whose producing snapshot cannot be read as a protocol snapshot —
   * a pre-SCH-01 row. The rubric must fail closed (CF-06) rather than invent a
   * level.
   */
  Seeded seedUnscorable() {
    return seedWith(
      HypothesisStatus.OPEN,
      ResearchLoopFixtures.INITIAL_CONFIDENCE_REASON,
      "{\"summary\":\"a legacy payload with no protocol fields\"}"
    );
  }

  private Seeded seedWith(HypothesisStatus status, String previousRendering) {
    return seedWith(
      status,
      previousRendering,
      null
    );
  }

  private Seeded seedWith(
    HypothesisStatus status,
    String previousRendering,
    String rawAnalysisPayload
  ) {
    var sourceId = UUID.randomUUID();
    var newsId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var analysisId = UUID.randomUUID();
    var hypothesisId = UUID.randomUUID();
    var factId = UUID.randomUUID();
    var condition = ResearchLoopFixtures.falsificationCondition(
      "if the reported margin recovers without an input-cost movement",
      "the reported unit margin",
      "compare with the pre-decline quarter at the same definition",
      "below 30 percent for two consecutive quarters"
    );
    AnalysisResult result = ResearchLoopFixtures.snapshot(
      ResearchLoopFixtures.source(
        ResearchLoopFixtures.ARTICLE_PUBLISHER,
        "PRIMARY",
        "COMPLETE"
      ),
      List.of(
        ResearchLoopFixtures.fact(
          factId,
          sourceId,
          "unit margin fell 30 percent on higher input cost"
        )
      ),
      List.of(
        ResearchLoopFixtures.mechanism(
          UUID.randomUUID(),
          List.of(factId),
          "PLAUSIBLE"
        )
      ),
      List.of(ResearchLoopFixtures.counterArgument(hypothesisId, List.of(factId))),
      List.of(condition),
      List.of(ResearchLoopFixtures.signal(hypothesisId)),
      List.of(ResearchLoopFixtures.planItem(hypothesisId))
    );
    var analysis = ResearchLoopFixtures.analysis(
      analysisId,
      newsId,
      jobId,
      result
    );
    var hypothesis = ResearchLoopFixtures.hypothesis(
      hypothesisId,
      status,
      ResearchLoopFixtures.INITIAL_CONFIDENCE,
      previousRendering,
      List.of(factId),
      List.of(condition)
    );

    db.update(
      "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES (?,?,?,?,?::jsonb,?)",
      sourceId,
      "https://wire.example.test/" + sourceId,
      "unit margin fell 30 percent on higher input cost",
      "PASTED",
      "{}",
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO news_items(id,source_id,payload,created_at) VALUES (?,?,?::jsonb,?)",
      newsId,
      sourceId,
      "{}",
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO analysis_jobs(id,news_id,status,correlation_id,created_at,updated_at) VALUES (?,?,?,?,?,?)",
      jobId,
      newsId,
      "COMPLETED",
      "wave2b-integration",
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT),
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO analyses(id,news_id,job_id,payload,created_at) VALUES (?,?,?,?::jsonb,?)",
      analysisId,
      newsId,
      jobId,
      rawAnalysisPayload != null ? rawAnalysisPayload : json.write(analysis),
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO hypotheses(id,analysis_id,payload,confidence,created_at,updated_at,version) VALUES (?,?,?::jsonb,?,?,?,0)",
      hypothesisId,
      analysisId,
      json.write(hypothesis),
      ResearchLoopFixtures.INITIAL_CONFIDENCE,
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT),
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    db.update(
      "INSERT INTO hypothesis_events(id,hypothesis_id,payload,created_at) VALUES (?,?,?::jsonb,?)",
      UUID.randomUUID(),
      hypothesisId,
      json.write(
        ResearchLoopFixtures.createdEvent(
          hypothesisId,
          status,
          ResearchLoopFixtures.INITIAL_CONFIDENCE,
          previousRendering
        )
      ),
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    return new Seeded(sourceId, hypothesisId, analysisId, factId);
  }

  /** A second, independent source — the only thing that can release CAP-A. */
  UUID seedIndependentSource(String publisher) {
    var sourceId = UUID.randomUUID();
    db.update(
      "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES (?,?,?,?,?::jsonb,?)",
      sourceId,
      "https://independent.example.test/" + sourceId,
      "an independently compiled record",
      "PASTED",
      "{}",
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    return sourceId;
  }

  /**
   * A due-but-unverified prediction, inserted directly to model a passed deadline.
   *
   * <p>The payload is a real {@link Prediction} record, not {@code {}}: the due
   * query reads the stored payload, so an empty one would deserialize to a
   * prediction with a null id.
   */
  UUID seedDueOpenPrediction(Seeded seeded, Instant expectedBy) {
    var predictionId = UUID.randomUUID();
    var payload = new Prediction(
      predictionId,
      seeded.hypothesisId(),
      "the next quarterly filing reports a unit margin below 30 percent",
      "PREDICTION",
      expectedBy,
      "OPEN",
      "confirmation requires a reported margin below 30 percent; rejection requires 30 percent or above",
      "the reported unit margin in the next quarterly filing",
      "the issuer's investor relations filings page",
      List.of()
    );
    db.update(
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES (?,?,?,?,?::jsonb)",
      predictionId,
      seeded.hypothesisId(),
      Timestamp.from(expectedBy),
      "OPEN",
      json.write(payload)
    );
    return predictionId;
  }

  /** An evidence row written directly, so a test can set the version it will cite. */
  UUID seedEvidenceRow(Seeded seeded, UUID sourceId, String stance, int strength) {
    var evidenceId = UUID.randomUUID();
    var payload = new Evidence(
      evidenceId,
      seeded.hypothesisId(),
      stance,
      strength,
      sourceId,
      "seeded evidence for the integration gate",
      ResearchLoopFixtures.SNAPSHOT_AT,
      List.of(),
      null
    );
    db.update(
      "INSERT INTO evidence(id,hypothesis_id,source_id,stance,strength,payload,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
      evidenceId,
      seeded.hypothesisId(),
      sourceId,
      stance,
      strength,
      json.write(payload),
      Timestamp.from(ResearchLoopFixtures.SNAPSHOT_AT)
    );
    return evidenceId;
  }

  // ---- reads -------------------------------------------------------------

  record HypothesisRow(
    int confidence,
    long version,
    HypothesisStatus status,
    String confidenceReason,
    List<UUID> supportingEvidenceRefs,
    List<UUID> contradictingEvidenceRefs
  ) {}

  HypothesisRow hypothesisRow(UUID hypothesisId) {
    return db
      .query(
        "SELECT confidence, version, payload::text FROM hypotheses WHERE id=?",
        (rs, n) -> {
          var payload = json.read(rs.getString(3), Hypothesis.class);
          return new HypothesisRow(
            rs.getInt(1),
            rs.getLong(2),
            payload.status(),
            payload.confidenceReason(),
            payload.supportingEvidenceRefs(),
            payload.contradictingEvidenceRefs()
          );
        },
        hypothesisId
      )
      .getFirst();
  }

  List<HypothesisEvent> events(UUID hypothesisId) {
    return db.query(
      "SELECT payload::text FROM hypothesis_events WHERE hypothesis_id=? ORDER BY created_at, id",
      (rs, n) -> json.read(rs.getString(1), HypothesisEvent.class),
      hypothesisId
    );
  }

  List<Evidence> evidenceRows(UUID hypothesisId) {
    return db.query(
      "SELECT id, hypothesis_id, stance, strength, source_id, created_at FROM evidence WHERE hypothesis_id=? ORDER BY created_at, id",
      (rs, n) ->
        new Evidence(
          rs.getObject("id", UUID.class),
          rs.getObject("hypothesis_id", UUID.class),
          rs.getString("stance"),
          rs.getInt("strength"),
          rs.getObject("source_id", UUID.class),
          null,
          rs.getTimestamp("created_at").toInstant(),
          List.of(),
          null
        ),
      hypothesisId
    );
  }

  String predictionStatus(UUID predictionId) {
    return db.queryForObject(
      "SELECT status FROM predictions WHERE id=?",
      String.class,
      predictionId
    );
  }

  /**
   * The verification timestamp of a prediction.
   *
   * <p>Read from the V4 column rather than from the HTTP body: the frozen
   * {@code Prediction} schema has no {@code verifiedAt} member, so the timestamp is
   * part of the verification record but not of the prediction payload
   * (WAVE2B_CONTRACT_FREEZE §11.7).
   */
  Instant predictionVerifiedAt(UUID predictionId) {
    return db.queryForObject(
      "SELECT verified_at FROM predictions WHERE id=?",
      (rs, n) -> {
        var value = rs.getTimestamp(1);
        return value == null ? null : value.toInstant();
      },
      predictionId
    );
  }

  int countEvents(UUID hypothesisId) {
    return db.queryForObject(
      "SELECT count(*) FROM hypothesis_events WHERE hypothesis_id=?",
      Integer.class,
      hypothesisId
    );
  }

  int countEvidence(UUID hypothesisId) {
    return db.queryForObject(
      "SELECT count(*) FROM evidence WHERE hypothesis_id=?",
      Integer.class,
      hypothesisId
    );
  }

  /**
   * Every stored event payload in timeline order. Comparing this list before and
   * after a transition is what proves the timeline is append-only rather than
   * rewritten.
   */
  List<String> eventPayloads(UUID hypothesisId) {
    return db.queryForList(
      "SELECT payload::text FROM hypothesis_events WHERE hypothesis_id=? ORDER BY created_at, id",
      String.class,
      hypothesisId
    );
  }

  /** Fails with the ApiError code, which is what the freeze pins for each status. */
  void assertError(HttpResponse<String> response, int status, String code) {
    assertEquals(
      status,
      response.statusCode(),
      "expected " + status + " " + code + " but got " + response.body()
    );
    assertTrue(
      response.body().contains("\"" + code + "\""),
      "expected code " + code + " in " + response.body()
    );
  }
}
