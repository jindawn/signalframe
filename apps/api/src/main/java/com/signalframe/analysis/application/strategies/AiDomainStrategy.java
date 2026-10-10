package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * AI dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.1). Recommendations only: the strategy may not
 * assert that any capability, cost or adoption change occurred.
 */
@Component
public final class AiDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.AI,
    SPECIFICITY,
    List.of(
      variable(
        "deployed capability versus benchmark capability",
        "capability reachable in a production configuration, against capability reported on a benchmark",
        "the size of the gap decides whether a headline improvement can change a product, price or cost structure",
        "decide UP or DOWN only from comparable dated measurements of the same task; UNKNOWN when the deployed configuration or the comparison baseline is unstated"
      ),
      variable(
        "cost per unit of capability",
        "price charged or cost incurred for a defined unit of work",
        "unit cost, not headline price, decides substitution and adoption",
        "compare dated prices for the same defined unit and configuration; UNKNOWN when the unit is not defined"
      ),
      variable(
        "latency and reliability in production",
        "observed response time and failure rate under a stated load",
        "production behaviour decides whether a capability can serve a real workflow",
        "decide from a dated operational measurement, not a demonstration; UNKNOWN when no measurement or load definition exists"
      ),
      variable(
        "compute availability and lead time",
        "supply of accelerators and power, and the wait to obtain them",
        "compute is the binding input for training and serving capacity",
        "decide from dated supply, allocation or lead-time disclosures; UNKNOWN when only demand claims exist"
      ),
      variable(
        "adoption and deployment penetration",
        "share of the relevant population actually running the capability in a product",
        "deployment, not announcement, is what changes labour, cost and competition",
        "decide from dated deployment disclosures by the deploying organization; UNKNOWN when only vendor-reported usage exists"
      ),
      variable(
        "ecosystem breadth and substitutability",
        "number and interchangeability of tools and providers serving the same job",
        "substitutes set the price ceiling and the switching cost",
        "decide UP when an interchangeable alternative is dated and available, DOWN when integration makes switching costly; else UNKNOWN"
      ),
      variable(
        "standards and interface compatibility",
        "adopted interfaces, formats and conformance requirements",
        "standards decide interoperability, portability and commoditization",
        "decide from a dated standards document or conformance record; UNKNOWN when only a roadmap exists"
      ),
      variable(
        "data access and intellectual-property position",
        "rights, licences and restrictions attached to training and input data",
        "rights decide who may legally build the same capability",
        "decide from dated licence terms, filings or litigation records; UNKNOWN when terms are unpublished"
      ),
      variable(
        "regulatory and safety posture",
        "binding obligations and evaluation requirements that apply to the capability",
        "obligations change compliance cost and who is allowed to deploy",
        "decide from enacted text and effective dates; UNKNOWN when only announced intent exists"
      )
    ),
    List.of(
      mechanism(
        "capability gain",
        "task substitution",
        "a cheaper or better capability makes an existing task automatable, shifting labour and process demand",
        "an independent dated capability measurement plus a dated adoption record for the substituted task"
      ),
      mechanism(
        "unit cost decline",
        "adoption and complement demand",
        "a lower unit cost widens the set of profitable uses and raises demand for complements such as compute and power",
        "a dated cost series for a defined unit plus a dated demand or procurement record"
      ),
      mechanism(
        "substitutable release",
        "price competition and margin compression",
        "a substitutable alternative lowers the price ceiling for equivalent offerings",
        "a dated release with licence terms plus comparable dated prices for the same job"
      ),
      mechanism(
        "compute constraint",
        "capability inequality",
        "limited compute concentrates training and serving capacity among fewer actors",
        "dated allocation, lead-time or capacity records rather than demand claims"
      ),
      mechanism(
        "unverified capability claim",
        "expectation repricing",
        "an unverified claim can move plans and valuations before any deployment exists",
        "the dated claim, the dated reaction, and a later dated deployment or measurement that tests it"
      )
    ),
    List.of(
      metric(
        "independent evaluation of the deployed configuration",
        "published evaluation reports and technical documentation with dates",
        "per release",
        "a measurement of the deployed configuration improves against a comparable baseline",
        "no independent measurement appears, or the measured configuration differs from the deployed one"
      ),
      metric(
        "dated pricing and usage limits for a defined unit",
        "official pricing and quota pages, including archived versions",
        "per change",
        "the price for the same unit falls or quotas widen",
        "the price rises for the same unit, quotas tighten, or the unit definition changes"
      ),
      metric(
        "deployment and procurement records",
        "deploying organization disclosures and public procurement notices",
        "per announcement",
        "a named organization states a production deployment with a date",
        "deployments remain pilots, or no named production deployment appears"
      ),
      metric(
        "independent release and conformance records",
        "release notes, licence text and standards-body conformance registers",
        "per release",
        "a substitutable release with clear licence terms appears",
        "releases add restrictions, or no substitutable release appears"
      )
    ),
    List.of(
      "a benchmark score is not deployed capability",
      "an unreproducible demonstration and a vendor-reported usage number are claims, not measurements",
      "a silent model or configuration update invalidates a before-and-after comparison",
      "framing a capability as general intelligence is not a testable claim",
      "announced capability, planned capacity and shipped capability are three different things"
    ),
    "Separate deployed capability from announced or benchmark capability, and require a dated measurement before treating a capability change as established. Keep cost, latency, compute and licensing as separate variables instead of folding them into one capability story."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.AI;
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
