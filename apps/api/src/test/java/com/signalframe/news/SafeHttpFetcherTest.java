package com.signalframe.news;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.infrastructure.news.FetchedPage;
import com.signalframe.infrastructure.news.NewsFetchProperties;
import com.signalframe.infrastructure.news.PublicDestinationPolicy;
import com.signalframe.infrastructure.news.SafeHttpFetcher;
import com.signalframe.infrastructure.news.SystemHostResolver;
import com.signalframe.news.domain.HostResolver;
import com.signalframe.news.domain.IngestionException;
import com.signalframe.news.domain.IngestionFailure;
import com.signalframe.news.domain.IngestionOutcome;
import com.signalframe.news.support.FixtureDestinationPolicy;
import com.signalframe.news.support.FixtureHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fetch-layer tests: bounds, content type policy, redirect handling and the
 * SSRF/DNS-rebinding invariants. All HTTP scenarios come from a loopback
 * fixture; no public site is contacted.
 */
class SafeHttpFetcherTest {

  private FixtureHttpServer fixture;
  private FixtureDestinationPolicy fixturePolicy;

  @BeforeEach
  void setUp() throws IOException {
    fixture = new FixtureHttpServer();
    fixturePolicy = new FixtureDestinationPolicy("127.0.0.1");
  }

  @AfterEach
  void tearDown() {
    fixture.close();
  }

  @Test
  void fetchesHtmlWithBoundedRequestHeaders() throws Exception {
    fixture.route(
      "/article",
      FixtureHttpServer.Response.html(
        "<html><body><p>fixture body</p></body></html>"
      )
    );

    FetchedPage page = fetcher(defaultProperties()).fetch(fixture.url("/article"));

    assertEquals(200, page.status());
    assertEquals("text/html", page.contentType());
    assertEquals(0, page.redirects());
    assertEquals("/article", page.finalUri().getPath());
    assertTrue(body(page).contains("fixture body"));
    assertEquals(1, fixture.requests().size());
    FixtureHttpServer.ReceivedRequest request = fixture.requests().getFirst();
    assertEquals("SignalFrame-Test/0.1", request.header("user-agent"));
    assertEquals("127.0.0.1:" + fixture.port(), request.header("host"));
    assertEquals("identity", request.header("accept-encoding"));
  }

  @Test
  void followsPublicRedirectAndReportsFinalUrl() throws Exception {
    fixture.route("/old", FixtureHttpServer.Response.redirect("/new"));
    fixture.route("/new", FixtureHttpServer.Response.html("<p>moved body</p>"));

    FetchedPage page = fetcher(defaultProperties()).fetch(fixture.url("/old"));

    assertEquals("/new", page.finalUri().getPath());
    assertEquals(1, page.redirects());
    assertTrue(body(page).contains("moved body"));
    assertEquals(
      List.of("/old", "/new"),
      fixture.requests().stream().map(FixtureHttpServer.ReceivedRequest::path).toList()
    );
  }

  @Test
  void blocksRedirectToPrivateDestination() throws Exception {
    fixture.route(
      "/jump",
      FixtureHttpServer.Response.redirect("http://192.168.7.7/private")
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> fetcher(defaultProperties()).fetch(fixture.url("/jump"))
    );

    assertEquals(
      IngestionFailure.REDIRECT_TO_UNSAFE_DESTINATION,
      failure.failure()
    );
    assertEquals(IngestionOutcome.BLOCKED, failure.failure().outcome());
    assertEquals(
      List.of("/jump"),
      fixture.requests().stream().map(FixtureHttpServer.ReceivedRequest::path).toList()
    );
  }

  @Test
  void blocksRedirectToUnsupportedScheme() throws Exception {
    fixture.route(
      "/jump",
      FixtureHttpServer.Response.redirect("file:///etc/passwd")
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> fetcher(defaultProperties()).fetch(fixture.url("/jump"))
    );

    assertEquals(
      IngestionFailure.REDIRECT_TO_UNSAFE_DESTINATION,
      failure.failure()
    );
  }

  @Test
  void refusesRedirectLoops() throws Exception {
    fixture.route("/a", FixtureHttpServer.Response.redirect("/b"));
    fixture.route("/b", FixtureHttpServer.Response.redirect("/a"));

    IngestionException failure = assertThrows(
      IngestionException.class,
      () ->
        fetcher(
          properties(Duration.ofSeconds(2), Duration.ofSeconds(5), 2, 64 * 1024)
        )
          .fetch(fixture.url("/a"))
    );

    assertEquals(IngestionFailure.TOO_MANY_REDIRECTS, failure.failure());
  }

