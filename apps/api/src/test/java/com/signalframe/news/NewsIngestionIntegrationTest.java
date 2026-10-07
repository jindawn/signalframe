package com.signalframe.news;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.NewsInput;
import com.signalframe.contract.NewsItem;
import com.signalframe.news.domain.DestinationPolicy;
import com.signalframe.news.support.FixtureDestinationPolicy;
import com.signalframe.news.support.FixtureHttpServer;
import com.signalframe.shared.JsonCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ingestion acceptance test through the real HTTP API, real Flyway schema and
 * real PostgreSQL (Testcontainers). Only the destination policy is replaced so a
 * loopback fixture can be fetched; redirect and private-address decisions still
 * run through the production policy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NewsIngestionIntegrationTest {

  private static final String ARTICLE_HTML =
    """
    <html><head><title>Fixture 站点</title></head><body>
      <article>
        <h1>Fixture 头条</h1>
        <p>第一段正文：这是一段用于验证真实 URL 抽取链路的测试文本，长度足够通过最小正文阈值，并保留段落顺序。</p>
        <p>第二段正文：继续验证来源追踪与段落顺序是否按预期工作，同时确认导航噪声不会混入抽取正文。</p>
      </article>
      <nav>NAV_NOISE</nav>
      <footer>FOOTER_NOISE</footer>
    </body></html>
    """;

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static final FixtureHttpServer fixture;

  static {
    postgres.start();
    try {
      fixture = new FixtureHttpServer();
      fixture.route("/article", FixtureHttpServer.Response.html(ARTICLE_HTML));
      fixture.route(
        "/paywall",
        FixtureHttpServer.Response.status(
          402,
          "text/html",
          "<html><body>请订阅后阅读全文</body></html>"
        )
      );
      fixture.route(
        "/jump-private",
        FixtureHttpServer.Response.redirect("http://10.1.2.3/private")
      );
    } catch (IOException e) {
      throw new IllegalStateException("fixture server", e);
    }
  }

  @AfterAll
  static void stopFixture() {
    fixture.close();
  }

  @TestConfiguration
  static class FixturePolicyConfiguration {

    @Bean
    @Primary
    DestinationPolicy fixtureDestinationPolicy() {
      return new FixtureDestinationPolicy("127.0.0.1");
    }
  }

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

  @Test
  void extractsFixtureUrlAndPersistsProvenance() throws Exception {
    var created = post(
      "/api/v1/news",
      new NewsInput(fixture.url("/article"), null, null)
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem item = json.read(created.body(), NewsItem.class);

    assertEquals("EXTRACTED", item.source().extractionStatus());
    assertEquals(fixture.url("/article"), item.source().url());
    assertEquals("Fixture 头条", item.title());
    assertTrue(item.source().text().contains("第一段正文"));
    assertTrue(item.source().text().contains("第二段正文"));
    assertFalse(item.source().text().contains("NAV_NOISE"));
    assertFalse(item.source().text().contains("FOOTER_NOISE"));
    assertTrue(item.source().message().contains("127.0.0.1"));

    NewsItem reloaded = json.read(get("/api/v1/news/" + item.id()).body(), NewsItem.class);
    assertEquals(item.source().id(), reloaded.source().id());
    assertEquals("EXTRACTED", reloaded.source().extractionStatus());
    assertEquals(fixture.url("/article"), reloaded.source().url());
    assertTrue(reloaded.source().text().contains("第二段正文"));
  }

  @Test
  void combinesUrlTextAndSupplementWithRetainedProvenance() throws Exception {
    var created = post(
      "/api/v1/news",
      new NewsInput(fixture.url("/article"), "用户补充的正文内容。", null)
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem item = json.read(created.body(), NewsItem.class);

    assertEquals("EXTRACTED", item.source().extractionStatus());
    assertEquals(fixture.url("/article"), item.source().url());
    assertTrue(item.source().text().contains("第一段正文"));
    assertTrue(item.source().text().contains("用户补充："));
    assertTrue(item.source().text().endsWith("用户补充的正文内容。"));
  }

  @Test
  void blockedUrlFallsBackToNeedsTextAndPasteContinues() throws Exception {
    var blocked = post(
      "/api/v1/news",
      new NewsInput(fixture.url("/paywall"), null, null)
    );
    assertEquals(201, blocked.statusCode(), blocked.body());
    NewsItem needsText = json.read(blocked.body(), NewsItem.class);

    assertEquals("NEEDS_TEXT", needsText.source().extractionStatus());
    assertTrue(needsText.source().text().isEmpty());
    assertTrue(needsText.source().message().contains("ACCESS_BLOCKED"));
    assertTrue(needsText.source().message().contains("粘贴正文"));
    assertEquals(
      409,
      post("/api/v1/news/" + needsText.id() + "/analyze", null).statusCode(),
      "a source without text must not be analyzable"
    );

    var pasted = post(
      "/api/v1/news",
      new NewsInput(null, "补充新闻原文：URL 降级后继续分析的测试文本。", null)
    );
    assertEquals(201, pasted.statusCode(), pasted.body());
    NewsItem pastedItem = json.read(pasted.body(), NewsItem.class);
    assertEquals("PASTED", pastedItem.source().extractionStatus());
    assertEquals(
      202,
      post("/api/v1/news/" + pastedItem.id() + "/analyze", null).statusCode(),
      "pasted text must remain analyzable after a URL failure"
    );
  }

  @Test
  void redirectToPrivateDestinationIsBlocked() throws Exception {
    var created = post(
      "/api/v1/news",
      new NewsInput(fixture.url("/jump-private"), null, null)
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem item = json.read(created.body(), NewsItem.class);

    assertEquals("NEEDS_TEXT", item.source().extractionStatus());
    assertTrue(item.source().message().contains("REDIRECT_TO_UNSAFE_DESTINATION"));
    assertTrue(item.source().message().contains("粘贴正文"));
  }

  @Test
  void privateDestinationIsBlockedWithoutNetworkAccess() throws Exception {
    var created = post(
      "/api/v1/news",
      new NewsInput("http://10.1.2.3/private", null, null)
    );
    assertEquals(201, created.statusCode(), created.body());
    NewsItem item = json.read(created.body(), NewsItem.class);

    assertEquals("NEEDS_TEXT", item.source().extractionStatus());
    assertTrue(item.source().message().contains("UNSAFE_DESTINATION"));
    assertFalse(item.source().message().contains("Exception"));
  }

  @Test
  void credentialedUrlIsRejectedAtTheApiBoundary() throws Exception {
    var rejected = post(
      "/api/v1/news",
      new NewsInput("http://user:supersecret@example.com/article", null, null)
    );

    assertEquals(400, rejected.statusCode());
    assertTrue(rejected.body().contains("INVALID_URL"));
    assertFalse(rejected.body().contains("supersecret"));
  }

  HttpResponse<String> post(String path, Object body) throws Exception {
    return request("POST", path, body);
  }

  HttpResponse<String> get(String path) throws Exception {
    return request("GET", path, null);
  }

  private HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    )
      .timeout(Duration.ofSeconds(20))
      .header("X-Request-ID", "ingestion-integration");
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
