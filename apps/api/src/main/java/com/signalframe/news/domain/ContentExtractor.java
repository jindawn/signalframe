package com.signalframe.news.domain;

public interface ContentExtractor {
  record Extraction(String text, String status, String message) {}

  Extraction extract(String url);
}
