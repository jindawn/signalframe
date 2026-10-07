package com.signalframe.news;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.NewsInput;
import com.signalframe.contract.NewsItem;
import com.signalframe.news.application.NewsService;
import com.signalframe.news.domain.ContentExtractor;
import com.signalframe.news.domain.IngestionFailure;
import com.signalframe.news.domain.IngestionOutcome;
import com.signalframe.news.domain.NormalizedContent;
import com.signalframe.news.domain.NewsRepository;
import com.signalframe.shared.ApplicationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Paste-text regression and ingestion-status mapping tests. No Spring, no
 * network: the extractor is stubbed with explicit ingestion results.
 */
class NewsServiceTest {

  private static final String URL = "https://news.example.com/article";

  @Test
  void pastedTextOnlyStillCreatesAPastedSource() {
    InMemoryRepository repository = new InMemoryRepository();
    StubExtractor extractor = new StubExtractor(null);
    NewsService service = new NewsService(repository, extractor);

    NewsItem item = service.create(
      new NewsInput(null, "  第一行\n\n第二行  ", null)
    );

    assertEquals("PASTED", item.source().extractionStatus());
    assertEquals("第一行\n\n第二行", item.source().text());
    assertNull(item.source().url());
    assertNull(item.source().message());
    assertEquals("第一行 第二行", item.title());
    assertTrue(extractor.calls.isEmpty());
    assertEquals(1, repository.recent().size());
  }

  @Test
  void successfulExtractionCreatesAnExtractedSourceWithProvenance() {
    InMemoryRepository repository = new InMemoryRepository();
    StubExtractor extractor = new StubExtractor(
      extracted("网页标题", "网页正文内容。")
    );
    NewsService service = new NewsService(repository, extractor);

    NewsItem item = service.create(new NewsInput(URL, null, null));

    assertEquals("EXTRACTED", item.source().extractionStatus());
    assertEquals(URL, item.source().url());
    assertEquals("网页正文内容。", item.source().text());
    assertEquals("网页标题", item.title());
    assertTrue(item.source().message().contains("news.example.com"));
    assertEquals(List.of(URL), extractor.calls);
  }

  @Test
  void combinesExtractedTextAndSupplementWithAnExplicitBoundary() {
    InMemoryRepository repository = new InMemoryRepository();
    StubExtractor extractor = new StubExtractor(
      extracted("网页标题", "网页正文内容。")
    );
    NewsService service = new NewsService(repository, extractor);

    NewsItem item = service.create(
      new NewsInput(URL, "用户补充的正文内容。", null)
    );

    String text = item.source().text();
    assertEquals("EXTRACTED", item.source().extractionStatus());
    assertEquals(URL, item.source().url(), "URL provenance is retained");
    assertTrue(text.startsWith("网页正文内容。"));
    assertTrue(text.contains("用户补充："), "boundary marker is retained");
    assertTrue(text.endsWith("用户补充的正文内容。"));
    assertTrue(
      text.indexOf("网页正文内容。") < text.indexOf("用户补充的正文内容。")
    );
  }

  @Test
  void failedUrlWithoutTextStaysNeedsText() {
    InMemoryRepository repository = new InMemoryRepository();
    StubExtractor extractor = new StubExtractor(
      NormalizedContent.failed(
        URL,
        "news.example.com",
        IngestionFailure.UNSAFE_DESTINATION
      )
    );
    NewsService service = new NewsService(repository, extractor);

    NewsItem item = service.create(new NewsInput(URL, null, null));

    assertEquals("NEEDS_TEXT", item.source().extractionStatus());
    assertEquals("", item.source().text());
    assertTrue(item.source().message().contains("UNSAFE_DESTINATION"));
    assertTrue(item.source().message().contains("粘贴正文"));
    assertEquals(URL, item.source().url());
  }

