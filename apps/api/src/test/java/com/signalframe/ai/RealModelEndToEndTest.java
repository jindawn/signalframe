package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.application.*;
import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.*;
import com.signalframe.shared.*;
import jakarta.validation.Validation;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Opt-in end-to-end verification of the real model path.
 *
 * <p>This suite is the only place in the repository that talks to an actual
 * OpenAI-compatible provider. It is disabled unless {@code AI_REAL_MODEL_E2E}
 * is explicitly {@code true}, and it skips itself when the provider variables
 * are absent, so a normal build, a keyless machine and CI never fail or spend
 * money because of it.
 *
 * <pre>
 * AI_REAL_MODEL_E2E=true              # enables this class (required)
 * AI_FAST_PROVIDER=openai-compatible  # provider identifier, must be registered
 * AI_FAST_MODEL=&lt;provider model id&gt;   # model identifier from the provider console
 * AI_FAST_BASE_URL=&lt;https api root&gt;   # API root; /chat/completions is appended
 * AI_FAST_API_KEY=&lt;secret&gt;            # never committed; read from the environment
 * AI_REAL_MODEL_E2E_ANALYSIS=true     # extra tier: full news -> analysis chain
 * </pre>
 *
 * <p>Nothing here changes the analysis prompt or the result schema: the strict
 * tier feeds the existing pipeline and validates whatever the provider returns
 * against the same schema and provenance rules as production.
 *
 * @see com.signalframe.ai.LiveProviderAuditIntegrationTest keyless stub twin
 */
