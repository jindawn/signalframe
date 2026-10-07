package com.signalframe.ai.domain;

public record ModelUsage(
  Long inputTokens,
  Long outputTokens,
  Long totalTokens,
  Double estimatedCost
) {}