  @Test
  void failedUrlWithPastedTextKeepsTheTextAnalyzableAndRecordsTheFailure() {
    InMemoryRepository repository = new InMemoryRepository();
    StubExtractor extractor = new StubExtractor(
      NormalizedContent.failed(
        URL,
        "news.example.com",
        IngestionFailure.ACCESS_BLOCKED
      )
    );
    NewsService service = new NewsService(repository, extractor);

    NewsItem item = service.create(new NewsInput(URL, "用户粘贴的正文。", null));

    assertEquals("PASTED", item.source().extractionStatus());
    assertEquals("用户粘贴的正文。", item.source().text());
    assertTrue(item.source().message().contains("ACCESS_BLOCKED"));
    assertFalse(item.source().message().contains("Exception"));
  }

  @Test
  void rejectsRequestsWithoutUrlOrText() {
    NewsService service = new NewsService(
      new InMemoryRepository(),
      new StubExtractor(null)
    );

    ApplicationException failure = assertThrows(
      ApplicationException.class,
      () -> service.create(new NewsInput(null, "   ", null))
    );

    assertEquals(400, failure.status());
    assertEquals("INPUT_REQUIRED", failure.code());
  }

  @Test
  void rejectsUnsupportedSchemesAndCredentials() {
    NewsService service = new NewsService(
      new InMemoryRepository(),
      new StubExtractor(null)
    );

    for (String url : List.of(
      "ftp://example.com/article",
      "not-a-url",
      "http://user:secret@example.com/article"
    )) {
      ApplicationException failure = assertThrows(
        ApplicationException.class,
        () -> service.create(new NewsInput(url, "纯文本正文", null)),
        url
      );
      assertEquals(400, failure.status(), url);
      assertEquals("INVALID_URL", failure.code(), url);
    }
  }

  @Test
  void trimsUrlBeforeExtractionAndKeepsInnerTextWhitespace() {
    InMemoryRepository repository = new InMemoryRepository();
    StubExtractor extractor = new StubExtractor(
      extracted("网页标题", "网页正文内容。")
    );
    NewsService service = new NewsService(repository, extractor);

    NewsItem item = service.create(
      new NewsInput("  " + URL + "  ", "  补充文字\n\n保留内部换行  ", null)
    );

    assertEquals(List.of(URL), extractor.calls);
    assertEquals(URL, item.source().url());
    assertTrue(item.source().text().endsWith("补充文字\n\n保留内部换行"));
  }

  @Test
  void userSuppliedTitleWinsOverTheExtractedTitle() {
    NewsService service = new NewsService(
      new InMemoryRepository(),
      new StubExtractor(extracted("网页标题", "网页正文内容。"))
    );

    NewsItem item = service.create(new NewsInput(URL, null, "自定义标题"));

    assertEquals("自定义标题", item.title());
  }

  private static NormalizedContent extracted(String title, String text) {
    return new NormalizedContent(
      title,
      text,
      URL,
      URL,
      "news.example.com",
      Instant.parse("2026-10-07T00:00:00Z"),
      "text/html",
      IngestionOutcome.FETCHED,
      null,
      "URL 抽取成功 · news.example.com",
      Map.of("finalUrl", URL)
    );
  }

  private static final class StubExtractor implements ContentExtractor {

    private final NormalizedContent result;
    private final List<String> calls = new ArrayList<>();

    StubExtractor(NormalizedContent result) {
      this.result = result;
    }

    @Override
    public NormalizedContent extract(String url) {
      calls.add(url);
      return result;
    }
  }

  private static final class InMemoryRepository implements NewsRepository {

    private final Map<UUID, NewsItem> items = new LinkedHashMap<>();

    @Override
    public void save(NewsItem item) {
      items.put(item.id(), item);
    }

    @Override
    public Optional<NewsItem> find(UUID id) {
      return Optional.ofNullable(items.get(id));
    }

    @Override
    public List<NewsItem> recent() {
      return List.copyOf(items.values());
    }
  }
}
