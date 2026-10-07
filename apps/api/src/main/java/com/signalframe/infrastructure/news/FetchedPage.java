package com.signalframe.infrastructure.news;

import java.net.URI;

/**
 * One bounded HTTP GET result. {@code body} is raw bytes capped by
 * {@code news.fetch.max-response-bytes}; HTML decoding and text extraction
 * happen later.
 */
public record FetchedPage(
  URI finalUri,
  int status,
  String contentType,
  String charset,
  byte[] body,
  int redirects
) {}
