package com.signalframe.news;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.infrastructure.news.HtmlArticleExtractor;
import com.signalframe.infrastructure.news.NewsFetchProperties;
import com.signalframe.infrastructure.news.PublicDestinationPolicy;
import com.signalframe.infrastructure.news.PublicWebContentExtractor;
import com.signalframe.infrastructure.news.SafeHttpFetcher;
import com.signalframe.infrastructure.news.SystemHostResolver;
import com.signalframe.news.domain.IngestionFailure;
import com.signalframe.news.domain.IngestionOutcome;
import com.signalframe.news.domain.NormalizedContent;
import com.signalframe.news.support.FixtureDestinationPolicy;
import com.signalframe.news.support.FixtureHttpServer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end ingestion tests over the loopback fixture: fetch, content-type
 * policy, HTML/text extraction, normalization, provenance and failure mapping.
 */
class PublicWebContentExtractorTest {

  private static final String ARTICLE_HTML =
    """
    <html><head>
      <title>站点 · 备用标题</title>
      <link rel="canonical" href="/canonical/article">
      <script>var tracking = "SCRIPT_NOISE";</script>
      <style>.x { color: red; }</style>
    </head><body>
      <nav>NAVIGATION_NOISE</nav>
      <header>HEADER_NOISE</header>
      <article>
        <h1>真实标题</h1>
        <p>第一段正文内容，用于验证正文抽取、段落顺序以及页面噪声过滤是否按预期工作。</p>
        <p>第二段正文内容，继续验证段落顺序以及导航、页脚、推荐位等噪声不会混入正文。</p>
        <div class="related-articles">RELATED_NOISE</div>
      </article>
      <aside>ASIDE_NOISE</aside>
      <footer>FOOTER_NOISE</footer>
    </body></html>
    """;

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
  void extractsArticleTextWithProvenanceAndWithoutNoise() throws Exception {
    fixture.route("/article", FixtureHttpServer.Response.html(ARTICLE_HTML));

    NormalizedContent content = extractor(defaults()).extract(
      fixture.url("/article")
    );

    assertEquals(IngestionOutcome.FETCHED, content.outcome());
    assertNull(content.failure());
    assertTrue(content.fetched());
    assertEquals("真实标题", content.title());
    assertEquals(
      fixture.url("/article"), content.originalUrl()
    );
    assertEquals(fixture.url("/canonical/article"), content.canonicalUrl());
    assertEquals("127.0.0.1", content.domain());
    assertEquals("text/html", content.contentType());
    assertNotNull(content.fetchedAt());
    assertEquals(fixture.url("/article"), content.metadata().get("finalUrl"));
    assertEquals("0", content.metadata().get("redirects"));

    String text = content.mainText();
    assertTrue(text.contains("第一段正文内容"), text);
    assertTrue(text.contains("第二段正文内容"), text);
    assertTrue(
      text.indexOf("第一段正文内容") < text.indexOf("第二段正文内容"),
      "paragraph order must be preserved"
    );
    assertFalse(text.contains("NAVIGATION_NOISE"));
    assertFalse(text.contains("HEADER_NOISE"));
    assertFalse(text.contains("FOOTER_NOISE"));
    assertFalse(text.contains("ASIDE_NOISE"));
    assertFalse(text.contains("RELATED_NOISE"));
    assertFalse(text.contains("SCRIPT_NOISE"));
    assertFalse(text.contains("<p>"), "raw HTML must never be stored as body");
  }

  @Test
  void supportsPlainTextResponses() throws Exception {
    fixture.route(
      "/plain",
      FixtureHttpServer.Response.text(
        "纯文本正文，用于验证纯文本内容类型可以进入正文抽取流程，并且不会被 HTML 清洗逻辑破坏结构。" +
        "第二句继续补足长度，确保超过最小正文阈值，从而走通 FETCHED 路径而不是降级为需要粘贴正文。"
      )
    );

    NormalizedContent content = extractor(defaults()).extract(fixture.url("/plain"));

    assertEquals(IngestionOutcome.FETCHED, content.outcome());
    assertEquals("text/plain", content.contentType());
    assertTrue(content.mainText().contains("纯文本正文"));
    assertNull(content.title(), "plain text has no reliable title");
  }

  @Test
  void marksThinContentAsExtractionFailedWithPasteHint() throws Exception {
    fixture.route(
      "/js-only",
      FixtureHttpServer.Response.html(
        "<html><body><div id=\"app\"></div><p>加载中</p></body></html>"
      )
    );

    NormalizedContent content = extractor(defaults()).extract(
      fixture.url("/js-only")
    );

    assertEquals(IngestionOutcome.EXTRACTION_FAILED, content.outcome());
    assertEquals(IngestionFailure.EXTRACTION_FAILED, content.failure());
    assertFalse(content.hasText());
    assertTrue(content.message().contains("EXTRACTION_FAILED"));
    assertTrue(content.message().contains("粘贴正文"));
  }

  @Test
  void mapsPaywallResponsesToBlocked() throws Exception {
    fixture.route(
      "/paywall",
      FixtureHttpServer.Response.status(
        402,
        "text/html",
        "<html><body>请订阅后阅读全文</body></html>"
      )
    );

    NormalizedContent content = extractor(defaults()).extract(
      fixture.url("/paywall")
    );

    assertEquals(IngestionOutcome.BLOCKED, content.outcome());
    assertEquals(IngestionFailure.ACCESS_BLOCKED, content.failure());
    assertFalse(content.hasText());
    assertTrue(content.message().contains("粘贴正文"));
  }

