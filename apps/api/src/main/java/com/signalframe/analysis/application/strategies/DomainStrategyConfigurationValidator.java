package com.signalframe.analysis.application.strategies;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Startup configuration validation for the strategy set (DS-02, DS-03, DS-04).
 *
 * <p>A duplicate top specificity, a missing fallback or a fallback that does not cover every domain
 * is a configuration error and must stop the application rather than be resolved at job time by
 * bean order. Selection itself stays with {@code AnalysisPipeline} (TASK-03); this component only
 * refuses to start on an ambiguous or incomplete set.
 */
@Component
public final class DomainStrategyConfigurationValidator {

  public DomainStrategyConfigurationValidator(
    List<DomainAnalysisStrategy> strategies
  ) {
    DomainStrategyResolver.validateConfiguration(strategies);
  }
}
