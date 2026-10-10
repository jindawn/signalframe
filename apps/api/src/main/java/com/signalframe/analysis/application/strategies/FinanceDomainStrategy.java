package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * FINANCE dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.4). Wave 2A scope; recommendations only, with
 * no price, no forecast and no probability.
 */
@Component
public final class FinanceDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.FINANCE,
    SPECIFICITY,
    List.of(
      variable(
        "expectation gap",
        "the realized outcome against the consensus expectation that was in place immediately before it",
        "the surprise, not the level, is what reprices a claim",
        "decide from a dated consensus snapshot and the realized outcome for the same period; UNKNOWN when no pre-release consensus exists"
      ),
      variable(
        "interest rate and implied policy path",
        "the policy rate and the market-implied path over the relevant horizon",
        "the discount rate moves the present value of every cash flow in the story",
        "decide from dated official decisions and dated market-implied paths; UNKNOWN when the horizon is unstated"
      ),
      variable(
        "funding and liquidity conditions",
        "availability and price of short-term funding and refinancing",
        "liquidity decides whether a solvency problem becomes a default",
        "decide from dated spreads, issuance results and facility disclosures; UNKNOWN when terms are undisclosed"
      ),
      variable(
        "earnings and guidance revisions",
        "reported earnings and changes to forward guidance",
        "revisions reset the number the market is discounting",
        "decide from dated filings and dated guidance changes; UNKNOWN when guidance is withdrawn or absent"
      ),
      variable(
        "valuation multiples and prices",
        "price relative to a stated fundamental measure",
        "valuation is the translation of expectations into price",
        "decide from dated prices and a stated denominator; UNKNOWN when the multiple is not defined"
      ),
      variable(
        "credit spreads, ratings and default risk",
        "compensation demanded for credit risk and any rating action",
        "spreads price the market's view of repayment, which equity price need not reflect",
        "decide from dated spread and rating records; UNKNOWN when the instrument is illiquid or unpriced"
      ),
      variable(
        "risk premium and cost of capital",
        "the excess return demanded for holding the asset",
        "the risk premium sets the hurdle every project and valuation must clear",
        "decide from dated comparable inputs under a stated method; UNKNOWN when the method is unstated"
      ),
      variable(
        "positioning and fund flows",
        "who holds the asset and how crowded the position is",
        "positioning decides the size and speed of a repricing when the expectation moves",
        "decide from dated positioning and flow reports; UNKNOWN when only anecdote or survey exists"
      )
    ),
    List.of(
      mechanism(
        "interest rate expectations",
        "valuation multiples",
        "a higher discount rate compresses the present value of distant cash flows",
        "a dated rate-expectation series and a dated multiple for the same asset class"
      ),
      mechanism(
        "expectation surprise",
        "estimate revisions and price",
        "an outcome away from the pre-existing expectation forces estimates to be revised, and price follows the revision",
        "a dated pre-event consensus, the realized outcome, and dated revisions by independent forecasters"
      ),
      mechanism(
        "credit conditions",
        "refinancing and capital spending",
        "tighter credit raises the cost of refinancing and forces capital plans to be cut",
        "a dated spread or lending-condition series plus a dated financing or capital-plan change"
      ),
      mechanism(
        "positioning",
        "squeeze dynamics",
        "crowded positions amplify a move when holders must exit at the same time",
        "a dated positioning series plus dated price and volume evidence of forced exits"
      ),
      mechanism(
        "index inclusion",
        "flow effects",
        "mandatory index tracking creates demand that is unrelated to the fundamentals of the issuer",
        "a dated index decision with an effective date plus dated fund-flow records around it"
      )
    ),
    List.of(
      metric(
        "exchange filings, prospectuses and audited statements",
        "issuer filings, exchange disclosure systems and audited reports with dates",
        "per reporting period",
        "the filing states the figure or event the hypothesis requires, with a comparable period",
        "the filing is absent, amended, or says something different from the sourced account"
      ),
      metric(
        "official rate decisions, minutes and statistical releases",
        "central-bank and statistical-agency publications with dates",
        "per meeting and per release",
        "the official text or the implied path moves in the direction the hypothesis requires",
        "the official text or the implied path moves the other way"
      ),
      metric(
        "settlement prices, issuance and auction results",
        "official exchange and settlement records, auction and issuance results",
        "per session and per auction",
        "a dated settlement or auction result confirms the move",
        "the recorded price is stale, illiquid, or the auction fails to clear as expected"
      ),
      metric(
        "rating actions and credit records",
        "rating-agency publications and dated spread series",
        "per action",
        "a rating or spread change confirms the credit reading with a date",
        "no rating action occurs, or spreads move against the reading"
      )
    ),
    List.of(
      "a price move is not confirmation of a causal narrative written after it",
      "a single session is noise, not a signal",
      "a consensus estimate is not an outcome",
      "a backtest with fitted parameters is not evidence",
      "an illiquid mark is not a market price",
      "survivorship and delisting bias flatter historical comparisons"
    ),
    "Never treat a price change as an explanation of itself: state the expectation that was in place first, then the outcome, then the mechanism. Keep rate expectations, liquidity, earnings, valuation, risk premium and positioning separate rather than letting one market move stand for all of them."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.FINANCE;
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
