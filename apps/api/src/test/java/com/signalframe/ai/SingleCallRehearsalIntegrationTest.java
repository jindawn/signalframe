package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.application.*;
import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.*;
import com.signalframe.shared.JsonCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
 * The offline rehearsal of a metered single-call analysis.
 *
 * <p>The live provider charges per request and the run may not be retried, so the
 * properties that guarantee one request and the diagnosis that a failure must
 * leave behind are proven here, against a local stub, before any real call:
 *
 * <ul>
 *   <li>{@code ai.retry.max-attempts=1} and {@code ai.retry.max-repair-attempts=0}
 *       are bound through the real Spring wiring and read back off the service,
 *       so "single shot" is a verified fact rather than a configuration hope;</li>
 *   <li>one analysis — success or failure — reaches the provider exactly once;</li>
 *   <li>an empty completion is diagnosed by its finish reason, and a body that
 *       cannot be decoded is diagnosed as a decode failure rather than as "the
 *       model returned nothing";</li>
 *   <li>neither the reasoning text, nor the raw provider body, nor the credential
 *       reaches a job row, an audit row or a log line.</li>
 * </ul>
 *
 * <p>The credential is a sentinel from a test credential source, so no paid key is
 * involved and the leak assertions have something exact to look for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SingleCallRehearsalIntegrationTest {

  static final String SENTINEL_KEY = "sk-rehearsal-sentinel-1234567890";
  static final String STUB_MODEL = "stub-metered-model";
  static final String REASONING = "internal reasoning that must never be recorded";
  static final String NEWS_TEXT = "某公司发布新一代推理服务，本段文字用于单次调用演练验证。";

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static final OpenAiStubServer stub;

  /** Body the stub returns, or null to fail loudly instead of guessing. */
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
        return OpenAiStubServer.Reply.ok(content);
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
    registry.add("ai.profiles.[analysis.fast].provider", () -> "openai-compatible");
    registry.add("ai.profiles.[analysis.fast].model", () -> STUB_MODEL);
    registry.add("ai.profiles.[analysis.fast].base-url", stub::baseUrl);
    registry.add("ai.routes.SYNTHESIS", () -> "analysis.fast");
    // The single-shot configuration this rehearsal exists to prove.
    registry.add("ai.retry.max-attempts", () -> "1");
    registry.add("ai.retry.max-repair-attempts", () -> "0");
    // The budget the live retest targets.
    registry.add("ai.profiles.[analysis.fast].max-tokens", () -> "16384");
    registry.add("ai.profiles.[analysis.fast].timeout", () -> "120");
  }

  @TestConfiguration
  static class RehearsalStubs {

    @Bean
    @Primary
    ModelCredentialSource testCredentialSource() {
      return name -> Optional.of(SENTINEL_KEY);
    }
  }

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  ModelAnalysisService analysisService;

  @Autowired
  ModelRouter router;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();

  @BeforeEach
  void resetStub() {
    canned.set(null);
    stub.clear();
  }

  // ---- the configuration the live run depends on ---------------------------

  @Test
  void theEffectiveRunIsSingleShotWithTheTargetedBudget() {
    var policy = analysisService.retryPolicy();
    assertEquals(1, policy.maxAttempts(), "exactly one transport attempt");
    assertEquals(0, policy.maxRepairAttempts(), "no schema repair round");
    var profile = router.route(ModelPurpose.SYNTHESIS);
    assertEquals(16384, profile.maxTokens(), "the targeted completion ceiling");
    assertEquals(120, profile.timeout(), "the targeted per-call bound");
    assertEquals("openai-compatible", profile.provider());
    assertEquals(STUB_MODEL, profile.model());
  }

  // ---- one analysis, one provider request ----------------------------------

  @Test
  void aSuccessfulAnalysisReachesTheProviderExactlyOnce() throws Exception {
    var news = createNews();
    primeWithValidSnapshot(news);
    var job = analyse(news.id());
    assertEquals(JobStatus.COMPLETED, job.status(), job.error());
    assertEquals(1, stub.bodies().size(), "one analysis must cost exactly one request");
    assertEquals(1, auditRows(job.id()).size(), "one attempt, one audit row");
    assertEquals("SUCCEEDED", auditRows(job.id()).getFirst().get("status"));
  }

  /**
   * Every parameter the metered call will carry, checked on the wire: the
   * configured ceiling and bound, the model, the structured-output request, the
   * client identity and the session header. The credential must ride in the
   * Authorization header and never in the body.
   */
  @Test
  void theSingleRequestCarriesEveryConfiguredParameter() throws Exception {
    var news = createNews();
    primeWithValidSnapshot(news);
    var job = analyse(news.id());
    assertEquals(JobStatus.COMPLETED, job.status(), job.error());
    assertEquals(1, stub.bodies().size());

    var sent = stub.bodies().getFirst();
    assertTrue(sent.contains("\"model\":\"" + STUB_MODEL + "\""), sent);
    assertTrue(
      sent.contains("\"max_tokens\":16384"),
      "the configured ceiling must reach the wire: " + sent
    );
    assertTrue(sent.contains("json_object"), sent);
    assertTrue(sent.contains("\"temperature\":0.2"), sent);
    assertFalse(sent.contains("\"stream\":true"), sent);

    assertEquals("SignalFrame/0.1.0", stub.header(0, "User-Agent"));
    assertNotNull(
      stub.header(0, "x-opencode-session"),
      "the session header must reach the provider"
    );
    assertTrue(
      stub.authorizations().getFirst().contains(SENTINEL_KEY),
      "the credential is carried as a bearer token"
    );
    assertFalse(
      sent.contains(SENTINEL_KEY),
      "the credential must never appear in the request body"
    );
    assertEquals("/chat/completions", stub.paths().getFirst());
  }

  @Test
  void anEmptyTruncatedCompletionIsDiagnosedAndStillCostsOneRequest()
    throws Exception {
    var news = createNews();
    canned.set(OpenAiStubServer.emptyContentCompletion(REASONING, "length", 4812, 16384));

    var job = analyse(news.id());

    assertEquals(JobStatus.FAILED, job.status());
    assertEquals(1, stub.bodies().size(), "a failure must not buy a second request");
    var rows = auditRows(job.id());
    assertEquals(1, rows.size(), "no repair round, so exactly one audited attempt");
    assertEquals("FAILED", rows.getFirst().get("status"));
    assertEquals("PROVIDER_RESPONSE_INVALID", rows.getFirst().get("errorType"));

    var error = job.error();
    assertNotNull(error);
    assertTrue(
      error.contains("finish_reason=LENGTH"),
      "the failure must name the finish reason: " + error
    );
    assertTrue(
      error.contains("truncated at the completion ceiling"),
      "the failure must say the reply was cut off before any content: " + error
    );
    assertNoLeakage(error, rows.getFirst());
  }

  /**
   * A provider that omits {@code finish_reason} entirely does not reach the
   * classifier at all: the client refuses to decode the body. That is a decode
   * failure naming the missing field, and it must never be reported as "the model
   * returned nothing".
   */
  @Test
  void anOmittedFinishReasonIsADecodeFailureNamingTheField() throws Exception {
    var news = createNews();
    canned.set(OpenAiStubServer.emptyContentCompletion(REASONING, null, 4812, 100));

    var job = analyse(news.id());

    assertEquals(JobStatus.FAILED, job.status());
    assertEquals(1, stub.bodies().size());
    assertTrue(
      job.error().contains("could not be decoded"),
      "an omitted required field is a decode failure: " + job.error()
    );
    assertTrue(
      job.error().contains("finish_reason"),
      "the decode failure must name the missing field: " + job.error()
    );
    assertFalse(
      job.error().contains("empty completion"),
      "it must not be confused with an empty answer: " + job.error()
    );
    assertNoLeakage(job.error(), auditRows(job.id()).getFirst());
  }

  /** The classifier itself still labels a present-but-blank reason as UNKNOWN. */
  @Test
  void ablankFinishReasonIsLabelledUnknownByTheClassifier() {
    for (var blank : Arrays.asList(null, "", "   ")) {
      var message = OpenAiCompatibleGateway.emptyCompletion(blank);
      assertTrue(
        message.contains("finish_reason=UNKNOWN"),
        "an unusable reason must be labelled UNKNOWN: " + message
      );
    }
    assertTrue(
      OpenAiCompatibleGateway.emptyCompletion("length").contains("finish_reason=LENGTH"),
      "the classifier normalizes the wire value"
    );
    assertTrue(
      OpenAiCompatibleGateway.emptyCompletion("STOP").contains("produced no answer")
    );
    assertTrue(
      OpenAiCompatibleGateway.emptyCompletion("something_new").contains(
        "finish_reason=SOMETHING_NEW"
      ),
      "an unrecognized reason is reported verbatim rather than guessed"
    );
  }

  @Test
  void aFilteredCompletionIsDiagnosedAsFiltered() throws Exception {
    var news = createNews();
    canned.set(
      OpenAiStubServer.emptyContentCompletion(REASONING, "content_filter", 4812, 12)
    );

    var job = analyse(news.id());

    assertEquals(JobStatus.FAILED, job.status());
    assertEquals(1, stub.bodies().size());
    assertTrue(
      job.error().contains("finish_reason=CONTENT_FILTER"),
      job.error()
    );
    assertNoLeakage(job.error(), auditRows(job.id()).getFirst());
  }

  @Test
  void anUndecodableBodyIsDiagnosedAsADecodeFailureNotAsAnEmptyAnswer()
    throws Exception {
    var news = createNews();
    canned.set(OpenAiStubServer.undecodableCompletion());

    var job = analyse(news.id());

    assertEquals(JobStatus.FAILED, job.status());
    assertEquals(1, stub.bodies().size());
    var error = job.error();
    assertTrue(
      error.contains("could not be decoded"),
      "an undecodable body must not be reported as an empty completion: " + error
    );
    assertFalse(
      error.contains("empty completion"),
      "the two causes must stay distinguishable: " + error
    );
    assertNoLeakage(error, auditRows(job.id()).getFirst());
  }

  // ---- helpers -------------------------------------------------------------

  /**
   * The provider's own body, reasoning, credential and the raw response must never
   * appear in an operator message or an audit row.
   */
  void assertNoLeakage(String error, Map<String, Object> auditRow) {
    var audit = String.valueOf(auditRow.get("payload"));
    for (var forbidden : List.of(REASONING, SENTINEL_KEY)) {
      assertFalse(
        error.contains(forbidden),
        "the job error must not contain the reasoning or the credential"
      );
      assertFalse(
        audit.contains(forbidden),
        "the audit row must not contain the reasoning or the credential"
      );
    }
    assertFalse(
      error.contains("\"choices\""),
      "the raw provider body must never reach the job error: " + error
    );
    assertFalse(error.contains("\n"), "the message stays single-line");
  }

  record News(NewsItem item) {}

  NewsItem createNews() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "single call rehearsal")
    );
    assertEquals(201, created.statusCode(), created.body());
    return json.read(created.body(), NewsItem.class);
  }

  /** A schema-valid snapshot generated from the news the API actually stored. */
  void primeWithValidSnapshot(NewsItem news) {
    var snapshot = new MockModelGateway(json)
      .call(
        new ModelRequest(
          UUID.randomUUID(),
          ModelPurpose.SYNTHESIS,
          "synthesis-v3",
          "",
          news,
          AiTestFixtures.profile("mock", "mock-v1"),
          false
        )
      )
      .content();
    // Wrapped in a real chat-completion envelope, which is what a provider sends.
    canned.set(OpenAiStubServer.completion(snapshot, 4812, 1190));
  }

  AnalysisJob analyse(UUID newsId) throws Exception {
    var started = request("POST", "/api/v1/news/" + newsId + "/analyze", null);
    assertEquals(202, started.statusCode(), started.body());
    var job = json.read(started.body(), AnalysisJob.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (
      job.status() != JobStatus.COMPLETED &&
      job.status() != JobStatus.FAILED &&
      System.nanoTime() < deadline
    ) {
      Thread.sleep(50);
      job = json.read(
        request("GET", "/api/v1/analysis-jobs/" + job.id(), null).body(),
        AnalysisJob.class
      );
    }
    assertNotEquals(JobStatus.QUEUED, job.status(), "the job must reach a terminal state");
    return job;
  }

  List<Map<String, Object>> auditRows(UUID jobId) {
    return db.queryForList(
      "SELECT status, payload->>'errorType' AS \"errorType\", payload::text AS payload FROM model_runs WHERE job_id=? ORDER BY started_at",
      jobId
    );
  }

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    ).timeout(Duration.ofSeconds(30));
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
}
