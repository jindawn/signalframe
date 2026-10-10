package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CONSUMER dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.10). Recommendations only: a stated intention
 * is never treated as observed behaviour.
 */
@Component
public final class ConsumerDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.CONSUMER,
    SPECIFICITY,
    List.of(
      variable(
        "real disposable income",
        "income available to spend after taxes and price changes",
        "income is the budget constraint every spending claim must fit inside",
        "decide from dated official income and price series together; UNKNOWN when only nominal income is given"
      ),
      variable(
        "savings rate and credit availability",
        "the share of income not spent and the terms on which households can borrow",
        "saving and borrowing decide how much spending can run ahead of income",
        "decide from dated savings and credit records; UNKNOWN when delinquency or approvals are unreported"
      ),
      variable(
        "sentiment and expectations",
        "what households say about their finances and the outlook",
        "expectations can shift spending before income changes",
        "decide from a dated survey with a published sample design; UNKNOWN when only a headline index is quoted"
      ),
      variable(
        "category price and demand elasticity",
        "how much volume responds to a price change in the category being discussed",
        "elasticity decides whether a price increase raises revenue or destroys it",
        "decide from dated price and volume pairs for the same category; UNKNOWN when the response is asserted without a comparable pair"
      ),
      variable(
        "premium versus value mix",
        "the share of purchases at premium and value tiers",
        "mix shows whether customers are trading up, trading down or leaving",
        "decide from dated tier-mix disclosures under one stated definition; UNKNOWN when tiers are redefined"
      ),
      variable(
        "channel shift",
        "where purchases happen and how that has changed",
        "channel decides cost to serve and who controls the customer relationship",
        "decide from dated channel disclosures; UNKNOWN when channel definitions change between periods"
      ),
      variable(
        "penetration and purchase frequency",
        "how many households buy the category and how often they buy it",
        "penetration and frequency separate new demand from deeper use by existing buyers",
        "decide from dated panel or official data with a stated method; UNKNOWN when only shipments are available"
      ),
      variable(
        "retention and substitution",
        "whether buyers return and what they switch to instead",
        "retention and switching decide whether demand is repeatable or borrowed",
        "decide from dated repeat-purchase and switching records; UNKNOWN when only intention surveys exist"
      )
    ),
    List.of(
      mechanism(
        "real disposable income",
        "category spending",
        "a change in income changes the budget available before any change in preference",
        "a dated income series plus a dated spending series for the same category and period"
      ),
      mechanism(
        "price increase",
        "elasticity, volume and mix",
        "a price increase trades volume for margin, and customers may also trade down within the category",
        "a dated price change plus dated volume and tier-mix data for the same category"
      ),
      mechanism(
        "promotion",
        "trial and repeat",
        "a promotion buys trial, but repeat depends on whether the product met the need",
        "a dated promotion plus dated trial and repeat-purchase data"
      ),
      mechanism(
        "credit availability",
        "big-ticket demand",
        "when borrowing terms tighten, purchases that need financing are postponed first",
        "a dated credit-term change plus dated demand for the financed category"
      ),
      mechanism(
        "channel shift",
        "margin structure",
        "a different channel carries a different cost to serve and a different owner of the customer",
        "a dated channel-mix change plus dated margin data for the same periods"
      )
    ),
    List.of(
      metric(
        "national accounts and retail sales",
        "statistical agencies' retail and household-sector releases",
        "per release",
        "the official series moves as the hypothesis requires under a stable definition",
        "the series is rebased or redefined, or moves against the reading"
      ),
      metric(
        "company-reported comparable sales with stated definitions",
        "company filings and dated trading statements",
        "per reporting period",
        "the disclosed comparable figure moves as the hypothesis requires",
        "the comparable definition changes, or the figure excludes the affected period"
      ),
      metric(
        "panel or card data with a disclosed method",
        "data providers that publish sample construction and weighting",
        "per reporting period",
        "the panel result is reproduced by an independent method",
        "the result cannot be reproduced, or the sample changed between periods"
      ),
      metric(
        "survey series with a published sample design",
        "official and academic surveys with published methodology",
        "per survey wave",
        "stated expectations and later observed behaviour agree",
        "stated intentions and observed purchases diverge"
      )
    ),
    List.of(
      "a sentiment index without a published track record is not a predictor",
      "a change in the comparable-sales definition is not a change in demand",
      "panel attrition and weighting changes move results without any consumer changing behaviour",
      "a stated intention is not a purchase",
      "seasonality and the promotional calendar explain many apparent shifts"
    ),
    "Prefer observed purchases to stated intentions, and read every figure against the definition used for the comparison period. Keep income, price, frequency, channel, penetration and substitution separate instead of letting one sales figure carry the whole consumer story."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.CONSUMER;
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
