package com.signalframe.analysis.application;

import com.signalframe.shared.confidence.ConfidenceRubric;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deterministic confidence rubric into the container.
 *
 * <p>The rubric itself lives in the neutral {@code shared.confidence} package and
 * is deliberately not a component: it is a pure function of the snapshot, must be
 * usable from {@code research} as well as {@code analysis}, and must stay free of
 * Spring so it can be tested and reasoned about without a context. The bean is
 * declared here, in the module that consumes it.
 */
@Configuration
public class AnalysisConfiguration {

  @Bean
  ConfidenceRubric confidenceRubric() {
    return ConfidenceRubric.deterministic();
  }
}
