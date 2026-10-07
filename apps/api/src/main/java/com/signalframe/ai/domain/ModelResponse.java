package com.signalframe.ai.domain;

public record ModelResponse(
  String content,
  ModelUsage usage,
  String provider,
  String model
) {}
