package com.signalframe.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobQueue;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.shared.JsonCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared harness for the TASK-10 cross-cutting regression gate.
 *
 * <p>Every test that extends this class runs against the real Spring context, a
 * real PostgreSQL 17.6 container migrated by the real Flyway V1→V4 chain and the
 * production beans. There is deliberately no port override: the persistence
 * assertions read the same rows the HTTP layer wrote.
 *
 * <p>The container is allocated once for the whole JVM. Unlike the per-task
 * harnesses (`FoundationIntegrationTest`, `Wave2BIntegrationSupport`), the
 * TASK-10 suites share one instance, which keeps the integrated-revision gate
 * cheap enough to run on every change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationFlowSupport {

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
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  JobRepository jobs;

  /** The production queue bean: the same one the HTTP layer enqueues into. */
  @Autowired
  JobQueue queue;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();

  // ---- HTTP --------------------------------------------------------------

  HttpResponse<String> send(
    String method,
    String path,
    Object body,
    Map<String, String> headers
  ) throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    )
      .timeout(Duration.ofSeconds(30))
      .header("X-Request-ID", "task10-integration");
    headers.forEach(builder::header);
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

  HttpResponse<String> post(String path, Object body) throws Exception {
    return send("POST", path, body, Map.of());
  }

  HttpResponse<String> get(String path) throws Exception {
    return send("GET", path, null, Map.of());
  }

  <T> T body(HttpResponse<String> response, Class<T> type) {
    return json.read(response.body(), type);
  }

  // ---- flow helpers ------------------------------------------------------

  NewsItem newsText(String text) throws Exception {
    var response = post("/api/v1/news", new NewsInput(null, text, null));
    assertEquals(201, response.statusCode(), response.body());
    return body(response, NewsItem.class);
  }

  AnalysisJob analyze(UUID newsId) throws Exception {
    var response = post("/api/v1/news/" + newsId + "/analyze", null);
    assertEquals(202, response.statusCode(), response.body());
    return body(response, AnalysisJob.class);
  }

  static boolean terminal(JobStatus status) {
    return status == JobStatus.COMPLETED || status == JobStatus.FAILED;
  }

  AnalysisJob awaitTerminal(UUID jobId, Duration budget) throws Exception {
    long deadline = System.nanoTime() + budget.toNanos();
    var job = body(get("/api/v1/analysis-jobs/" + jobId), AnalysisJob.class);
    while (!terminal(job.status()) && System.nanoTime() < deadline) {
      Thread.sleep(75);
      job = body(get("/api/v1/analysis-jobs/" + jobId), AnalysisJob.class);
    }
    assertTrue(
      terminal(job.status()),
      "job " + jobId + " stayed " + job.status() + " for " + budget
    );
    return job;
  }

  // ---- SSE ---------------------------------------------------------------

  /** One parsed server-sent event frame. */
  record SseFrame(String id, String name, String data) {
    JobEvent event(JsonCodec json) {
      return json.read(data, JobEvent.class);
    }
  }

  /**
   * Reads an event stream to its close.
   *
   * <p>A stream is only expected to close on a terminal job, which is exactly the
   * behaviour under test; a non-terminal job would hold the connection until the
   * emitter timeout, so callers must only read terminal or fast jobs.
   */
  List<SseFrame> readEvents(UUID jobId, Long lastEventId) throws Exception {
    var path = "/api/v1/analysis-jobs/" + jobId + "/events";
    var response = lastEventId == null
      ? get(path)
      : send("GET", path, null, Map.of("Last-Event-ID", lastEventId.toString()));
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(
      response
        .headers()
        .firstValue("Content-Type")
        .orElse("")
        .contains("text/event-stream"),
      "expected an event stream, got " +
      response.headers().firstValue("Content-Type").orElse("none")
    );
    return parseFrames(response.body());
  }

  static List<SseFrame> parseFrames(String body) {
    var frames = new ArrayList<SseFrame>();
    var normalized = body.replace("\r\n", "\n");
    for (var block : normalized.split("\n\n")) {
      String id = null;
      String name = null;
      var data = new StringBuilder();
      for (var line : block.split("\n")) {
        if (line.startsWith("id:")) id = line.substring(3).trim();
        else if (line.startsWith("event:")) name = line.substring(6).trim();
        else if (line.startsWith("data:")) {
          if (data.length() > 0) data.append('\n');
          data.append(line.substring(5).trim());
        }
      }
      if (id != null || name != null || data.length() > 0) frames.add(
        new SseFrame(id, name, data.toString())
      );
    }
    return frames;
  }

  static List<Long> ids(List<SseFrame> frames) {
    return frames
      .stream()
      .map(frame -> {
        assertNotNull(frame.id(), "every progress frame must carry an id");
        return Long.parseLong(frame.id());
      })
      .toList();
  }

  static JobEvent event(SseFrame frame, JsonCodec json) {
    assertNotNull(frame.data(), "progress frames must carry a job event");
    return frame.event(json);
  }

  // ---- persistence -------------------------------------------------------

  int countRows(String sql, Object... args) {
    var count = db.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  String sourceText(UUID sourceId) {
    return db.queryForObject(
      "SELECT body FROM sources WHERE id=?",
      String.class,
      sourceId
    );
  }

  UUID latestJobId(UUID newsId) {
    return db.queryForObject(
      "SELECT id FROM analysis_jobs WHERE news_id=? ORDER BY created_at DESC, id DESC LIMIT 1",
      UUID.class,
      newsId
    );
  }

  /** Fails with the ApiError code, mirroring the foundation gate convention. */
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
