package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.ApplicationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end verification of the live provider path through the real Spring
 * wiring: settings-driven profile, routing gateway, OpenAI-compatible adapter,
 * a local stub endpoint, the analysis job pipeline and the persisted audit row.
 *
 * <p>The credential comes from a test credential source, so the suite still
 * needs no paid key. The stub is reached over loopback HTTP, which is the
 * supported configuration for a local OpenAI-compatible server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveProviderAuditIntegrationTest {

  static final String SENTINEL_KEY = "sk-integration-sentinel-0987654321";
  static final String LIVE_MODEL = "stub-live-model";
  static final String NEWS_TEXT = "某公司发布新一代推理服务，本段文字用于端到端审计验证。";

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static final OpenAiStubServer stub;

  /** Completion body the stub returns once the test has prepared it. */
  static final AtomicReference<String> canned = new AtomicReference<>();

  static {
    postgres.start();
    try {
      stub = new OpenAiStubServer(body -> {
        String content = canned.get();
        if (content == null) return OpenAiStubServer.Reply.status(
          503,
          OpenAiStubServer.error(503, "stub not primed")
        );
        return OpenAiStubServer.Reply.ok(
          OpenAiStubServer.completion(content, 42, 7)
        );
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(
      "ai.profiles.[analysis.fast].provider",
      () -> "openai-compatible"
    );
    registry.add("ai.profiles.[analysis.fast].model", () -> LIVE_MODEL);
    registry.add("ai.profiles.[analysis.fast].base-url", stub::baseUrl);
    registry.add("ai.routes.SYNTHESIS", () -> "analysis.fast");
  }

  @TestConfiguration
  static class LiveStubs {

    @Bean
    @Primary
    ModelCredentialSource testCredentialSource() {
      return name -> Optional.of(SENTINEL_KEY);
    }

    /** Registered only in this context, to prove routing reads the adapter set. */
    @Bean
    ModelProviderAdapter probeAdapter() {
      return new ModelProviderAdapter() {
        @Override
        public String provider() {
          return "integration-probe";
        }

        @Override
        public ModelResponse call(
          ModelRequest request,
          ModelCredentials credentials
        ) {
          throw new UnsupportedOperationException("probe adapter is not called");
        }
      };
    }
  }

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  com.signalframe.infrastructure.ai.providers.RoutingModelGateway routing;

  @Autowired
  ModelProfilePolicy policy;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    )
      .timeout(Duration.ofSeconds(15))
      .header("X-Request-ID", "live-audit-check");
    return http.send(
      builder
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.write(body))
        )
        .header("Content-Type", "application/json")
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  @Test
  void liveProviderCallIsAuditedWithItsActualIdentifiersAndNoCredential()
    throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "live audit news")
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem news = json.read(created.body(), NewsItem.class);

    canned.set(
      new com.signalframe.infrastructure.ai.providers.MockModelGateway(json)
        .call(
          new ModelRequest(
            UUID.randomUUID(),
            ModelPurpose.SYNTHESIS,
            "synthesis-v1",
            "",
            news,
            AiTestFixtures.profile("mock", "mock-v1"),
            false
          )
        )
        .content()
    );

    var started = request(
      "POST",
      "/api/v1/news/" + news.id() + "/analyze",
      null
    );
    assertEquals(202, started.statusCode(), started.body());
    var job = json.read(started.body(), AnalysisJob.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
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

    // The request really reached the configured endpoint with the configured
    // model and the resolved credential.
    assertEquals(1, stub.bodies().size(), stub.bodies().toString());
    assertTrue(stub.bodies().getFirst().contains("\"model\":\"" + LIVE_MODEL + "\""));
    assertTrue(stub.authorizations().getFirst().contains(SENTINEL_KEY));

    // The audit records the actual live provider and model, not the profile
    // fallback, with the reported token usage.
    List<Map<String, Object>> persisted = db.queryForList(
      "SELECT payload::text AS payload FROM model_runs WHERE job_id=?",
      job.id()
    );
    assertFalse(persisted.isEmpty(), "an audit row must be persisted");
    String payload = persisted.getFirst().get("payload").toString();
    // jsonb re-renders with spaces after separators, so compare compactly.
    String compact = payload.replace(" ", "");
    assertTrue(compact.contains("\"provider\":\"openai-compatible\""), payload);
    assertTrue(compact.contains("\"model\":\"" + LIVE_MODEL + "\""), payload);
    assertTrue(compact.contains("\"promptVersion\":\"synthesis-v1\""), payload);
    assertTrue(compact.contains("\"status\":\"SUCCEEDED\""), payload);
    assertTrue(compact.contains("\"inputTokens\":42"), payload);
    assertTrue(compact.contains("\"outputTokens\":7"), payload);
    assertTrue(compact.contains("\"totalTokens\":49"), payload);
    assertTrue(compact.contains("\"correlationId\":\"live-audit-check\""), payload);
    assertTrue(compact.contains("\"errorType\":null"), payload);
    assertTrue(compact.contains("\"estimatedCost\":null"), payload);
    assertFalse(payload.contains(SENTINEL_KEY), "credential must never be audited");
    assertFalse(payload.contains(NEWS_TEXT), "source text must never be audited");

    // The public audit endpoint exposes the same facts and no secret.
    var runs = request("GET", "/api/v1/model-runs?jobId=" + job.id(), null);
    assertEquals(200, runs.statusCode());
    assertTrue(runs.body().contains("\"model\":\"" + LIVE_MODEL + "\""));
    assertTrue(runs.body().contains("SUCCEEDED"));
    assertFalse(runs.body().contains(SENTINEL_KEY));
    assertFalse(runs.body().toLowerCase(Locale.ROOT).contains("apikey"));
  }

  @Test
  void providerRegistryComesFromTheSpringContext() {
    assertTrue(routing.providers().contains("mock"));
    assertTrue(routing.providers().contains("openai-compatible"));
    assertTrue(
      routing.providers().contains("integration-probe"),
      routing.providers().toString()
    );
    assertDoesNotThrow(() ->
      policy.validate(
        new ModelProfile(
          "integration-probe",
          "any",
          "https://probe.invalid",
          "AI_TEST_API_KEY",
          0.2,
          2048,
          20,
          true,
          false,
          true
        )
      )
    );
    assertEquals(
      "UNSUPPORTED_PROVIDER",
      assertThrows(ApplicationException.class, () ->
        policy.validate(
          new ModelProfile(
            "not-registered",
            "any",
            "https://probe.invalid",
            "AI_TEST_API_KEY",
            0.2,
            2048,
            20,
            true,
            false,
            true
          )
        )
      ).code()
    );
  }
}
