package com.signalframe.news.application;

import com.signalframe.contract.*;
import com.signalframe.news.domain.*;
import com.signalframe.shared.ApplicationException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Persistence orchestration for news input: one request becomes one Source and
 * one NewsItem, no matter whether the body came from a paste or a best-effort
 * URL fetch.
 *
 * <p>Ingestion status mapping (canonical contract vocabulary):
 *
 * <ul>
 *   <li>{@code EXTRACTED} — URL fetch and extraction produced usable text. A
 *       user supplement, if any, is appended behind an explicit boundary.
 *   <li>{@code NEEDS_TEXT} — the URL produced nothing usable and no text was
 *       supplied: the source is deliberately unusable until the caller pastes
 *       text. This mirrors {@code AnalysisJobService}, which refuses sources
 *       with a blank body.
 *   <li>{@code PASTED} — the body is user-supplied. This includes the case
 *       where a URL was attempted but failed while the caller also supplied
 *       text: the pasted text is kept analyzable and the failure category is
 *       preserved in {@code Source.message} instead of being silently dropped.
 * </ul>
 */
@Service
public class NewsService {

  /** Boundary marker between extracted and user-supplied text in one source. */
  static final String SUPPLEMENT_MARKER = "\n\n用户补充：\n";
  static final String STATUS_PASTED = "PASTED";
  static final String STATUS_EXTRACTED = "EXTRACTED";
  static final String STATUS_NEEDS_TEXT = "NEEDS_TEXT";
  private static final int TITLE_PREVIEW_CHARS = 80;

  private final NewsRepository repository;
  private final ContentExtractor extractor;

  public NewsService(NewsRepository r, ContentExtractor e) {
    repository = r;
    extractor = e;
  }

  public NewsItem create(NewsInput input) {
    String pasted = trimToNull(input.text());
    String url = trimToNull(input.url());
    if (pasted == null && url == null) throw new ApplicationException(
      400,
      "INPUT_REQUIRED",
      "请输入 URL 或新闻正文。"
    );
    if (url != null) requireFetchableUrl(url);

    NormalizedContent content = url == null ? null : extractor.extract(url);

    String text;
    String status;
    String message;
    if (content == null) {
      text = pasted;
      status = STATUS_PASTED;
      message = null;
    } else if (content.fetched()) {
      text = pasted == null
        ? content.mainText()
        : content.mainText() + SUPPLEMENT_MARKER + pasted;
      status = STATUS_EXTRACTED;
      message = content.message();
    } else if (pasted == null) {
      // Nothing usable was produced: stay NEEDS_TEXT with an actionable message.
      text = "";
      status = STATUS_NEEDS_TEXT;
      message = content.message();
    } else {
      // URL used nothing, but the caller did supply text: keep it analyzable and
      // record why the URL was not used (never drop the failure category).
      text = pasted;
      status = STATUS_PASTED;
      message = unusedUrlMessage(content);
    }
    Instant now = Instant.now();
    var source = new Source(UUID.randomUUID(), url, text, status, message, now);
    var item = new NewsItem(
      UUID.randomUUID(),
      title(input, content, text, url),
      source,
      DomainType.OTHER,
      now
    );
    repository.save(item);
    return item;
  }

  public NewsItem get(UUID id) {
    return repository.find(id).orElseThrow(ApplicationException::missing);
  }

  public List<NewsItem> recent() {
    return repository.recent();
  }

  /**
   * API-level URL gate: shape and credentials only. Destinations that must not
   * be fetched (private addresses, unsupported ports) are refused later by the
   * extraction policy, which turns them into an explicit NEEDS_TEXT status
   * instead of a request error.
   */
  private static void requireFetchableUrl(String url) {
    try {
      URI uri = URI.create(url);
      String scheme = uri.getScheme();
      if (
        scheme == null ||
        !(
          scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")
        ) ||
        uri.getHost() == null ||
        uri.getHost().isBlank() ||
        uri.getUserInfo() != null
      ) throw new IllegalArgumentException("not fetchable");
    } catch (RuntimeException e) {
      throw new ApplicationException(
        400,
        "INVALID_URL",
        "URL 必须使用 http 或 https，且不能包含用户名或密码。"
      );
    }
  }

  private static String unusedUrlMessage(NormalizedContent content) {
    IngestionFailure failure = content.failure() == null
      ? IngestionFailure.EXTRACTION_FAILED
      : content.failure();
    return (
      "URL 未使用（" +
      failure.code() +
      " · " +
      failure.userMessage() +
      "）· 已保留你粘贴的正文并继续分析。"
    );
  }

  private static String title(
    NewsInput input,
    NormalizedContent content,
    String text,
    String url
  ) {
    if (input.title() != null && !input.title().isBlank()) return truncateTitle(
      input.title().trim()
    );
    if (
      content != null && content.title() != null && !content.title().isBlank()
    ) return truncateTitle(content.title().trim());
    if (text != null && !text.isBlank()) {
      String preview = text
        .substring(0, Math.min(TITLE_PREVIEW_CHARS, text.length()))
        .replaceAll("\\s+", " ")
        .trim();
      return truncateTitle(preview.isBlank() ? text.trim() : preview);
    }
    return url;
  }

  private static String truncateTitle(String title) {
    return title.length() > 240 ? title.substring(0, 240) : title;
  }

  private static String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
