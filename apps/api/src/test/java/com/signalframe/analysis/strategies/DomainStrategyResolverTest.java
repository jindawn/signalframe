package com.signalframe.analysis.strategies;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.strategies.DomainStrategyResolver;
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

/** DS-01, DS-02, DS-03, DS-04, DS-05: deterministic selection and configuration validation. */
class DomainStrategyResolverTest {

  private static final Set<DomainType> ALL = EnumSet.allOf(DomainType.class);

  @Test
  void highestSpecificityWins() {
    var generic = strategy(1, Set.of(DomainType.POLICY), DomainType.POLICY);
    var specialised = strategy(2, Set.of(DomainType.POLICY), DomainType.POLICY);
    assertSame(
      specialised,
      DomainStrategyResolver.resolve(
        DomainType.POLICY,
        List.of(generic, specialised)
      )
    );
    assertSame(
      specialised,
      DomainStrategyResolver.resolve(
        DomainType.POLICY,
        List.of(specialised, generic)
      ),
      "selection must not depend on input order"
    );
  }

  @Test
  void theFallbackLosesToAnySpecialisedStrategy() {
    var fallback = strategy(0, ALL, DomainType.OTHER);
    var specialised = strategy(1, Set.of(DomainType.ENERGY), DomainType.ENERGY);
    assertSame(
      specialised,
      DomainStrategyResolver.resolve(
        DomainType.ENERGY,
        List.of(fallback, specialised)
      )
    );
  }

  @Test
  void anUnmatchedDomainIsAnExplicitFailure() {
    var policy = strategy(2, Set.of(DomainType.POLICY), DomainType.POLICY);
    var error = assertThrows(IllegalStateException.class, () ->
      DomainStrategyResolver.resolve(DomainType.AI, List.of(policy))
    );
    assertTrue(error.getMessage().contains("DS-05"), error.getMessage());
  }

  @Test
  void anEmptyStrategyListIsAnExplicitFailure() {
    assertThrows(IllegalStateException.class, () ->
      DomainStrategyResolver.resolve(DomainType.OTHER, List.of())
    );
  }

  @Test
  void duplicateTopSpecificityIsAConfigurationError() {
    var first = strategy(2, Set.of(DomainType.FINANCE), DomainType.FINANCE);
    var second = strategy(2, Set.of(DomainType.FINANCE), DomainType.FINANCE);
    for (List<DomainAnalysisStrategy> order : List.of(
      List.of(first, second),
      List.of(second, first)
    )) {
      var error = assertThrows(IllegalStateException.class, () ->
        DomainStrategyResolver.resolve(DomainType.FINANCE, order)
      );
      assertTrue(
        error.getMessage().contains("DS-04"),
        "a tie must be a configuration error, not a coin flip: " +
          error.getMessage()
      );
    }
  }

  @Test
  void lowerSpecificityDuplicatesAreTolerated() {
    var fallback = strategy(0, ALL, DomainType.OTHER);
    var low = strategy(1, Set.of(DomainType.MACRO), DomainType.MACRO);
    var high = strategy(2, Set.of(DomainType.MACRO), DomainType.MACRO);
    assertSame(
      high,
      DomainStrategyResolver.resolve(
        DomainType.MACRO,
        List.of(fallback, low, high)
      )
    );
  }

  @Test
  void allForReturnsEveryClaimantInInputOrder() {
    var fallback = strategy(0, ALL, DomainType.OTHER);
    var specialised = strategy(
      2,
      Set.of(DomainType.HEALTHCARE),
      DomainType.HEALTHCARE
    );
    assertEquals(
      List.of(fallback, specialised),
      DomainStrategyResolver.allFor(
        DomainType.HEALTHCARE,
        List.of(fallback, specialised)
      )
    );
  }

  @Test
  void aValidConfigurationPassesStartupValidation() {
    var fallback = strategy(0, ALL, DomainType.OTHER);
    var policy = strategy(2, Set.of(DomainType.POLICY), DomainType.POLICY);
    assertDoesNotThrow(() ->
      DomainStrategyResolver.validateConfiguration(List.of(fallback, policy))
    );
  }

  @Test
  void aMissingFallbackFailsStartupValidation() {
    var policy = strategy(2, Set.of(DomainType.POLICY), DomainType.POLICY);
    assertThrows(IllegalStateException.class, () ->
      DomainStrategyResolver.validateConfiguration(List.of(policy))
    );
  }

  @Test
  void twoFallbacksFailStartupValidation() {
    var first = strategy(0, ALL, DomainType.OTHER);
    var second = strategy(0, ALL, DomainType.OTHER);
    assertThrows(IllegalStateException.class, () ->
      DomainStrategyResolver.validateConfiguration(List.of(first, second))
    );
  }

  @Test
  void aFallbackThatMissesADomainFailsStartupValidation() {
    var partial = strategy(0, Set.of(DomainType.TECH), DomainType.OTHER);
    assertThrows(IllegalStateException.class, () ->
      DomainStrategyResolver.validateConfiguration(List.of(partial))
    );
  }

  @Test
  void aDuplicateTopSpecificityFailsStartupValidation() {
    var fallback = strategy(0, ALL, DomainType.OTHER);
    var first = strategy(2, Set.of(DomainType.CONSUMER), DomainType.CONSUMER);
    var second = strategy(2, Set.of(DomainType.CONSUMER), DomainType.CONSUMER);
    assertThrows(IllegalStateException.class, () ->
      DomainStrategyResolver.validateConfiguration(
        List.of(fallback, first, second)
      )
    );
  }

  @Test
  void aTemplateListCannotBeEmpty() {
    assertThrows(IllegalArgumentException.class, () ->
      new DomainStrategySpec(
        DomainType.OTHER,
        0,
        List.of(),
        List.of(mechanism()),
        List.of(metric()),
        List.of("hazard"),
        "guidance"
      )
    );
  }

  private static DomainAnalysisStrategy strategy(
    int specificity,
    Set<DomainType> domains,
    DomainType specDomain
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
          specDomain,
          specificity,
          List.of(
            new VariableTemplate(
              "variable",
              "what it tracks",
              "why it matters",
              "how direction would be decided"
            )
          ),
          List.of(mechanism()),
          List.of(metric()),
          List.of("a hazard"),
          "test guidance"
        );
      }
    };
  }

  private static MechanismTemplate mechanism() {
    return new MechanismTemplate(
      "from",
      "to",
      "explanation pattern",
      "required evidence",
      MechanismTemplate.SPECULATIVE
    );
  }

  private static VerificationMetricTemplate metric() {
    return new VerificationMetricTemplate(
      "what to check",
      "where to check",
      "per release",
      "supporting result",
      "contradicting result"
    );
  }
}
