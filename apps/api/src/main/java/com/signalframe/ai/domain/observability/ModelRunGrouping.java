package com.signalframe.ai.domain.observability;

/** Dimension an audit report is grouped by. */
public enum ModelRunGrouping {
  /** Actual recorded provider and model identifier. */
  PROVIDER_MODEL,
  /** Business purpose chosen by the application layer. */
  PURPOSE,
  /** Prompt version used for the call. */
  PROMPT_VERSION,
  /** Job that caused the call. */
  JOB,
}
