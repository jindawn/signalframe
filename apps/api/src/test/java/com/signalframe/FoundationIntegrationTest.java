package com.signalframe;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.shared.JsonCodec;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest {

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
  JsonCodec json;

  @Autowired
  JobRepository jobs;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    )
      .timeout(Duration.ofSeconds(10))
      .header("X-Request-ID", "integration-check");
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
  void persistedTextToAnalysisAndResearchTimeline() throws Exception {
    assertEquals(200, request("GET", "/actuator/health", null).statusCode());
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(
        null,
        "某公司宣布 AI 推理服务价格下降。原始公告仍需独立复核。",
        "Integration news"
      )
    );
    assertEquals(201, created.statusCode());
    assertEquals(
      "integration-check",
      created.headers().firstValue("X-Request-ID").orElseThrow()
    );
    NewsItem news = json.read(created.body(), NewsItem.class);
    assertEquals(
      news.source().id(),
      json
        .read(
          request("GET", "/api/v1/news/" + news.id(), null).body(),
          NewsItem.class
        )
        .source()
        .id()
    );
    var started = request(
      "POST",
      "/api/v1/news/" + news.id() + "/analyze",
      null
    );
    assertEquals(202, started.statusCode());
    var job = json.read(started.body(), AnalysisJob.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
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
    assertEquals(16, job.events().size());
    var analysis = json.read(
      request("GET", "/api/v1/analyses/" + job.analysisId(), null).body(),
      Analysis.class
    );
    assertTrue(analysis.result().demo());
    assertEquals(ClaimType.FACT, analysis.result().facts().getFirst().type());
    assertEquals(
      news.source().id(),
      analysis.result().facts().getFirst().sourceRefs().getFirst().sourceId()
    );
    assertFalse(analysis.result().confidenceAssessment().isProbability());
    var runs = request("GET", "/api/v1/model-runs?jobId=" + job.id(), null);
    assertTrue(runs.body().contains("synthesis-v1"));
    assertTrue(runs.body().contains("SUCCEEDED"));
    assertFalse(runs.body().contains("apiKey"));
    var detail = json.read(
      request(
        "GET",
        "/api/v1/hypotheses/" + analysis.result().hypotheses().getFirst().id(),
        null
      ).body(),
      HypothesisDetail.class
    );
    assertEquals("CREATED", detail.timeline().getFirst().eventType());
    var sse = request(
      "GET",
      "/api/v1/analysis-jobs/" + job.id() + "/events",
      null
    );
    assertEquals(200, sse.statusCode());
    assertTrue(sse.body().contains("event:progress"));
    assertTrue(sse.body().contains("COMPLETED"));
    assertTrue(
      request("GET", "/api/v1/news/" + news.id() + "/analyses", null)
        .body()
        .contains(analysis.id().toString())
    );
  }

  @Test
  void cleanErrorsUrlFallbackProfileAndRestartRecovery() throws Exception {
    assertEquals(
      400,
      request(
        "POST",
        "/api/v1/news",
        new NewsInput(null, null, null)
      ).statusCode()
    );
    assertEquals(
      400,
      request("GET", "/api/v1/news/not-uuid", null).statusCode()
    );
    assertEquals(
      404,
      request("GET", "/api/v1/news/" + UUID.randomUUID(), null).statusCode()
    );
    var news = json.read(
      request(
        "POST",
        "/api/v1/news",
        new NewsInput("http://127.0.0.1/private", null, null)
      ).body(),
      NewsItem.class
    );
    assertEquals("NEEDS_TEXT", news.source().extractionStatus());
    assertEquals(
      409,
      request(
        "POST",
        "/api/v1/news/" + news.id() + "/analyze",
        null
      ).statusCode()
    );
    var text = json.read(
      request(
        "POST",
        "/api/v1/news",
        new NewsInput(null, "用于验证重启恢复的原始新闻正文", null)
      ).body(),
      NewsItem.class
    );
    var queued = jobs.create(text.id(), "recovery-test");
    assertThrows(com.signalframe.shared.ApplicationException.class, () ->
      jobs.create(text.id(), "duplicate")
    );
    jobs.recoverInterrupted();
    assertEquals(
      JobStatus.FAILED,
      jobs.find(queued.id()).orElseThrow().status()
    );
    var profile = new ModelProfile(
      "mock",
      "alternative-mock",
      "https://api.example.com",
      "AI_FAST_API_KEY",
      0.1,
      2048,
      20,
      true,
      false,
      true
    );
    assertEquals(
      200,
      request(
        "PUT",
        "/api/v1/settings/model-profiles/analysis.fast",
        profile
      ).statusCode()
    );
    assertTrue(
      request("GET", "/api/v1/settings/model-profiles", null)
        .body()
        .contains("alternative-mock")
    );
  }
}
