package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TECH dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.2).
 *
 * <p>This strategy previously also claimed {@link DomainType#AI}; the AI dictionary now lives in
 * {@link AiDomainStrategy}, and one domain must have exactly one top-specificity strategy (DS-04),
 * so the claim was narrowed to TECH rather than left overlapping.
 */
@Component
public final class TechnologyAnalysisStrategy
  implements DomainAnalysisStrategy
{

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.TECH,
    SPECIFICITY,
    List.of(
      variable(
        "measured unit cost curve",
        "measured cost of a defined unit as volume, yield or maturity changes",
        "the slope of the cost curve decides which uses become viable and which are priced out",
        "compare dated measurements for the same unit and scope; UNKNOWN without a stated unit"
      ),
      variable(
        "measured performance against stated service levels",
        "throughput, latency and reliability against a published objective",
        "unmet service levels block adoption regardless of what the specification claims",
        "decide from a dated operational measurement against a stated objective; UNKNOWN when only specification claims exist"
      ),
      variable(
        "availability and supply lead time",
        "how quickly the component can be obtained at a stated quantity",
        "lead time decides whether demand converts into revenue or into a backlog",
        "decide from dated order, allocation or shipping records; UNKNOWN when only marketing availability is stated"
      ),
      variable(
        "install base and attach rate",
        "units deployed and the share using the paid or advanced capability",
        "the installed base sets the addressable upgrade and service revenue",
        "decide from dated shipment, install or activation disclosures; UNKNOWN when the counting rule is unstated"
      ),
      variable(
        "standards adoption and conformance",
        "which interfaces independent vendors implement and certify",
        "standards determine interoperability and how quickly the component commoditizes",
        "decide from dated standards documents and conformance lists; UNKNOWN when adoption rests on one vendor roadmap"
      ),
      variable(
        "ecosystem breadth and developer adoption",
        "independent integrations, libraries and published project activity around the platform",
        "ecosystem breadth raises switching cost and lowers substitution risk",
        "decide from dated independent releases and project activity; UNKNOWN when activity is only vendor-reported"
      ),
      variable(
        "substitutability and switching cost",
        "how easily a competing component replaces this one in a running system",
        "substitutes cap pricing power and shorten the life of a price increase",
        "decide from a dated drop-in alternative plus a stated migration effort; UNKNOWN when no comparable alternative is identified"
      ),
      variable(
        "version and compatibility fragmentation",
        "how many supported versions exist and how far they diverge",
        "fragmentation raises integration cost and slows adoption",
        "decide from dated release and support matrices; UNKNOWN when the support window is unstated"
      ),
      variable(
        "intellectual-property and licensing position",
        "patents, licences and restrictions covering the component",
        "rights decide who may build, sell or modify the component",
        "decide from dated licence text or filings; UNKNOWN when terms are unpublished"
      )
    ),
    List.of(
      mechanism(
        "measured cost decline",
        "adoption",
        "a falling measured cost per unit widens the set of viable uses",
        "a dated cost measurement for a defined unit plus a dated adoption record"
      ),
      mechanism(
        "standards adoption",
        "interoperability and commoditization",
        "independent implementations of one interface make components interchangeable and compress margin",
        "a dated standards document plus at least two independent conforming implementations"
      ),
      mechanism(
        "reliability",
        "trust and volume",
        "meeting stated service levels is what allows a component into production workloads",
        "a dated operational measurement against a published objective plus a dated production deployment"
      ),
      mechanism(
        "supply constraint",
        "price and lead time",
        "limited supply raises price and delays delivery until capacity is added",
        "dated lead-time, allocation or capacity records"
      ),
      mechanism(
        "ecosystem breadth",
        "lock-in",
        "integrations and tooling raise the cost of leaving the platform",
        "dated independent integrations plus a stated migration cost"
      )
    ),
    List.of(
      metric(
        "official specifications, release notes and changelogs",
        "vendor and standards-body publication pages with dates",
        "per release",
        "the specification or release note states the capability with a date",
        "the shipped artifact differs from the specification, or the release is delayed without a note"
      ),
      metric(
        "independent measured performance",
        "independent test reports and teardown analyses",
        "per generation",
        "an independent measurement matches the stated objective",
        "an independent measurement falls short, or the setup cannot be reproduced"
      ),
      metric(
        "shipment, install and activation disclosures",
        "company filings, regulatory registries and dated press releases",
        "per reporting period",
        "disclosed units or activations rise against the prior comparable period",
        "the disclosure is withdrawn, restated, or defined differently from the prior period"
      ),
      metric(
        "standards conformance registers",
        "standards-body conformance lists and certification records",
        "per certification round",
        "independent vendors appear on the conformance list",
        "conformance is self-declared, or only one vendor appears"
      )
    ),
    List.of(
      "a specification sheet is not measured performance",
      "a roadmap press release is not shipped capability",
      "comparing results across changed test setups is not a comparison",
      "industry-leading without a defined metric is not a claim",
      "an announced partnership is not an integration"
    ),
    "Distinguish technical capability from adoption, deployment cost and independent reproducibility. Treat a specification as a claim until an independent measurement exists, and keep cost, reliability and standards conformance as separate variables."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.TECH;
  }

  @Override
  public int specificity() {
    return SPECIFICITY;
  }

  @Override
  public DomainStrategySpec spec() {
    return SPEC;
  }
}
