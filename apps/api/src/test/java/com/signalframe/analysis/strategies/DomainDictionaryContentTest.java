package com.signalframe.analysis.strategies;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.strategies.DomainStrategyResolver;
import com.signalframe.analysis.application.strategies.MechanismTemplate;
import com.signalframe.analysis.application.strategies.VariableTemplate;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** DS-12: every required domain has a dictionary that covers its variables and its traps. */
class DomainDictionaryContentTest {

  private static final List<DomainAnalysisStrategy> STRATEGIES =
    StrategyFixtures.strategies();

  static Stream<Arguments> requiredVariables() {
    return Stream.of(
      Arguments.of(
        DomainType.AI,
        List.of(
          "capability",
          "cost",
          "latency",
          "compute",
          "availability",
          "ecosystem",
          "substitutability",
          "standards",
          "intellectual-property"
        )
      ),
      Arguments.of(
        DomainType.TECH,
        List.of(
          "cost",
          "performance",
          "availability",
          "install base",
          "standards",
          "ecosystem",
          "substitutability",
          "fragmentation",
          "licensing"
        )
      ),
      Arguments.of(
        DomainType.BUSINESS,
        List.of(
          "revenue",
          "margin",
          "pricing",
          "concentration",
          "channel",
          "share",
          "cash",
          "capital spending",
          "moat"
        )
      ),
      Arguments.of(
        DomainType.FINANCE,
        List.of(
          "expectation",
          "interest rate",
          "liquidity",
          "earnings",
          "valuation",
          "risk premium",
          "positioning",
          "flow"
        )
      ),
      Arguments.of(
        DomainType.MACRO,
        List.of(
          "activity",
          "inflation",
          "policy rate",
          "employment",
          "credit",
          "exchange rate",
          "fiscal",
          "inventor"
        )
      ),
      Arguments.of(
        DomainType.POLICY,
        List.of(
          "instrument",
          "regulator",
          "enforcement",
          "implementation",
          "timeline",
          "threshold",
          "compliance cost",
          "subsid"
        )
      ),
      Arguments.of(
        DomainType.GEOPOLITICS,
        List.of(
          "interest",
          "capability",
          "capacity",
          "dependency",
          "sanctions",
          "alliance",
          "leverage",
          "action"
        )
      ),
      Arguments.of(
        DomainType.REAL_ESTATE,
        List.of(
          "credit",
          "transaction",
          "inventory",
          "rent",
          "price",
          "land supply",
          "construction",
          "financing"
        )
      ),
      Arguments.of(
        DomainType.ENERGY,
        List.of(
          "supply and demand balance",
          "inventor",
          "capacity",
          "marginal cost",
          "transport",
          "substitution",
          "regulation",
          "geopolitical"
        )
      ),
      Arguments.of(
        DomainType.CONSUMER,
        List.of(
          "income",
          "savings",
          "sentiment",
          "elasticity",
          "mix",
          "channel",
          "penetration",
          "frequency",
          "retention",
          "substitution"
        )
      ),
      Arguments.of(
        DomainType.HEALTHCARE,
        List.of(
          "efficacy",
          "safety",
          "clinical stage",
          "patient population",
          "approval",
          "reimbursement",
          "net price",
          "supply",
          "adoption"
        )
      ),
      Arguments.of(
        DomainType.EMPLOYMENT,
        List.of(
          "demand",
          "supply",
          "participation",
          "unemployment",
          "wage",
          "barrier",
          "automation",
          "hours",
          "geography"
        )
      ),
      Arguments.of(
        DomainType.OTHER,
        List.of(
          "change",
          "verification",
          "expectation",
          "mechanism",
          "objection"
        )
      )
    );
  }