@EnabledIfEnvironmentVariable(named = "AI_REAL_MODEL_E2E", matches = "(?i)true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealModelEndToEndTest {

  /** Enables the tier that requires a provider able to satisfy the full schema. */
  static final String STRICT_FLAG = "AI_REAL_MODEL_E2E_ANALYSIS";

  static final String PROVIDER = env("AI_FAST_PROVIDER");
  static final String MODEL = env("AI_FAST_MODEL");
  static final String BASE_URL = env("AI_FAST_BASE_URL");
  static final String API_KEY = env("AI_FAST_API_KEY");

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value.trim();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("ai.profiles.[analysis.fast].provider", () -> PROVIDER);
    registry.add("ai.profiles.[analysis.fast].model", () -> MODEL);
    registry.add("ai.profiles.[analysis.fast].base-url", () -> BASE_URL);
    registry.add("ai.profiles.[analysis.fast].timeout", () ->
      env("AI_REAL_MODEL_E2E_TIMEOUT_SECONDS").isEmpty()
        ? "45"
        : env("AI_REAL_MODEL_E2E_TIMEOUT_SECONDS")
    );
  }

  @BeforeAll
  static void requireProviderConfiguration() {
    Assumptions.assumeTrue(
      !PROVIDER.isBlank() &&
      !MODEL.isBlank() &&
      !BASE_URL.isBlank() &&
      !API_KEY.isBlank(),
      "AI_REAL_MODEL_E2E is set but AI_FAST_PROVIDER/MODEL/BASE_URL/API_KEY are not all present; " +
      "see docs/ai-runtime/REAL_MODEL_E2E.md"
    );
  }

  @LocalServerPort
  int port;

  @Autowired
  ModelRouter router;

  @Autowired
  ModelProfilePolicy policy;

  @Autowired
  RoutingModelGateway routing;

  @Autowired
  JsonCodec json;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();

  ModelProfile liveProfile() {
    var profile = router.route(ModelPurpose.SYNTHESIS);
    assertEquals(
      PROVIDER,
      profile.provider(),
      "the routed profile must come from the real provider configuration"
    );
    assertEquals(MODEL, profile.model());
    return profile;
  }

  ModelRequest request(ModelProfile profile, String prompt) {
    return new ModelRequest(
      UUID.randomUUID(),
      ModelPurpose.SYNTHESIS,
      "synthesis-v1",
      prompt,
      AiTestFixtures.news(),
      profile,
      false
    );
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .header("X-Request-ID", "real-model-e2e")
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  HttpResponse<String> post(String path, Object body) throws Exception {
    return http.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .header("X-Request-ID", "real-model-e2e")
        .header("Content-Type", "application/json")
        .POST(
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.write(body))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  /**
   * The configured provider must really be reachable and really be reported as
   * live: a missing key would silently fall back to the offline adapter, which
   * is the single easiest way to believe a real provider was validated.
   */
  @Test
  void configuredProfilesReportLiveModeWithoutExposingTheKey() throws Exception {
    var response = get("/api/v1/settings/model-profiles");
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().contains("\"analysis.fast\""), response.body());
    assertTrue(
      response.body().contains("\"effectiveMode\":\"LIVE\""),
      response.body()
    );
    assertTrue(response.body().contains("\"" + MODEL + "\""), response.body());
    assertFalse(
      response.body().contains(API_KEY),
      "the settings API must never return the credential"
    );
    assertEquals("LIVE", policy.effectiveMode(liveProfile()));
  }

  /** One real structured-output call through routing, credentials and adapter. */
  @Test
  void realProviderAnswersJsonAndReportsOnlyHonestUsage() {
    var profile = liveProfile();
    var response = routing.call(
      request(
        profile,
        "Answer with a single JSON object only, no prose: {\"status\":\"ok\"}. Output json."
      )
    );

    assertFalse(response.content().isBlank(), "provider returned no content");
    assertTrue(
      response.content().trim().startsWith("{"),
      "structured output must be a JSON object, got: " + response.content()
    );
    assertEquals(PROVIDER, response.provider());
    assertEquals(MODEL, response.model());

    var usage = response.usage();
    assertNotNull(usage, "usage must never be absent from the runtime response");
    boolean unknown =
      usage.inputTokens() == null &&
      usage.outputTokens() == null &&
      usage.totalTokens() == null;
    if (!unknown) {
      assertNotNull(usage.inputTokens(), "reported usage must be complete");
      assertNotNull(usage.outputTokens(), "reported usage must be complete");
      assertNotNull(usage.totalTokens(), "reported usage must be complete");
      assertEquals(
        usage.inputTokens() + usage.outputTokens(),
        usage.totalTokens().longValue(),
        "reported totals must add up"
      );
      assertNotEquals(
        0L,
        usage.inputTokens().longValue(),
        "a real completion cannot have consumed zero input tokens"
      );
    }
    assertNull(
      usage.estimatedCost(),
      "no provider prices this runtime, so cost stays unknown rather than fabricated"
    );
  }

  /**
   * A real provider rejects an unknown model identifier. The failure must be
   * classified, non-retryable and free of the credential, so an operator can
   * tell "wrong model id" from "wrong key" or "provider down".
   */
  @Test
  void unknownModelIsClassifiedAsARealProviderFailure() {
    var configured = liveProfile();
    var bogus = new ModelProfile(
      configured.provider(),
      configured.model() + "-not-a-real-model-" + UUID.randomUUID(),
      configured.baseUrl(),
      configured.apiKeyEnv(),
      configured.temperature(),
      configured.maxTokens(),
      configured.timeout(),
      configured.structuredOutput(),
      false,
      true
    );
    var failure = assertThrows(ModelInvocationException.class, () ->
      routing.call(request(bogus, "Output json only: {\"status\":\"ok\"}"))
    );

    assertNotEquals(
      ModelFailure.UNKNOWN,
      failure.failure(),
      "a real provider rejection must not fall through to UNKNOWN: " + failure.detail()
    );
    assertFalse(failure.retryable(), "an unknown model id cannot succeed on retry");
    assertTrue(
      Set.of(
        ModelFailure.MODEL_NOT_FOUND,
        ModelFailure.INVALID_REQUEST,
        ModelFailure.AUTHENTICATION,
        ModelFailure.PERMISSION_DENIED,
        ModelFailure.QUOTA_EXCEEDED
      ).contains(failure.failure()),
      "unexpected classification: " + failure.failure() + " / " + failure.detail()
    );
    assertFalse(
      failure.getMessage().contains(API_KEY),
      "the credential must never appear in a failure message"
    );
    assertFalse(
      failure.detail().contains(API_KEY),
      "the credential must never appear in the audited failure detail"
    );
    assertFalse(
      failure.detail().contains("\n"),
      "the audited failure detail must stay single-line: " + failure.detail()
    );
  }

  /**
   * Timeout and retry against the real HTTP stack, bounded and audited.
   *
   * <p>The endpoint accepts the connection and never answers, so the bound is
   * tested deterministically instead of depending on how slow the configured
   * provider happens to be. Every attempt is measured and recorded on its own,
   * and an unknown usage stays null rather than becoming a fabricated zero.
   */
  @Test
  void timeoutIsEnforcedAtTheConfiguredBoundAndEachAttemptIsAudited()
    throws Exception {
    try (var blackHole = new BlackHoleServer()) {
      var profile = new ModelProfile(
        "openai-compatible",
        "hung-model",
        blackHole.baseUrl(),
        "AI_FAST_API_KEY",
        0.2,
        2048,
        3,
        true,
        false,
        true
      );
      var runs = new ArrayList<ModelRun>();
      var service = new ModelAnalysisService(
        new ModelRouter(
          new AiProperties(
            "analysis.fast",
            Map.of(ModelPurpose.SYNTHESIS, "analysis.fast"),
            Map.of("analysis.fast", profile)
          ),
          Validation.buildDefaultValidatorFactory().getValidator(),
          new RoutingProfilePolicy()
        ),
        routing,
        recording(runs),
        new AnalysisResultValidator(
          json,
          Validation.buildDefaultValidatorFactory().getValidator()
        ),
        new PromptCatalog(),
        json,
        new ModelRetryPolicy(2, 1, Duration.ZERO)
      );

      long started = System.nanoTime();
      var error = assertThrows(ApplicationException.class, () ->
        service.synthesize(UUID.randomUUID(), "real-timeout", AiTestFixtures.news(), "")
      );
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;

      assertEquals("TIMEOUT", error.code());
      assertEquals(503, error.status());
      assertEquals(
        2,
        runs.size(),
        "one bounded retry means exactly two audited attempts: " + runs
      );
      for (var run : runs) {
        assertEquals("FAILED", run.status());
        assertEquals("TIMEOUT", run.errorType());
        assertEquals("openai-compatible", run.provider());
        assertEquals("hung-model", run.model());
        assertNotNull(run.latencyMs());
        assertTrue(
          run.latencyMs() >= 2_000,
          "the attempt must wait for the configured bound: " + run.latencyMs()
        );
        assertNull(run.inputTokens(), "a timed-out call must not invent usage");
        assertNull(run.outputTokens(), "a timed-out call must not invent usage");
        assertNull(run.totalTokens(), "a timed-out call must not invent usage");
        assertNull(run.estimatedCost(), "cost stays unknown");
      }
      assertTrue(
        elapsedMs < 30_000,
        "two 3s attempts must finish quickly, took " + elapsedMs + "ms"
      );
    }
  }

  /**
   * The full target chain against the configured provider: real call,
   * structured output, schema and provenance validation, analysis persistence,
   * ModelRun audit and the payload the web analysis detail reads.
   *
   * <p>Requires a provider that can follow the analysis output contract; small
   * local models often echo the input instead. Enable with
   * {@code AI_REAL_MODEL_E2E_ANALYSIS=true}.
   */
  @Test
  void analysisChainCompletesThroughApiAndIsVisibleToTheWebDetail()
    throws Exception {
    Assumptions.assumeTrue(
      Boolean.parseBoolean(env(STRICT_FLAG)),
      STRICT_FLAG +
      " is not true: the full analysis chain needs a provider that satisfies the result schema"
    );

    var created = post(
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "real model e2e")
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem news = json.read(created.body(), NewsItem.class);

    var started = post("/api/v1/news/" + news.id() + "/analyze", null);
    assertEquals(202, started.statusCode(), started.body());
    AnalysisJob job = json.read(started.body(), AnalysisJob.class);

    long deadline =
      System.nanoTime() +
      Duration.ofSeconds(
        Long.parseLong(
          env("AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS").isEmpty()
            ? "600"
            : env("AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS")
        )
      ).toNanos();
    while (
      job.status() != JobStatus.COMPLETED &&
      job.status() != JobStatus.FAILED &&
      System.nanoTime() < deadline
    ) {
      Thread.sleep(500);
      job = json.read(get("/api/v1/analysis-jobs/" + job.id()).body(), AnalysisJob.class);
    }
    if (job.status() != JobStatus.COMPLETED) {
      var audited = Arrays.stream(
        json.read(
          get("/api/v1/model-runs?jobId=" + job.id()).body(),
          ModelRun[].class
        )
      ).map(run -> run.status() + "/" + run.errorType()).toList();
      fail(
        "real model analysis did not complete: " +
        job.error() +
        " | audited attempts: " +
        audited +
        " | the provider must satisfy the AnalysisResult contract; a provider that echoes the input or wraps the object fails here, see docs/ai-runtime/REAL_MODEL_E2E.md"
      );
    }

    // Analysis persistence and the payload the analysis detail page renders.
    var analyses = json.read(
      get("/api/v1/news/" + news.id() + "/analyses").body(),
      Analysis[].class
    );
    assertEquals(1, analyses.length, "exactly one analysis must be persisted");
    var analysis = json.read(
      get("/api/v1/analyses/" + analyses[0].id()).body(),
      Analysis.class
    );
    var result = analysis.result();
    assertFalse(result.facts().isEmpty(), "a live analysis must state facts");
    assertFalse(result.demo(), "live output must not be marked as the offline demo");
    assertFalse(
      result.modifiesExistingHypotheses(),
      "live output must not claim to rewrite existing hypotheses"
    );
    assertFalse(result.confidenceAssessment().isProbability());
    for (var fact : result.facts()) {
      assertEquals(ClaimType.FACT, fact.type());
      assertFalse(fact.sourceRefs().isEmpty(), "facts need provenance");
      for (var ref : fact.sourceRefs()) {
        assertEquals(news.source().id(), ref.sourceId());
        assertEquals(
          NEWS_TEXT.substring(ref.startOffset(), ref.endOffset()),
          ref.quote(),
          "the quote must be the exact source span"
        );
      }
    }

    // ModelRun audit: real provider and model, measured latency, honest usage.
    var runs = json.read(
      get("/api/v1/model-runs?jobId=" + job.id()).body(),
      ModelRun[].class
    );
    assertFalse(runs.length == 0, "every attempt must be audited");
    var accepted = Arrays.stream(runs)
      .filter(run -> "SUCCEEDED".equals(run.status()))
      .findFirst()
      .orElseThrow(() ->
        new AssertionError("no successful attempt was audited: " + json.write(runs))
      );
    assertEquals(PROVIDER, accepted.provider());
    assertEquals(MODEL, accepted.model());
    assertEquals("synthesis-v1", accepted.promptVersion());
    assertEquals(ModelPurpose.SYNTHESIS, accepted.purpose());
    assertTrue(accepted.latencyMs() > 0, "latency must be measured");
    assertNull(accepted.errorType());
    assertNull(
      accepted.estimatedCost(),
      "cost must stay unknown unless a provider reports it"
    );
    boolean unknownUsage =
      accepted.inputTokens() == null &&
      accepted.outputTokens() == null &&
      accepted.totalTokens() == null;
    if (!unknownUsage) {
      assertNotNull(accepted.inputTokens(), "an audited run must record usage as a set");
      assertNotNull(accepted.outputTokens(), "an audited run must record usage as a set");
      assertNotNull(accepted.totalTokens(), "an audited run must record usage as a set");
      assertEquals(
        accepted.inputTokens() + accepted.outputTokens(),
        accepted.totalTokens().longValue(),
        "audited totals must add up"
      );
    }
    assertFalse(
      json.write(runs).contains(API_KEY),
      "the audit trail must never contain the credential"
    );
    assertFalse(
      json.write(runs).contains(NEWS_TEXT),
      "the audit trail must never contain source text"
    );
  }

  /** One realistic, short report so a real model has something to quote. */
  static final String NEWS_TEXT =
    "当地时间周三，某国能源监管机构表示，已与三家主要数据中心运营商启动电力供给谈判，" +
    "计划在未来两年内为新建数据中心提供约3吉瓦的专用电力容量。监管机构同时提出，" +
    "运营商需承担部分输配电设施升级成本。其中一家运营商回应称，升级成本分摊比例尚未确定，" +
    "若分摊方案超过预算，该公司可能推迟两处原定于明年开工的项目。分析人士认为，" +
    "该谈判结果将直接影响未来两年该国新增算力供给的增速。";

  static ModelRunRepository recording(List<ModelRun> runs) {
    return new ModelRunRepository() {
      @Override
      public void save(ModelRun run) {
        runs.add(run);
      }

      @Override
      public List<ModelRun> recent(UUID jobId) {
        return List.copyOf(runs);
      }
    };
  }

  /** Accepts TCP connections and never answers, to test the client bound. */
  static final class BlackHoleServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> held = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    BlackHoleServer() throws IOException {
      this.server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
      this.acceptor = Thread.ofVirtual().start(() -> {
        while (!server.isClosed()) {
          try {
            held.add(server.accept());
          } catch (IOException closed) {
            return;
          }
        }
      });
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Override
    public void close() {
      try {
        server.close();
      } catch (IOException ignored) {
        // closing is best-effort in a test teardown
      }
      for (var socket : held) {
        try {
          socket.close();
        } catch (IOException ignored) {
          // closing is best-effort in a test teardown
        }
      }
    }
  }
}
