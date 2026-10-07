package com.signalframe.infrastructure.news;

import com.signalframe.news.domain.ContentExtractor;
import com.signalframe.news.domain.IngestionException;
import com.signalframe.news.domain.IngestionFailure;
import com.signalframe.news.domain.IngestionOutcome;
import com.signalframe.news.domain.NormalizedContent;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort public-web ingestion: fetch → extract → normalize.
 *
 * <p>Composition root for URL ingestion. HTTP concerns live in
 * {@link SafeHttpFetcher} (bounded, SSRF-checked, pinned-address connection),
 * content extraction lives in {@link HtmlArticleExtractor}, and this class only
 * turns the two into a {@link NormalizedContent} plus an audit log line.
 *
 * <p>It never fabricates text: any failure becomes a categorized
 * {@link IngestionFailure} with an actionable message, so the caller can fall
 * back to pasted text.
 */
@Component
public class PublicWebContentExtractor implements ContentExtractor {

  private static final Logger log = LoggerFactory.getLogger(
    PublicWebContentExtractor.class
  );
  private static final PublicDestinationPolicy PUBLIC_POLICY =
    new PublicDestinationPolicy();

  private final SafeHttpFetcher fetcher;
  private final HtmlArticleExtractor articles;
  private final NewsFetchProperties properties;

  public PublicWebContentExtractor(
    SafeHttpFetcher fetcher,
    HtmlArticleExtractor articles,
    NewsFetchProperties properties
  ) {
    this.fetcher = fetcher;
    this.articles = articles;
    this.properties = properties;
  }

  @Override
  public NormalizedContent extract(String url) {
    String originalUrl = url == null ? "" : url.trim();
    String host = PublicDestinationPolicy.safeHost(originalUrl);
    long startedAt = System.nanoTime();
    try {
      FetchedPage page = fetcher.fetch(originalUrl);
      Instant fetchedAt = Instant.now();
      HtmlArticleExtractor.ExtractedArticle article = articles.extract(
        page.body(),
        page.contentType(),
        page.charset(),
        page.finalUri().toString()
      );
      String text = truncate(article.mainText());
      if (text.length() < properties.minTextChars()) return failure(
        originalUrl,
        host,
        IngestionFailure.EXTRACTION_FAILED,
        startedAt
      );
      String canonical = sameHostCanonical(article.canonicalUrl(), host);
      NormalizedContent content = new NormalizedContent(
        article.title(),
        text,
        originalUrl,
        canonical,
        host,
        fetchedAt,
        page.contentType(),
        IngestionOutcome.FETCHED,
        null,
        fetchedMessage(host, fetchedAt, page, canonical),
        Map.of(
          "finalUrl",
          page.finalUri().toString(),
          "redirects",
          String.valueOf(page.redirects()),
          "bytes",
          String.valueOf(page.body().length)
        )
      );
      log.info(
        "news.ingest host={} outcome=FETCHED status={} contentType={} redirects={} bytes={} latencyMs={}",
        host,
        page.status(),
        page.contentType(),
        page.redirects(),
        page.body().length,
        elapsedMillis(startedAt)
      );
      return content;
    } catch (IngestionException e) {
      return failure(originalUrl, host, e.failure(), startedAt);
    } catch (RuntimeException e) {
      // Defensive: ingestion must degrade to NEEDS_TEXT, never surface a stack trace.
      log.warn(
        "news.ingest host={} outcome=EXTRACTION_FAILED failure={} type={} latencyMs={}",
        host,
        IngestionFailure.EXTRACTION_FAILED.code(),
        e.getClass().getSimpleName(),
        elapsedMillis(startedAt)
      );
      return NormalizedContent.failed(
        originalUrl,
        host,
        IngestionFailure.EXTRACTION_FAILED
      );
    }
  }

  private NormalizedContent failure(
    String originalUrl,
    String host,
    IngestionFailure failure,
    long startedAt
  ) {
    log.info(
      "news.ingest host={} outcome={} failure={} latencyMs={}",
      host,
      failure.outcome(),
      failure.code(),
      elapsedMillis(startedAt)
    );
    return NormalizedContent.failed(originalUrl, host, failure);
  }

  private String truncate(String text) {
    String value = text == null ? "" : text.trim();
    int max = properties.maxTextChars();
    if (value.length() <= max) return value;
    int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
    return value.substring(0, end);
  }

  private static String fetchedMessage(
    String host,
    Instant fetchedAt,
    FetchedPage page,
    String canonical
  ) {
    StringBuilder message = new StringBuilder("URL 抽取成功 · ")
      .append(host == null ? "unknown" : host)
      .append(" · ")
      .append(page.contentType())
      .append(" · ")
      .append(fetchedAt)
      .append(" · 最佳努力提取，请核对原文。");
    if (canonical != null && !canonical.isBlank()) message
      .append(" · canonical: ")
      .append(canonical);
    return message.toString();
  }

  /** Canonical URLs are informative only; keep them on the same host. */
  private static String sameHostCanonical(String canonicalUrl, String host) {
    if (canonicalUrl == null || canonicalUrl.isBlank() || host == null) return null;
    try {
      String canonicalHost = PublicDestinationPolicy.hostName(
        URI.create(canonicalUrl)
      );
      return canonicalHost != null && canonicalHost.equalsIgnoreCase(host)
        ? canonicalUrl
        : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }

  /**
   * Static SSRF gate for callers without a Spring context (kept for the
   * architecture test): rejects unsupported schemes, credentials, non-default
   * ports and any destination that resolves to a non-public address.
   */
  public static void validate(URI uri) throws Exception {
    PUBLIC_POLICY.verifyShape(uri);
    List<InetAddress> resolved = List.of(
      InetAddress.getAllByName(PUBLIC_POLICY.hostName(uri))
    );
    PUBLIC_POLICY.verifyAddresses(uri, resolved);
  }
}
