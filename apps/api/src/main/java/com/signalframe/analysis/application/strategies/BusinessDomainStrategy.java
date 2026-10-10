package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * BUSINESS dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.3). Wave 2A scope; recommendations only, with
 * no financial figure and no claim that any figure moved.
 */
@Component
public final class BusinessDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.BUSINESS,
    SPECIFICITY,
    List.of(
      variable(
        "revenue mix by segment and geography",
        "the split of reported revenue across the segments the company itself defines",
        "mix decides which margin structure and which demand cycle the business is actually exposed to",
        "decide from comparable periods under one stated segment definition; UNKNOWN when the definition changes"
      ),
      variable(
        "gross margin and unit economics",
        "revenue less direct cost, read per unit sold and per customer",
        "unit economics decide whether growth adds or destroys value",
        "decide from dated comparable disclosures of the same scope; UNKNOWN when only adjusted figures are given"
      ),
      variable(
        "pricing actions",
        "list or realized price changes with the date they took effect",
        "price is the fastest lever on margin and the fastest trigger of volume loss",
        "decide from dated price lists or disclosed pricing actions; UNKNOWN when only a stated intention exists"
      ),
      variable(
        "customer concentration",
        "share of revenue contributed by the largest customers",
        "concentration decides bargaining power and how durable the revenue is",
        "decide from dated disclosures of largest-customer share; UNKNOWN when customer detail is withheld"
      ),
      variable(
        "supplier concentration",
        "share of input volume or cost from the largest suppliers",
        "supplier concentration transfers pricing power from the company to the supplier",
        "decide from dated supplier or single-source disclosures; UNKNOWN when sourcing is undisclosed"
      ),
      variable(
        "channel mix and channel economics",
        "share of sales by channel and the margin each channel carries",
        "channel shift changes margin structure and who owns the customer relationship",
        "decide from dated channel disclosures under one stated definition; UNKNOWN when channel definitions change"
      ),
      variable(
        "competitive share",
        "unit or revenue share against a defined market",
        "share movement separates market growth from displacement of a competitor",
        "decide from dated third-party or filed share estimates with a stated market definition; UNKNOWN when the denominator is unstated"
      ),
      variable(
        "cash conversion and free cash flow",
        "operating cash flow against reported earnings and capital spending",
        "cash conversion shows whether reported profit is actually collected",
        "decide from dated cash-flow statements for the same period and scope; UNKNOWN when the periods differ"
      ),
      variable(
        "capital spending and capacity utilization",
        "investment committed to capacity and the share of that capacity in use",
        "utilization decides whether fixed costs are absorbed and whether new capacity is needed",
        "decide from dated utilization or capital-spending disclosures; UNKNOWN when capacity is not defined"
      ),
      variable(
        "retention and competitive moat indicators",
        "observable retention, price premium and evidence that an entrant must replicate a costly asset",
        "a durable advantage is what keeps returns from being competed away",
        "decide from dated retention, price-premium and entry records; UNKNOWN when only management asserts a moat"
      )
    ),
    List.of(
      mechanism(
        "pricing action",
        "volume and margin",
        "a price increase trades volume for margin, and the net effect depends on the elasticity of the affected customers",
        "a dated price change, dated volumes for the affected segment, and a dated margin disclosure"
      ),
      mechanism(
        "churn",
        "lifetime value",
        "losing customers reduces the revenue each acquisition can repay, so acquisition spending buys less",
        "a dated retention or churn disclosure plus a dated acquisition-cost disclosure"
      ),
      mechanism(
        "capacity utilization",
        "fixed-cost absorption",
        "underused capacity spreads fixed cost over fewer units and compresses margin until volume recovers",
        "a dated utilization disclosure plus a dated margin disclosure for the same period"
      ),
      mechanism(
        "customer concentration",
        "bargaining power",
        "a customer large enough to move the revenue line can negotiate price and terms",
        "a dated concentration disclosure plus a dated contract or pricing change involving that customer"
      ),
      mechanism(
        "channel incentive design",
        "channel behaviour",
        "a channel responds to the incentive it is actually paid on, not to the one announced",
        "the dated incentive terms plus a dated change in channel mix or sell-through"
      )
    ),
    List.of(
      metric(
        "audited filings and annual reports",
        "company filings, exchange disclosures and audited statements with dates",
        "per reporting period",
        "the filing states the figure with a comparable prior period under the same definition",
        "the figure is unaudited, restated, or the prior period is redefined"
      ),
      metric(
        "earnings-call transcripts and guidance revisions",
        "official transcripts and dated guidance statements",
        "per quarter",
        "management guidance moves in the direction the hypothesis requires and the next filing confirms it",
        "guidance is withdrawn, restated, or the next filing contradicts it"
      ),
      metric(
        "official price lists and channel disclosures",
        "company price lists, distributor terms and dated channel notices",
        "per change",
        "a dated price or channel term changes as the hypothesis requires",
        "prices and terms are unchanged, or a discount offsets the announced change"
      ),
      metric(
        "registries, trademark and patent filings",
        "regulatory registries and intellectual-property filing databases",
        "per filing",
        "a dated filing shows a new product, market or protected asset",
        "no filing appears within the stated window, or the filing lapses"
      )
    ),
    List.of(
      "an adjusted or non-GAAP measure is not comparable to a reported figure",
      "a record period without a comparable base is not growth",
      "a channel check presented as measured data is an anecdote",
      "survivor bias in case studies overstates what works",
      "a definitional change in a reported metric is not a performance change"
    ),
    "Prefer filed and audited figures over management characterizations, and read every figure against a comparable period under one stated definition. Treat margins, pricing, concentration, channel and cash conversion as separate variables that must each be evidenced."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.BUSINESS;
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
