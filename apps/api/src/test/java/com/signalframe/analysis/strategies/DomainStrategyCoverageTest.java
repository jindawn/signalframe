package com.signalframe.analysis.strategies;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.strategies.AiDomainStrategy;
import com.signalframe.analysis.application.strategies.BusinessDomainStrategy;
import com.signalframe.analysis.application.strategies.ConsumerDomainStrategy;
import com.signalframe.analysis.application.strategies.DefaultDomainStrategy;
import com.signalframe.analysis.application.strategies.DomainStrategyResolver;
import com.signalframe.analysis.application.strategies.EmploymentDomainStrategy;
import com.signalframe.analysis.application.strategies.EnergyDomainStrategy;
import com.signalframe.analysis.application.strategies.FinanceDomainStrategy;
import com.signalframe.analysis.application.strategies.GeopoliticsDomainStrategy;
import com.signalframe.analysis.application.strategies.HealthcareDomainStrategy;
import com.signalframe.analysis.application.strategies.MacroDomainStrategy;
import com.signalframe.analysis.application.strategies.PolicyDomainStrategy;
import com.signalframe.analysis.application.strategies.RealEstateDomainStrategy;
import com.signalframe.analysis.application.strategies.TechnologyAnalysisStrategy;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DS-01, DS-02, DS-03, DS-05 and DS-12: coverage, fallback and DomainType mapping. */
class DomainStrategyCoverageTest {

  private static final List<DomainAnalysisStrategy> STRATEGIES =
    StrategyFixtures.strategies();

  private static final Map<DomainType, Class<?>> EXPECTED = Map.ofEntries(
    Map.entry(DomainType.AI, AiDomainStrategy.class),
    Map.entry(DomainType.TECH, TechnologyAnalysisStrategy.class),
    Map.entry(DomainType.BUSINESS, BusinessDomainStrategy.class),
    Map.entry(DomainType.FINANCE, FinanceDomainStrategy.class),
    Map.entry(DomainType.MACRO, MacroDomainStrategy.class),
    Map.entry(DomainType.POLICY, PolicyDomainStrategy.class),
    Map.entry(DomainType.GEOPOLITICS, GeopoliticsDomainStrategy.class),
    Map.entry(DomainType.REAL_ESTATE, RealEstateDomainStrategy.class),
    Map.entry(DomainType.ENERGY, EnergyDomainStrategy.class),
    Map.entry(DomainType.CONSUMER, ConsumerDomainStrategy.class),
    Map.entry(DomainType.HEALTHCARE, HealthcareDomainStrategy.class),
    Map.entry(DomainType.EMPLOYMENT, EmploymentDomainStrategy.class),
    Map.entry(DomainType.OTHER, DefaultDomainStrategy.class)
  );

  @Test
  void everyDomainTypeResolvesToAStrategyWithGuidance() {
    for (DomainType domain : DomainType.values()) {
      DomainAnalysisStrategy strategy = DomainStrategyResolver.resolve(
        domain,
        STRATEGIES
      );
      assertNotNull(strategy, "no strategy for " + domain);
      assertFalse(
        strategy.guidance().isBlank(),
        "empty guidance for " + domain
      );
      assertEquals(
        strategy.specificity(),
        strategy.spec().specificity(),
        "specificity() must agree with spec().specificity() for " + domain
      );
      assertEquals(
        domain,
        strategy.spec().domain(),
        "strategy resolved for " + domain + " must publish that domain"
      );
    }
  }

  @Test
  void domainTypeMappingIsComplete() {
    assertEquals(
      EnumSet.allOf(DomainType.class),
      EXPECTED.keySet(),
      "every DomainType value must be mapped, including OTHER"
    );
    for (Map.Entry<DomainType, Class<?>> entry : EXPECTED.entrySet()) {
      assertEquals(
        entry.getValue(),
        DomainStrategyResolver.resolve(entry.getKey(), STRATEGIES).getClass(),
        "unexpected strategy for " + entry.getKey()
      );
    }
  }

  @Test
  void otherUsesTheFallbackStrategy() {
    DomainAnalysisStrategy fallback = DomainStrategyResolver.resolve(
      DomainType.OTHER,
      STRATEGIES
    );
    assertInstanceOf(DefaultDomainStrategy.class, fallback);
    assertEquals(0, fallback.specificity());
    for (DomainType domain : DomainType.values())
      assertTrue(
        fallback.supports(domain),
        "the fallback must support " + domain
      );
  }

  @Test
  void theFallbackIsTheOnlyAllDomainStrategy() {
    List<DomainAnalysisStrategy> allDomain = STRATEGIES.stream()
      .filter(s ->
        EnumSet.allOf(DomainType.class).stream().allMatch(s::supports)
      )
      .toList();
    assertEquals(
      1,
      allDomain.size(),
      "exactly one all-domain strategy (DS-03)"
    );
    assertInstanceOf(DefaultDomainStrategy.class, allDomain.getFirst());
    assertEquals(0, allDomain.getFirst().specificity());
  }

  @Test
  void everySpecialisedStrategyHasSpecificityOfAtLeastOne() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      if (strategy.specificity() == 0) continue;
      assertTrue(
        strategy.specificity() >= 1,
        "specialised strategy must declare specificity >= 1: " +
          strategy.getClass().getName()
      );
    }
  }

  @Test
  void noDomainHasADuplicateTopSpecificity() {
    assertDoesNotThrow(() ->
      DomainStrategyResolver.validateConfiguration(STRATEGIES)
    );
  }

  @Test
  void eachSpecialisedStrategyOwnsTheDomainItPublishes() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      if (strategy.specificity() == 0) continue;
      assertSame(
        strategy,
        DomainStrategyResolver.resolve(strategy.spec().domain(), STRATEGIES),
        "the strategy must be the winner for its own domain"
      );
    }
  }
}