  @Test
  void rejectsOversizedBody() throws Exception {
    fixture.route(
      "/big",
      FixtureHttpServer.Response.html("x".repeat(200_000))
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () ->
        fetcher(
          properties(Duration.ofSeconds(2), Duration.ofSeconds(5), 3, 1024)
        )
          .fetch(fixture.url("/big"))
    );

    assertEquals(IngestionFailure.RESPONSE_TOO_LARGE, failure.failure());
  }

  @Test
  void boundsDecompressedBodyAsWell() throws Exception {
    fixture.route(
      "/gzip-bomb",
      FixtureHttpServer.Response.bytes(
        200,
        "text/html",
        gzip("y".repeat(300_000).getBytes(StandardCharsets.UTF_8)),
        Map.of("Content-Encoding", "gzip")
      )
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () ->
        fetcher(
          properties(Duration.ofSeconds(2), Duration.ofSeconds(5), 3, 4096)
        )
          .fetch(fixture.url("/gzip-bomb"))
    );

    assertEquals(IngestionFailure.RESPONSE_TOO_LARGE, failure.failure());
  }

  @Test
  void decodesGzipResponses() throws Exception {
    fixture.route(
      "/gzip",
      FixtureHttpServer.Response.bytes(
        200,
        "text/html",
        gzip("<p>compressed body</p>".getBytes(StandardCharsets.UTF_8)),
        Map.of("Content-Encoding", "gzip")
      )
    );

    FetchedPage page = fetcher(defaultProperties()).fetch(fixture.url("/gzip"));

    assertTrue(body(page).contains("compressed body"));
  }

  @Test
  void rejectsUnsupportedContentType() throws Exception {
    fixture.route(
      "/image",
      FixtureHttpServer.Response.bytes(
        200,
        "image/png",
        new byte[] { (byte) 0x89, 'P', 'N', 'G' },
        Map.of()
      )
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> fetcher(defaultProperties()).fetch(fixture.url("/image"))
    );

    assertEquals(
      IngestionFailure.UNSUPPORTED_CONTENT_TYPE,
      failure.failure()
    );
    assertEquals(IngestionOutcome.UNSUPPORTED, failure.failure().outcome());
  }

  @Test
  void marksAccessBlockedStatusesAsBlocked() throws Exception {
    fixture.route(
      "/paywall",
      FixtureHttpServer.Response.status(402, "text/html", "subscribe")
    );
    fixture.route(
      "/forbidden",
      FixtureHttpServer.Response.status(403, "text/html", "forbidden")
    );

    for (String path : List.of("/paywall", "/forbidden")) {
      IngestionException failure = assertThrows(
        IngestionException.class,
        () -> fetcher(defaultProperties()).fetch(fixture.url(path))
      );
      assertEquals(IngestionFailure.ACCESS_BLOCKED, failure.failure(), path);
    }
  }

  @Test
  void mapsOtherHttpErrorsToHttpError() throws Exception {
    fixture.route(
      "/missing",
      FixtureHttpServer.Response.status(404, "text/html", "nope")
    );
    fixture.route(
      "/boom",
      FixtureHttpServer.Response.status(500, "text/html", "oops")
    );

    for (String path : List.of("/missing", "/boom")) {
      IngestionException failure = assertThrows(
        IngestionException.class,
        () -> fetcher(defaultProperties()).fetch(fixture.url(path))
      );
      assertEquals(IngestionFailure.HTTP_ERROR, failure.failure(), path);
    }
  }

  @Test
  void timesOutSlowResponses() throws Exception {
    fixture.route(
      "/slow",
      FixtureHttpServer.Response.html("<p>slow</p>", Duration.ofSeconds(1))
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () ->
        fetcher(
          properties(
            Duration.ofMillis(200),
            Duration.ofSeconds(3),
            3,
            64 * 1024
          )
        )
          .fetch(fixture.url("/slow"))
    );

    assertEquals(IngestionFailure.TIMEOUT, failure.failure());
  }

  @Test
  void rejectsUnsupportedSchemeWithoutConnecting() {
    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> fetcher(defaultProperties()).fetch("file:///etc/passwd")
    );

