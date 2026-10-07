package com.signalframe.infrastructure.news;

import com.signalframe.news.domain.ContentExtractor;
import java.net.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class PublicWebContentExtractor implements ContentExtractor {

  public Extraction extract(String url) {
    try {
      URI uri = URI.create(url);
      validate(uri);
      var response = Jsoup.connect(url)
        .timeout(5000)
        .maxBodySize(500000)
        .followRedirects(false)
        .userAgent("SignalFrame/0.1")
        .execute();
      if (
        response.statusCode() != 200 ||
        !response.contentType().toLowerCase().contains("text/html")
      ) return unavailable();
      var document = response.parse();
      document.select("script,style,nav,footer,header").remove();
      var article = document.selectFirst("article,main");
      String text = (article == null ? document.body() : article).text();
      if (text.length() < 80) return unavailable();
      return new Extraction(
        text.substring(0, Math.min(text.length(), 100000)),
        "EXTRACTED",
        "Best-effort extraction; check source text before analysis."
      );
    } catch (Exception ignored) {
      return unavailable();
    }
  }

  public static void validate(URI uri) throws Exception {
    if (
      !(
        "https".equalsIgnoreCase(uri.getScheme()) ||
        "http".equalsIgnoreCase(uri.getScheme())
      ) ||
      uri.getHost() == null ||
      uri.getUserInfo() != null ||
      (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)
    ) throw new IllegalArgumentException("Invalid URL");
    for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
      byte[] b = a.getAddress();
      int first = b[0] & 255,
        second = b.length > 1 ? b[1] & 255 : 0;
      if (
        a.isAnyLocalAddress() ||
        a.isLoopbackAddress() ||
        a.isLinkLocalAddress() ||
        a.isSiteLocalAddress() ||
        a.isMulticastAddress() ||
        (b.length == 4 &&
          (first == 0 ||
            first >= 224 ||
            (first == 100 && second >= 64 && second <= 127) ||
            (first == 198 && (second == 18 || second == 19)))) ||
        (b.length == 16 && (first & 0xfe) == 0xfc)
      ) throw new IllegalArgumentException("Private destination");
    }
  }

  private Extraction unavailable() {
    return new Extraction(
      "",
      "NEEDS_TEXT",
      "网页无法提取，请粘贴正文继续分析。"
    );
  }
}