  static Stream<Arguments> requiredHazards() {
    return Stream.of(
      Arguments.of(
        DomainType.AI,
        "a benchmark score is not deployed capability"
      ),
      Arguments.of(
        DomainType.TECH,
        "a specification sheet is not measured performance"
      ),
      Arguments.of(
        DomainType.BUSINESS,
        "an adjusted or non-gaap measure is not comparable"
      ),
      Arguments.of(
        DomainType.FINANCE,
        "a price move is not confirmation of a causal narrative"
      ),
      Arguments.of(DomainType.MACRO, "a first print is not the final figure"),
      Arguments.of(DomainType.POLICY, "announced is not enacted"),
      Arguments.of(DomainType.POLICY, "implemented is not enforced"),
      Arguments.of(
        DomainType.GEOPOLITICS,
        "a statement is not a capability and a statement is not an action"
      ),
      Arguments.of(
        DomainType.REAL_ESTATE,
        "an asking price is not a transaction price"
      ),
      Arguments.of(DomainType.ENERGY, "a policy target is not built capacity"),
      Arguments.of(DomainType.CONSUMER, "a stated intention is not a purchase"),
      Arguments.of(
        DomainType.HEALTHCARE,
        "a topline announcement is not a peer-reviewed result"
      ),
      Arguments.of(DomainType.EMPLOYMENT, "a posting is not a hire"),
      Arguments.of(
        DomainType.OTHER,
        "a single source is reported, not corroborated"
      )
    );
  }

  @ParameterizedTest(name = "{0} covers its required variables")
  @MethodSource("requiredVariables")
  void domainDictionaryCoversItsRequiredVariables(
    DomainType domain,
    List<String> fragments
  ) {
    String names = variableNames(domain);
    for (String fragment : fragments)
      assertTrue(
        names.contains(fragment.toLowerCase(Locale.ROOT)),
        domain +
          " dictionary has no variable covering '" +
          fragment +
          "': " +
          names
      );
  }

  @ParameterizedTest(name = "{0} warns about its reasoning trap")
  @MethodSource("requiredHazards")
  void domainDictionaryDeclaresItsReasoningTraps(
    DomainType domain,
    String fragment
  ) {
    String hazards = hazards(domain);
    assertTrue(
      hazards.contains(fragment.toLowerCase(Locale.ROOT)),
      domain +
        " dictionary is missing the hazard '" +
        fragment +
        "': " +
        hazards
    );
  }

  @Test
  void everyDomainOffersVariablesMechanismsMetricsAndHazards() {
    for (DomainType domain : DomainType.values()) {
      var spec = DomainStrategyResolver.resolve(domain, STRATEGIES).spec();
      String label = domain + " (" + spec.domain() + ")";
      assertTrue(spec.variables().size() >= 4, label + " variables");
      assertTrue(spec.mechanisms().size() >= 3, label + " mechanisms");
      assertTrue(spec.metrics().size() >= 3, label + " metrics");
      assertTrue(spec.epistemicHazards().size() >= 3, label + " hazards");
      assertFalse(spec.guidanceText().isBlank(), label + " guidance text");
      for (MechanismTemplate mechanism : spec.mechanisms()) {
        assertFalse(mechanism.requiredEvidence().isBlank(), label);
        assertFalse(mechanism.explanationPattern().isBlank(), label);
      }
    }
  }

  @Test
  void theFallbackDocumentsItsGap() {
    DomainAnalysisStrategy fallback = DomainStrategyResolver.resolve(
      DomainType.OTHER,
      STRATEGIES
    );
    assertTrue(
      fallback.spec().guidanceText().contains("no specialised dictionary"),
      "the fallback must state that the domain has no dictionary"
    );
  }

  private static String variableNames(DomainType domain) {
    return DomainStrategyResolver.resolve(domain, STRATEGIES)
      .spec()
      .variables()
      .stream()
      .map(VariableTemplate::name)
      .collect(Collectors.joining(" | "))
      .toLowerCase(Locale.ROOT);
  }

  private static String hazards(DomainType domain) {
    return String.join(
      " | ",
      DomainStrategyResolver.resolve(domain, STRATEGIES)
        .spec()
        .epistemicHazards()
    ).toLowerCase(Locale.ROOT);
  }
}