    assertEquals(IngestionFailure.INVALID_URL, failure.failure());
    assertTrue(fixture.requests().isEmpty());
  }

  @Test
  void rejectsCredentialsInUrlWithoutConnecting() {
    IngestionException failure = assertThrows(
      IngestionException.class,
      () ->
        fetcher(defaultProperties())
          .fetch("http://user:secret@127.0.0.1:" + fixture.port() + "/article")
    );

    assertEquals(IngestionFailure.INVALID_URL, failure.failure());
    assertTrue(fixture.requests().isEmpty());
  }

  @Test
  void blocksHostResolvingToPrivateAddressWithoutConnecting() {
    SequenceResolver resolver = new SequenceResolver("10.1.2.3");
    SafeHttpFetcher fetcher = new SafeHttpFetcher(
      resolver,
      new PublicDestinationPolicy(),
      defaultProperties()
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> fetcher.fetch("http://internal.example.com/article")
    );

    assertEquals(IngestionFailure.UNSAFE_DESTINATION, failure.failure());
    assertEquals(1, resolver.calls());
    assertTrue(fixture.requests().isEmpty());
  }

  @Test
  void connectsToTheValidatedAddressWithoutReResolving() throws Exception {
    fixture.route(
      "/article",
      FixtureHttpServer.Response.html("<p>pinned body</p>")
    );
    // Rebinding attack shape: public on the first lookup, metadata service on
    // any later lookup. Validation resolves once and the connection uses that
    // pinned address, so the second answer can never take effect.
    SequenceResolver resolver = new SequenceResolver(
      "127.0.0.1",
      "169.254.169.254"
    );
    SafeHttpFetcher fetcher = new SafeHttpFetcher(
      resolver,
      fixturePolicy,
      defaultProperties()
    );

    FetchedPage page = fetcher.fetch(fixture.url("/article"));

    assertTrue(body(page).contains("pinned body"));
    assertEquals(1, resolver.calls());
    assertEquals(1, fixture.requests().size());
  }

  @Test
  void validatesAndResolvesEveryRedirectHopSeparately() throws Exception {
    fixture.route("/a", FixtureHttpServer.Response.redirect("/b"));
    fixture.route("/b", FixtureHttpServer.Response.html("<p>second hop</p>"));
    SequenceResolver resolver = new SequenceResolver("127.0.0.1");
    SafeHttpFetcher fetcher = new SafeHttpFetcher(
      resolver,
      fixturePolicy,
      defaultProperties()
    );

    FetchedPage page = fetcher.fetch(fixture.url("/a"));

    assertEquals("/b", page.finalUri().getPath());
    assertEquals(2, resolver.calls(), "one resolution per hop");
  }

  @Test
  void reportsNetworkFailureWhenPortIsClosed() throws Exception {
    int port = closedPort();
    SafeHttpFetcher fetcher = new SafeHttpFetcher(
      new SystemHostResolver(),
      fixturePolicy,
      defaultProperties()
    );

    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> fetcher.fetch("http://127.0.0.1:" + port + "/anything")
    );

    assertEquals(IngestionFailure.NETWORK_FAILURE, failure.failure());
  }

  private SafeHttpFetcher fetcher(NewsFetchProperties properties) {
    return new SafeHttpFetcher(new SystemHostResolver(), fixturePolicy, properties);
  }

  private static NewsFetchProperties defaultProperties() {
    return properties(Duration.ofSeconds(2), Duration.ofSeconds(5), 3, 64 * 1024);
  }

  private static NewsFetchProperties properties(
    Duration readTimeout,
    Duration totalTimeout,
    int maxRedirects,
    int maxResponseBytes
  ) {
    return new NewsFetchProperties(
      Duration.ofMillis(500),
      readTimeout,
      totalTimeout,
      maxRedirects,
      maxResponseBytes,
      100_000,
      80,
      "SignalFrame-Test/0.1",
      List.of("text/html", "application/xhtml+xml", "text/plain")
    );
  }

  private static String body(FetchedPage page) {
    return new String(page.body(), StandardCharsets.UTF_8);
  }

  private static byte[] gzip(byte[] raw) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(raw);
    }
    return out.toByteArray();
  }

  private static int closedPort() throws IOException {
    try (
      ServerSocket socket = new ServerSocket(
        0,
        0,
        InetAddress.getByName("127.0.0.1")
      )
    ) {
      return socket.getLocalPort();
    }
  }

  /** Test resolver: answers in order, repeating the last answer afterwards. */
  private static final class SequenceResolver implements HostResolver {

    private final List<String> answers;
    private int calls;

    SequenceResolver(String... answers) {
      this.answers = List.of(answers);
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      calls++;
      String answer = answers.get(Math.min(calls - 1, answers.size() - 1));
      return List.of(InetAddress.getByName(answer));
    }

    int calls() {
      return calls;
    }
  }
}
