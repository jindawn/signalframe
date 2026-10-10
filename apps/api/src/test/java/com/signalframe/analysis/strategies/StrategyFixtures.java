package com.signalframe.analysis.strategies;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Test support: discovers the strategies the same way Spring would, without starting a context.
 *
 * <p>Discovery means a new strategy automatically enters every contract test, so the tests cannot
 * drift from the production set.
 */
final class StrategyFixtures {

  private static final String STRATEGY_PACKAGE =
    "com.signalframe.analysis.application.strategies";

  private StrategyFixtures() {}

  static List<DomainAnalysisStrategy> strategies() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(
      new AssignableTypeFilter(DomainAnalysisStrategy.class)
    );
    List<DomainAnalysisStrategy> found = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(
      STRATEGY_PACKAGE
    )) {
      found.add(instantiate(definition.getBeanClassName()));
    }
    if (found.isEmpty()) throw new IllegalStateException(
      "No domain strategy found in " + STRATEGY_PACKAGE
    );
    return List.copyOf(found);
  }

  private static DomainAnalysisStrategy instantiate(String className) {
    try {
      return (DomainAnalysisStrategy) Class.forName(className)
        .getDeclaredConstructor()
        .newInstance();
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("Cannot instantiate " + className, error);
    }
  }
}
