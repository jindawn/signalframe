package com.signalframe.news.application;

import com.signalframe.contract.*;
import com.signalframe.news.domain.*;
import com.signalframe.shared.ApplicationException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NewsService {

  private final NewsRepository repository;
  private final ContentExtractor extractor;

  public NewsService(NewsRepository r, ContentExtractor e) {
    repository = r;
    extractor = e;
  }

  public NewsItem create(NewsInput input) {
    String text = input.text() == null ? "" : input.text().trim();
    String url = input.url() == null ? null : input.url().trim();
    if (url != null && url.isBlank()) url = null;
    if (text.isBlank() && url == null) throw new ApplicationException(
      400,
      "INPUT_REQUIRED",
      "请输入 URL 或新闻正文。"
    );
    if (url != null) {
      try {
        var uri = URI.create(url);
        if (
          uri.getHost() == null ||
          !(
            uri.getScheme().equalsIgnoreCase("https") ||
            uri.getScheme().equalsIgnoreCase("http")
          )
        ) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new ApplicationException(
          400,
          "INVALID_URL",
          "URL must use http or https"
        );
      }
    }
    String status = "PASTED",
      message = null;
    if (url != null) {
      var extracted = extractor.extract(url);
      status = extracted.status();
      message = extracted.message();
      if (!extracted.text().isBlank()) text =
        extracted.text() + (text.isBlank() ? "" : "\n\n用户补充：\n" + text);
      else if (!text.isBlank()) status = "PASTED";
    }
    Instant now = Instant.now();
    var source = new Source(UUID.randomUUID(), url, text, status, message, now);
    String title =
      input.title() == null || input.title().isBlank()
        ? text.isBlank()
          ? url
          : text.substring(0, Math.min(80, text.length()))
        : input.title().trim();
    var item = new NewsItem(
      UUID.randomUUID(),
      title,
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
}
