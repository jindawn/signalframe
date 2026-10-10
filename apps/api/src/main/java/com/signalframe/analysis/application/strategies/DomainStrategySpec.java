package com.signalframe.analysis.application.strategies;

import com.signalframe.contract.DomainType;
import java.util.List;

/**
 * The immutable recommendation set of one domain strategy.
 *
 * <p>It contains recommendations only: variables to look for, candidate mechanisms, verification
 * metrics, domain hazards and a bounded guidance paragraph. It contains no claim, no direction, no
 * magnitude, no confidence and no probability.
 */
public record DomainStrategySpec(
  DomainType domain,
  int specificity,
  List<VariableTemplate> variables,
  List<MechanismTemplate> mechanisms,
  List<VerificationMetricTemplate> metrics,
  List<String> epistemicHazards,
  String guidanceText
) {
  public DomainStrategySpec {
    if (domain == null) throw new IllegalArgumentException(
      "DomainStrategySpec.domain must not be null"
    );
    if (specificity < 0) throw new IllegalArgumentException(
      "DomainStrategySpec.specificity must not be negative"
    );
    variables = SpecRequirements.items(
      variables,
      "DomainStrategySpec.variables"
    );
    mechanisms = SpecRequirements.items(
      mechanisms,
      "DomainStrategySpec.mechanisms"
    );
    metrics = SpecRequirements.items(metrics, "DomainStrategySpec.metrics");
    epistemicHazards = SpecRequirements.texts(
      epistemicHazards,
      "DomainStrategySpec.epistemicHazards"
    );
    guidanceText = SpecRequirements.text(
      guidanceText,
      "DomainStrategySpec.guidanceText"
    );
  }
}
