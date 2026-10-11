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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The two-stage mode against a real PostgreSQL and Flyway V1→V4 schema.
 *
 * <p>The in-memory boundary tests prove the assembly and failure rules; this proves
 * the parts that only a real database and a real Spring context can: the persisted
 * snapshot is one ordinary immutable row the Wave 2B engine can read, the two stage
 * attempts are audited as two rows with their own prompt versions, and the
 * monolithic path is not involved at all.
 *
 * <p>The offline reference provider answers both stages, so no network call and no
 * credential is involved.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = "analysis.pipeline.mode=two-stage"
)
class TwoStagePostgresIntegrationTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  static final String NEWS_TEXT =
    "文件编号𠮷A-2026。某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";

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

  @Autowired
  JdbcTemplate db;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    ).timeout(Duration.ofSeconds(20));
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
  void twoStageModePersistsOneAnalysisAndAuditsBothStages() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "two stage postgres")
    );
    assertEquals(201, created.statusCode(), created.body());
    var news = json.read(created.body(), NewsItem.class);

    var started = request("POST", "/api/v1/news/" + news.id() + "/analyze", null);
    assertEquals(202, started.statusCode(), started.body());
    var job = json.read(started.body(), AnalysisJob.class);

    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
    assertEquals(16, job.events().size(), "the 14 fixed steps are unchanged");

    // ---- the persisted snapshot is one ordinary analysis --------------------
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM analyses WHERE news_id=?",
        Integer.class,
        news.id()
      )
    );
    var analysis = json.read(
      request("GET", "/api/v1/analyses/" + job.analysisId(), null).body(),
      Analysis.class
    );
    var result = analysis.result();
    assertTrue(result.demo(), "the offline provider is still flagged as demo");
    assertFalse(result.facts().isEmpty());
    assertEquals(
      ConfidenceMethod.RUBRIC,
      result.confidenceAssessment().method(),
      "Gate D: the persisted score is the deterministic rubric's"
    );
    assertFalse(result.confidenceAssessment().isProbability());
    assertTrue(
      result.provenance().promptVersions().contains("two-stage-facts-v1"),
      "PR-15: the stage prompt versions are recorded: " +
      result.provenance().promptVersions()
    );
    assertTrue(
      result.provenance().promptVersions().contains("two-stage-reasoning-v4")
    );

    // ---- both stages are audited separately ---------------------------------
    var runs = json.read(
      request("GET", "/api/v1/model-runs?jobId=" + job.id(), null).body(),
      ModelRun[].class
    );
    assertEquals(
      2,
      runs.length,
      "one row per stage, never one row pretending to be both: " +
      Arrays.toString(runs)
    );
    var purposes = Arrays.stream(runs).map(ModelRun::purpose).toList();
    assertTrue(purposes.contains(ModelPurpose.FACT_EXTRACTION), purposes.toString());
    assertTrue(purposes.contains(ModelPurpose.SYNTHESIS), purposes.toString());
    var versions = Arrays.stream(runs).map(ModelRun::promptVersion).toList();
    assertTrue(versions.contains("two-stage-facts-v1"), versions.toString());
    assertTrue(versions.contains("two-stage-reasoning-v4"), versions.toString());
    for (var run : runs) {
      assertEquals("SUCCEEDED", run.status());
      assertNull(run.errorType());
      assertNotNull(run.latencyMs());
    }

    // ---- the Wave 2B research objects still agree with the snapshot ---------
    // The invariant is agreement, not a fixed count: whatever the snapshot states
    // is what the research engine gets, with one CREATED event per hypothesis and
    // version 0 so the optimistic lock starts where it always did. The offline
    // reference provider answers each stage with its own snapshot, so its
    // stage-2 fact references do not resolve against the stage-1 fact ids and are
    // conservatively dropped (PR-05) rather than rewritten; a real provider sees
    // the stage-1 ids in its prompt.
    var hypotheses = db.queryForObject(
      "SELECT count(*) FROM hypotheses WHERE analysis_id=?",
      Integer.class,
      job.analysisId()
    );
    assertEquals(
      result.hypotheses().size(),
      hypotheses,
      "one rows per snapshot hypothesis"
    );
    var events = db.queryForObject(
      "SELECT count(*) FROM hypothesis_events WHERE hypothesis_id IN (SELECT id FROM hypotheses WHERE analysis_id=?)",
      Integer.class,
      job.analysisId()
    );
    assertEquals(hypotheses, events, "one CREATED event per hypothesis");
    if (hypotheses > 0) {
      var detail = json.read(
        request(
          "GET",
          "/api/v1/hypotheses/" +
          db.queryForObject(
            "SELECT id FROM hypotheses WHERE analysis_id=? LIMIT 1",
            UUID.class,
            job.analysisId()
          ),
          null
        ).body(),
        HypothesisDetail.class
      );
      assertEquals(0L, detail.version(), "the research engine reads it unchanged");
      assertEquals("CREATED", detail.timeline().getFirst().eventType());
    }
  }

  @Test
  void aSecondAnalysisOfTheSameNewsIsANewImmutableSnapshot() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "two stage immutability")
    );
    var news = json.read(created.body(), NewsItem.class);

    Set<String> snapshotIds = new LinkedHashSet<>();
    for (int run = 0; run < 2; run++) {
      var started = request(
        "POST",
        "/api/v1/news/" + news.id() + "/analyze",
        null
      );
      var job = json.read(started.body(), AnalysisJob.class);
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
      snapshotIds.add(job.analysisId().toString());
    }
    assertEquals(2, snapshotIds.size(), "each run writes its own snapshot");
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM analyses WHERE news_id=?",
        Integer.class,
        news.id()
      ),
      "PR-17: an earlier snapshot is never overwritten"
    );
  }
}