  @Test
  void blocksRedirectToPrivateDestination() throws Exception {
    fixture.route(
      "/internal",
      FixtureHttpServer.Response.redirect("http://10.9.8.7/private")
    );

    NormalizedContent content = extractor(defaults()).extract(
      fixture.url("/internal")
    );

    assertEquals(IngestionOutcome.BLOCKED, content.outcome());
    assertEquals(
      IngestionFailure.REDIRECT_TO_UNSAFE_DESTINATION,
      content.failure()
    );
  }

  @Test
  void rejectsOversizedResponsesAsUnsupported() throws Exception {
    fixture.route(
      "/big",
      FixtureHttpServer.Response.html("z".repeat(50_000))
    );

    NormalizedContent content = extractor(properties(1024, 100_000, 80)).extract(
      fixture.url("/big")
    );

    assertEquals(IngestionOutcome.UNSUPPORTED, content.outcome());
    assertEquals(IngestionFailure.RESPONSE_TOO_LARGE, content.failure());
    assertTrue(content.message().contains("粘贴正文"));
  }

  @Test
  void rejectsUnsupportedContentTypesAsUnsupported() throws Exception {
    fixture.route(
      "/video",
      FixtureHttpServer.Response.bytes(
        200,
        "video/mp4",
        new byte[] { 0, 0, 0, 24 },
        Map.of()
      )
    );

    NormalizedContent content = extractor(defaults()).extract(
      fixture.url("/video")
    );

    assertEquals(IngestionOutcome.UNSUPPORTED, content.outcome());
    assertEquals(
      IngestionFailure.UNSUPPORTED_CONTENT_TYPE,
      content.failure()
    );
  }

  @Test
  void rejectsPrivateDestinationsWithoutAnyFetch() {
    NormalizedContent metadata = productionExtractor().extract(
      "http://169.254.169.254/latest/meta-data/"
    );
    assertEquals(IngestionOutcome.BLOCKED, metadata.outcome());
    assertEquals(IngestionFailure.UNSAFE_DESTINATION, metadata.failure());

    NormalizedContent loopback = productionExtractor().extract(
      "http://127.0.0.1/private"
    );
    assertEquals(IngestionFailure.UNSAFE_DESTINATION, loopback.failure());

    NormalizedContent highPort = productionExtractor().extract(
      "http://example.com:8080/article"
    );
    assertEquals(IngestionFailure.INVALID_URL, highPort.failure());
  }

  @Test
  void truncatesLongTextAtTheConfiguredLimit() throws Exception {
    fixture.route(
      "/long",
      FixtureHttpServer.Response.html(
        "<html><body><article><p>" +
        "长文本内容。".repeat(300) +
        "</p></article></body></html>"
      )
    );

    NormalizedContent content = extractor(properties(64 * 1024, 300, 80)).extract(
      fixture.url("/long")
    );

    assertEquals(IngestionOutcome.FETCHED, content.outcome());
    assertEquals(300, content.mainText().length());
  }

  @Test
  void neverLeaksCredentialsOrExceptionDetail() {
    NormalizedContent credentials = productionExtractor().extract(
      "http://user:supersecret@example.com/article"
    );
    assertEquals(IngestionFailure.INVALID_URL, credentials.failure());
    assertFalse(credentials.message().contains("supersecret"));
    assertFalse(credentials.message().contains("user:"));

    NormalizedContent scheme = productionExtractor().extract(
      "file:///etc/passwd"
    );
    assertEquals(IngestionFailure.INVALID_URL, scheme.failure());
    assertEquals(IngestionOutcome.UNSUPPORTED, scheme.outcome());
    assertFalse(scheme.message().toLowerCase().contains("exception"));
    assertFalse(scheme.message().contains("/etc/passwd"));
  }

  private PublicWebContentExtractor extractor(NewsFetchProperties properties) {
    return new PublicWebContentExtractor(
      new SafeHttpFetcher(new SystemHostResolver(), fixturePolicy, properties),
      new HtmlArticleExtractor(),
      properties
    );
  }

  private static PublicWebContentExtractor productionExtractor() {
    NewsFetchProperties properties = defaults();
    return new PublicWebContentExtractor(
      new SafeHttpFetcher(
        new SystemHostResolver(),
        new PublicDestinationPolicy(),
        properties
      ),
      new HtmlArticleExtractor(),
      properties
    );
  }

  private static NewsFetchProperties defaults() {
    return properties(64 * 1024, 100_000, 80);
  }

  private static NewsFetchProperties properties(
    int maxResponseBytes,
    int maxTextChars,
    int minTextChars
  ) {
    return new NewsFetchProperties(
      Duration.ofMillis(500),
      Duration.ofSeconds(2),
      Duration.ofSeconds(4),
      3,
      maxResponseBytes,
      maxTextChars,
      minTextChars,
      "SignalFrame-Test/0.1",
      List.of("text/html", "application/xhtml+xml", "text/plain")
    );
  }
}
