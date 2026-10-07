package com.signalframe.infrastructure.news;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Central fetch limits for URL ingestion. Defaults are code-level because
 * {@code application.yml} belongs to the integrator; every value can be
 * overridden through {@code news.fetch.*} YAML/environment properties without
 * touching code.
 *
 * <p>Kept deliberately small: no crawler knobs, only the bounds required to
 * make a single best-effort page fetch predictable.
 */
@ConfigurationProperties("news.fetch")
public record NewsFetchProperties(
  @DefaultValue("3s") Duration connectTimeout,
  @DefaultValue("5s") Duration readTimeout,
  @DefaultValue("10s") Duration totalTimeout,
  @DefaultValue("3") int maxRedirects,
  @DefaultValue("2097152") int maxResponseBytes,
  @DefaultValue("100000") int maxTextChars,
  @DefaultValue("80") int minTextChars,
  @DefaultValue("SignalFrame/0.1 (+local single-user research workspace)") String userAgent,
  @DefaultValue(
    { "text/html", "application/xhtml+xml", "text/plain" }
  ) List<String> allowedContentTypes
) {}
