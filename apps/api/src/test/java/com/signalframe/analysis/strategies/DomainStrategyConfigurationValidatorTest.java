package com.signalframe.analysis.strategies;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.strategies.DomainStrategyConfigurationValidator;
import com.signalframe.analysis.application.strategies.DomainStrategySpec;
import com.signalframe.analysis.application.strategies.MechanismTemplate;
import com.signalframe.analysis.application.strategies.VariableTemplate;
import com.signalframe.analysis.application.strategies.VerificationMetricTemplate;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** DS-04: an ambiguous strategy set must stop the application, not be resolved at job time. */
class DomainStrategyConfigurationValidatorTest {

  @Test
  void theProductionStrategySetPassesStartupValidation() {
    assertDoesNotThrow(() ->
      new DomainStrategyConfigurationValidator(StrategyFixtures.strategies())
    );
  }

  @Test
  void aMissingFallbackStopsStartup() {
    assertThrows(IllegalStateException.class, () ->
      new DomainStrategyConfigurationValidator(
        List.of(fake(Set.of(DomainType.ENERGY), 2))
      )
    );
  }

  @Test
  void aDuplicateTopSpecificityStopsStartup() {
    var fallback = fake(EnumSet.allOf(DomainType.class), 0);
    assertThrows(IllegalStateException.class, () ->
      new DomainStrategyConfigurationValidator(
        List.of(
          fallback,
          fake(Set.of(DomainType.ENERGY), 2),
          fake(Set.of(DomainType.ENERGY), 2)
        )
      )
    );
  }

  private static DomainAnalysisStrategy fake(
    Set<DomainType> domains,
    int specificity
  ) {
    return new DomainAnalysisStrategy() {
      @Override
      public boolean supports(DomainType domain) {
        return domains.contains(domain);
      }

      @Override
      public int specificity() {
        return specificity;
      }

      @Override
      public DomainStrategySpec spec() {
        return new DomainStrategySpec(
          domains.contains(DomainType.OTHER)
            ? DomainType.OTHER
            : domains.iterator().next(),
          specificity,
          List.of(new VariableTemplate("v", "tracks", "why", "direction rule")),
          List.of(
            new MechanismTemplate(
              "from",
              "to",
              "pattern",
              "required evidence",
              MechanismTemplate.SPECULATIVE
            )
          ),
          List.of(
            new VerificationMetricTemplate(
              "what",
              "where",
              "per release",
              "supporting",
              "contradicting"
            )
          ),
          List.of("hazard"),
          "guidance"
        );
      }
    };
  }
}
