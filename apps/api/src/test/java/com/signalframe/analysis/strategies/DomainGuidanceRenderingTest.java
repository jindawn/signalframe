package com.signalframe.analysis.strategies;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.strategies.DomainStrategySpecRenderer;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DS-07, DS-10, DS-11: guidance is a stable, bounded rendering of the spec. */
class DomainGuidanceRenderingTest {

  private static final List<DomainAnalysisStrategy> STRATEGIES =
    StrategyFixtures.strategies();

  @Test
  void guidanceIsTheRenderingOfTheSpec() {
    for (DomainAnalysisStrategy strategy : STRATEGIES)
      assertEquals(
        DomainStrategySpecRenderer.render(strategy.spec()),
        strategy.guidance(),
        strategy.getClass().getName()
      );
  }

  @Test
  void guidanceIsStableAcrossCallsAndInstances() {
    var first = byDomain(STRATEGIES);
    var second = byDomain(StrategyFixtures.strategies());
    assertEquals(first.keySet(), second.keySet());
    for (var domain : first.keySet()) {
      var strategy = first.get(domain);
      assertEquals(
        strategy.guidance(),
        strategy.guidance(),
        "guidance must be pure and repeatable"
      );
      assertEquals(
        strategy.guidance(),
        second.get(domain).guidance(),
        "domain guidance must be stable across instances for " + domain
      );
    }
  }

  private static java.util.Map<
    com.signalframe.contract.DomainType,
    DomainAnalysisStrategy
  > byDomain(List<DomainAnalysisStrategy> strategies) {
    var map = new java.util.HashMap<
      com.signalframe.contract.DomainType,
      DomainAnalysisStrategy
    >();
    for (DomainAnalysisStrategy strategy : strategies) {
      assertNull(
        map.put(strategy.spec().domain(), strategy),
        "two strategies publish the same spec domain: " +
          strategy.spec().domain()
      );
    }
    return map;
  }

  @Test
  void guidanceStaysWithinTheContractBound() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      assertTrue(
        strategy.guidance().length() <=
          DomainStrategySpecRenderer.MAX_GUIDANCE_LENGTH,
        strategy.getClass().getSimpleName() +
          " guidance is " +
          strategy.guidance().length() +
          " characters"
      );
      assertTrue(strategy.guidance().length() > 0);
    }
  }

  @Test
  void guidanceAlwaysCarriesDomainGuidanceAndEveryHazard() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      String guidance = strategy.guidance();
      assertTrue(
        guidance.startsWith("Domain " + strategy.spec().domain()),
        "guidance must name the domain: " + guidance
      );
      assertTrue(
        guidance.contains(strategy.spec().guidanceText()),
        "domain guidance paragraph is missing"
      );
      assertTrue(guidance.contains("Domain hazards"));
      for (String hazard : strategy.spec().epistemicHazards())
        assertTrue(
          guidance.contains(hazard),
          "hazard omitted from guidance: " + hazard
        );
    }
  }

  @Test
  void everyRenderedSectionOffersAtLeastOneEntry() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      String guidance = strategy.guidance();
      String label = strategy.getClass().getSimpleName();
      assertTrue(guidance.contains("Variables to check"), label);
      assertTrue(guidance.contains("Candidate mechanisms"), label);
      assertTrue(guidance.contains("Verification checks"), label);
      assertTrue(guidance.contains("SPECULATIVE"), label);
      assertTrue(
        guidance.contains(strategy.spec().variables().getFirst().name()),
        label + " renders no variable"
      );
      assertTrue(
        guidance.contains(
          strategy.spec().mechanisms().getFirst().fromConcept()
        ),
        label + " renders no mechanism"
      );
      assertTrue(
        guidance.contains(strategy.spec().metrics().getFirst().whatToCheck()),
        label + " renders no verification check"
      );
    }
  }

  @Test
  void truncationOnlyAppearsWhenTheBudgetIsTooSmall() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      String guidance = strategy.guidance();
      if (!guidance.contains("the strategy spec holds the full list")) continue;
      assertTrue(
        guidance.contains("more variable") ||
          guidance.contains("more mechanism") ||
          guidance.contains("more check"),
        "an omission note must name the section it refers to"
      );
    }
  }
}
