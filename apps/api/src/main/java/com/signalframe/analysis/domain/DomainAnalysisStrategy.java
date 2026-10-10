package com.signalframe.analysis.domain;

import com.signalframe.analysis.application.strategies.DomainStrategySpec;
import com.signalframe.analysis.application.strategies.DomainStrategySpecRenderer;
import com.signalframe.contract.DomainType;

/**
 * Domain strategy contract v0.1.1 (see docs/analysis/DOMAIN_STRATEGY_CONTRACT.md).
 *
 * <p>A strategy recommends what to look for in a domain: candidate variables, candidate
 * mechanisms, verification metrics and the reasoning traps the domain invites. It never changes
 * the meaning of FACT, INFERENCE, HYPOTHESIS, PREDICTION or UNKNOWN (EP-12), never assigns
 * confidence (CF-01), and never states a conclusion.
 *
 * <p>Implementations are pure: no I/O, no clock, no model call, no database, no randomness
 * (DS-06).
 */
public interface DomainAnalysisStrategy {
  /** DS-01: depends on the domain only. */
  boolean supports(DomainType domain);

  /** 0 = fallback, 1 = generic domain, 2+ = specialized. Higher wins. */
  default int specificity() {
    return 1;
  }

  /** Structured recommendations consumed by the analysis stages. */
  DomainStrategySpec spec();

  /** Legacy text rendering; must equal render(spec()) once spec() exists (DS-07). */
  default String guidance() {
    return DomainStrategySpecRenderer.render(spec());
  }
}
