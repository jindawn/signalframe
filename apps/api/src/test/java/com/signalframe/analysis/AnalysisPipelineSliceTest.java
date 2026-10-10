package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Full PostgreSQL vertical slice of the protocol pipeline in the staged topology.
 *
 * <p>Real Spring wiring, real Flyway schema, real JDBC persistence: every
 * model-backed stage issues its own audited call, and the persisted snapshot is
 * checked against the protocol's invariants (verbatim fact provenance, rubric
 * provenance, UNKNOWN instead of silence, hypotheses OPEN at creation).
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = "analysis.pipeline.mode=staged"
)
class AnalysisPipelineSliceTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  static final String NEWS_TEXT =
    "某公司宣布 AI 推理服务价格下降 30%，并警告供应链受限。";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    )
      .timeout(Duration.ofSeconds(20))
      .header("X-Request-ID", "analysis-slice");
    if (body != null) builder.header("Content-Type", "application/json");
    return http.send(
      builder
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.write(body))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  @Test
  void stagedPipelinePersistsAProtocolConformantSnapshot() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "Protocol slice")
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem news = json.read(created.body(), NewsItem.class);

    var started = request(
      "POST",
      "/api/v1/news/" + news.id() + "/analyze",
      null
    );
    assertEquals(202, started.statusCode(), started.body());
    var job = json.read(started.body(), AnalysisJob.class);

    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (
      job.status() != JobStatus.COMPLETED &&
      job.status() != JobStatus.FAILED &&
      System.nanoTime() < deadline
    ) {
      Thread.sleep(75);
      job = json.read(
        request("GET", "/api/v1/analysis-jobs/" + job.id(), null).body(),
        AnalysisJob.class
      );
    }
    assertEquals(JobStatus.COMPLETED, job.status(), job.error());
    assertNotNull(job.analysisId());
    assertEquals(
      16,
      job.events().size(),
      "14 fixed durable steps plus creation and completion"
    );

    var analysis = json.read(
      request("GET", "/api/v1/analyses/" + job.analysisId(), null).body(),
      Analysis.class
    );
    var result = analysis.result();

    // STG-03: facts carry exact spans and remain REPORTED, never verified truth.
    assertFalse(result.facts().isEmpty());
    for (var fact : result.facts()) {
      assertEquals(ClaimType.FACT, fact.type());
      assertFalse(fact.sourceRefs().isEmpty());
      for (var ref : fact.sourceRefs()) {
        assertEquals(news.source().id(), ref.sourceId());
        assertEquals(
          NEWS_TEXT.substring(ref.startOffset(), ref.endOffset()),
          ref.quote()
        );
      }
      assertEquals("REPORTED", fact.verificationStatus());
    }

    // STG-16: the stored confidence is the deterministic rubric, and it records
    // the versions that produced the snapshot.
    var assessment = result.confidenceAssessment();
    assertFalse(assessment.isProbability());
    assertEquals(ConfidenceMethod.RUBRIC, assessment.method());
    assertEquals("0.1", assessment.rubricVersion());
    assertFalse(assessment.dimensions().isEmpty());
    assertEquals("0.1", result.protocolVersion());
    assertNotNull(result.provenance());
    assertFalse(result.provenance().promptVersions().isEmpty());
    assertTrue(assessment.score() >= 0 && assessment.score() <= 100);

    // PR-05/STG-16.2: a single-source snapshot must state what is missing.
    assertFalse(result.unknowns().isEmpty());
    result.unknowns().forEach(unknown ->
      assertNotEquals(ClaimType.FACT, unknown.type())
    );

    // STG-09: hypotheses start OPEN and carry their protocol refs.
    for (var hypothesis : result.hypotheses()) {
      assertEquals(ClaimType.HYPOTHESIS, hypothesis.type());
      assertEquals(HypothesisStatus.OPEN, hypothesis.status());
      assertFalse(hypothesis.confidenceReason().isBlank());
      assertFalse(hypothesis.supportingFactRefs().isEmpty());
      assertFalse(hypothesis.falsificationConditions().isEmpty());
    }
    assertFalse(result.modifiesExistingHypotheses());
    assertTrue(result.demo(), "offline mock output must be marked as demo");

    // PR-15: one audit row per model-backed stage, each with its own prompt version.
    var runs = json.read(
      request("GET", "/api/v1/model-runs?jobId=" + job.id(), null).body(),
      ModelRun[].class
    );
    var versions = Arrays.stream(runs).map(ModelRun::promptVersion).toList();
    assertEquals(14, runs.length, "thirteen stage runs plus synthesis: " + versions);
    for (var expected : List.of(
      "fact-extraction-v1",
      "variable-analysis-v1",
      "mechanism-analysis-v1",
      "first-order-effects-v1",
      "second-order-effects-v1",
      "stakeholder-analysis-v1",
      "hypothesis-generation-v1",
      "alternative-explanations-v1",
      "counter-argument-v1",
      "falsification-conditions-v1",
      "corroborating-signals-v1",
      "prediction-generation-v1",
      "verification-plan-v1",
      "synthesis-v1"
    )) assertTrue(versions.contains(expected), "missing audit row: " + expected);
    assertTrue(
      Arrays.stream(runs).allMatch(r -> "SUCCEEDED".equals(r.status()))
    );
    assertTrue(
      Arrays.stream(runs).noneMatch(r -> r.errorType() != null),
      "a successful attempt carries no error type"
    );

    // The run order is the protocol order, not an arbitrary scan order.
    assertEquals(
      "fact-extraction-v1",
      Arrays.stream(runs)
        .min(Comparator.comparing(ModelRun::startedAt))
        .orElseThrow()
        .promptVersion()
    );
  }
}
