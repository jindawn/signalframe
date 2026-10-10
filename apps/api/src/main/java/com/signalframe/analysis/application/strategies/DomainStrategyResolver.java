package com.signalframe.analysis.application.strategies;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deterministic strategy selection (DOMAIN_STRATEGY_CONTRACT.md §5).
 *
 * <p>Pure and stateless: {@code AnalysisPipeline} owns the wiring and calls this, TASK-05 must not
 * edit the pipeline. Selection order is fixed:
 *
 * <ol>
 *   <li>collect every strategy where {@code supports(domain)} is true;
 *   <li>take the maximum {@code specificity()};
 *   <li>more than one strategy at that maximum is a startup configuration error (DS-04) — class
 *       names and bean order are never used as a tie-break;
 *   <li>no strategy at all is a job failure (DS-05), never empty guidance.
 * </ol>
 */
public final class DomainStrategyResolver {

  private DomainStrategyResolver() {}

  /** Every strategy that claims the domain, in input order. */
  public static List<DomainAnalysisStrategy> allFor(
    DomainType domain,
    Collection<DomainAnalysisStrategy> strategies
  ) {
    if (domain == null) throw new IllegalArgumentException(
      "domain must not be null"
    );
    if (strategies == null) throw new IllegalArgumentException(
      "strategies must not be null"
    );
    return strategies
      .stream()
      .filter(s -> s.supports(domain))
      .toList();
  }

  /** The single highest-specificity strategy for the domain, or a configuration error. */
  public static DomainAnalysisStrategy resolve(
    DomainType domain,
    Collection<DomainAnalysisStrategy> strategies
  ) {
    List<DomainAnalysisStrategy> candidates = allFor(domain, strategies);
    if (candidates.isEmpty()) throw new IllegalStateException(
      "No domain strategy supports " +
        domain +
        " (DS-05); guidance must never be empty"
    );
    int max = candidates
      .stream()
      .mapToInt(DomainAnalysisStrategy::specificity)
      .max()
      .orElseThrow();
    List<DomainAnalysisStrategy> top = candidates
      .stream()
      .filter(s -> s.specificity() == max)
      .toList();
    if (top.size() > 1) throw new IllegalStateException(
      "Duplicate domain strategy specificity " +
        max +
        " for " +
        domain +
        " (DS-04): " +
        names(top) +
        " — selection must not depend on class-name or bean order"
    );
    return top.getFirst();
  }

  /**
   * Startup validation (DS-02, DS-03, DS-04): exactly one fallback with specificity 0 that supports
   * every domain, every other strategy at specificity 1 or higher, and no duplicate top
   * specificity in any domain.
   */
  public static void validateConfiguration(
    Collection<DomainAnalysisStrategy> strategies
  ) {
    List<DomainAnalysisStrategy> fallbacks = strategies
      .stream()
      .filter(s -> s.specificity() == 0)
      .toList();
    if (fallbacks.size() != 1) throw new IllegalStateException(
      "Exactly one fallback strategy with specificity 0 is required (DS-02/DS-03); found: " +
        (fallbacks.isEmpty() ? "none" : names(fallbacks))
    );
    DomainAnalysisStrategy fallback = fallbacks.getFirst();
    for (DomainType domain : DomainType.values()) {
      if (!fallback.supports(domain)) throw new IllegalStateException(
        "Fallback strategy " +
          fallback.getClass().getName() +
          " must support every domain (DS-03), missing " +
          domain
      );
      resolve(domain, strategies);
    }
  }

  private static String names(Collection<DomainAnalysisStrategy> strategies) {
    return strategies
      .stream()
      .map(s -> s.getClass().getName())
      .sorted()
      .collect(Collectors.joining(", "));
  }
}
