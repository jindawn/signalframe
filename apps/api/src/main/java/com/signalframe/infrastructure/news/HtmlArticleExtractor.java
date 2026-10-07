package com.signalframe.infrastructure.news;

import com.signalframe.news.domain.IngestionException;
import com.signalframe.news.domain.IngestionFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Content extraction: turns a fetched HTML or plain-text payload into
 * normalized article text. No HTTP, no persistence.
 *
 * <p>MVP quality bar: drop obvious non-content (scripts, styles, navigation,
 * footers, sharing/related/ad containers), keep block order, and never store
 * raw HTML as the body. It is intentionally not a readability clone.
 */
@Component
public class HtmlArticleExtractor {

  /** Element-level noise that is never part of an article body. */
  private static final String NOISE_ELEMENTS =
    "script,style,noscript,template,svg,canvas,iframe,form,button,input,select,textarea,nav,footer,header,aside";

  /** Container markers for recommendation/ad/consent boxes. Kept conservative. */
  private static final List<String> NOISE_MARKERS = List.of(
    "advert",
    "cookie",
    "newsletter",
    "sidebar",
    "related",
    "recommend",
    "sponsor",
    "promo",
    "subscribe",
    "paywall"
  );

  private static final String BLOCK_SELECTOR =
    "p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,figcaption,td";

  /** Below this, paragraph extraction is considered too thin and body text is used. */
  private static final int MIN_PARAGRAPH_TEXT = 200;
  private static final int MAX_TITLE_CHARS = 240;

  public record ExtractedArticle(
    String title,
    String mainText,
    String canonicalUrl
  ) {
    public ExtractedArticle {
      mainText = mainText == null ? "" : mainText;
    }
  }

  public ExtractedArticle extract(
    byte[] body,
    String contentType,
    String charsetHeader,
    String baseUri
  ) {
    if (isPlainText(contentType)) return new ExtractedArticle(
      null,
      normalizePlainText(decode(body, charsetHeader)),
      null
    );
    try {
      Document document = Jsoup.parse(
        new ByteArrayInputStream(body == null ? new byte[0] : body),
        charsetName(charsetHeader),
        baseUri
      );
      return extractHtml(document);
    } catch (IOException e) {
      throw new IngestionException(IngestionFailure.EXTRACTION_FAILED, e);
    }
  }

  private ExtractedArticle extractHtml(Document document) {
    // Capture head-level provenance before removing noise containers.
    String title = firstNonBlank(
      metaContent(document, "meta[property=og:title]"),
      text(document.selectFirst("h1")),
      document.title()
    );
    String canonical = canonicalUrl(document);

    document.select(NOISE_ELEMENTS).remove();
    removeNoiseContainers(document);

    Element container = firstElement(
      document.selectFirst("article"),
      document.selectFirst("main"),
      document.body()
    );
    String text = container == null ? "" : blockText(container);
    return new ExtractedArticle(truncateTitle(title), text, canonical);
  }

  /**
   * Joins top-level block text with blank lines so paragraph order survives,
   * skipping blocks nested inside an already collected block.
   */
  private static String blockText(Element container) {
    Elements blocks = container.select(BLOCK_SELECTOR);
    Set<Element> kept = Collections.newSetFromMap(new IdentityHashMap<>());
    List<String> paragraphs = new ArrayList<>();
    for (Element block : blocks) {
      boolean nested = false;
      for (
        Element parent = block.parent();
        parent != null;
        parent = parent.parent()
      ) if (kept.contains(parent)) {
        nested = true;
        break;
      }
      if (nested) continue;
      String text = normalizeInline(block.text());
      if (text.isEmpty()) continue;
      kept.add(block);
      if (!paragraphs.isEmpty() && paragraphs.getLast().equals(text)) continue;
      paragraphs.add(text);
    }
    String joined = String.join("\n\n", paragraphs);
    if (joined.length() >= MIN_PARAGRAPH_TEXT) return joined;
    String fallback = normalizeInline(container.text());
    return fallback.length() > joined.length() ? fallback : joined;
  }

  private static void removeNoiseContainers(Document document) {
    for (Element element : document.select("[class],[id]")) {
      if (element.parent() == null) continue; // already detached with an ancestor
      String marker = (element.className() + " " + element.id())
        .toLowerCase(Locale.ROOT);
      for (String noise : NOISE_MARKERS) if (marker.contains(noise)) {
        element.remove();
        break;
      }
    }
  }

  private static String canonicalUrl(Document document) {
    Element link = document.selectFirst("link[rel=canonical]");
    if (link == null) return null;
    String href = link.absUrl("href");
    if (href == null || href.isBlank()) return null;
    try {
      URI uri = URI.create(href.trim());
      String scheme = uri.getScheme();
      if (
        scheme == null ||
        !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) ||
        uri.getHost() == null ||
        uri.getUserInfo() != null
      ) return null;
      return uri.toString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String decode(byte[] body, String charsetHeader) {
    if (body == null) return "";
    Charset charset = validCharset(charsetHeader);
    String text = new String(body, charset == null ? StandardCharsets.UTF_8 : charset);
    return text.startsWith("\uFEFF") ? text.substring(1) : text;
  }

  private static String charsetName(String charsetHeader) {
    Charset charset = validCharset(charsetHeader);
    return charset == null ? null : charset.name();
  }

  private static Charset validCharset(String name) {
    if (name == null || name.isBlank()) return null;
    try {
      return Charset.isSupported(name) ? Charset.forName(name) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static boolean isPlainText(String contentType) {
    return contentType != null && contentType
      .toLowerCase(Locale.ROOT)
      .startsWith("text/plain");
  }

  private static String normalizeInline(String text) {
    if (text == null) return "";
    return text
      .replace('\u00a0', ' ')
      .replaceAll("\\p{Cntrl}", " ")
      .replaceAll("\\s+", " ")
      .trim();
  }

  private static String normalizePlainText(String text) {
    if (text == null) return "";
    return text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace('\u00a0', ' ')
      .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ")
      .replaceAll("[ \\t]+", " ")
      .replaceAll("\n{3,}", "\n\n")
      .trim();
  }

  private static String truncateTitle(String title) {
    String value = normalizeInline(title);
    if (value.isEmpty()) return null;
    return value.length() > MAX_TITLE_CHARS
      ? value.substring(0, MAX_TITLE_CHARS)
      : value;
  }

  private static String metaContent(Document document, String selector) {
    Element meta = document.selectFirst(selector);
    return meta == null ? null : meta.attr("content");
  }

  private static String text(Element element) {
    return element == null ? null : element.text();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return null;
  }

  private static Element firstElement(Element... elements) {
    for (Element element : elements) if (element != null) return element;
    return null;
  }
}
