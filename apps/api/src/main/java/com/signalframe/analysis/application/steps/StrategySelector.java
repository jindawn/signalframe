package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import com.signalframe.shared.ApplicationException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic domain strategy selection (DOMAIN_STRATEGY_CONTRACT §5).
 *
 * <p>Selection never depends on class-name prefixes, autowiring order or a
 * coin flip: specialised strategies win over the all-domain fallback, and the
 * remaining order is stable. A domain with no matching strategy is a job failure
 * (DS-05), never an empty guidance string.
 *
 * <p><b>Known gap (TASK-05 pending).</b> The contract's v0.1.1 addition
 * ({@code specificity()} + {@code spec()}) is owned by TASK-05 and has not
 * landed on this branch. Until it does, "specialised" is expressed as "not the
 * fallback" and the DS-04 duplicate-top-specificity startup check cannot exist
 * yet; the selection itself is already deterministic. This file is the single
 * place that must change when {@code specificity()} lands.
 */
@Component
public class StrategySelector {

  private final List<DomainAnalysisStrategy> strategies;

  public StrategySelector(List<DomainAnalysisStrategy> strategies) {
    this.strategies = List.copyOf(strategies);
  }

  public DomainAnalysisStrategy select(DomainType domain) {
    var matches = strategies
      .stream()
      .filter(s -> s.supports(domain))
      .sorted((a, b) -> {
        int byFallback = Boolean.compare(isFallback(a), isFallback(b));
        if (byFallback != 0) return byFallback;
        return a.getClass().getSimpleName().compareTo(b.getClass().getSimpleName());
      })
      .toList();
    if (matches.isEmpty()) throw new ApplicationException(
      500,
      "NO_DOMAIN_STRATEGY",
      "No domain strategy supports this analysis domain."
    );
    return matches.getFirst();
  }

  /** The strategy id recorded in the snapshot's provenance (PR-16). */
  public String idOf(DomainAnalysisStrategy strategy) {
    return strategy.getClass().getSimpleName();
  }

  private static boolean isFallback(DomainAnalysisStrategy strategy) {
    return strategy
      .getClass()
      .getSimpleName()
      .toLowerCase(java.util.Locale.ROOT)
      .startsWith("default");
  }
}
