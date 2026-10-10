package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * MACRO dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.5). Recommendations only: the strategy may not
 * state that any aggregate rose, fell or will turn.
 */
@Component
public final class MacroDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.MACRO,
    SPECIFICITY,
    List.of(
      variable(
        "activity (output and survey measures)",
        "output aggregates alongside the survey measures that anticipate them",
        "activity decides the demand that everything else in the story is measured against",
        "decide from comparable dated releases under one seasonal-adjustment convention; UNKNOWN when the frequency or basis differs"
      ),
      variable(
        "inflation (headline, core and wage measures)",
        "price growth read separately for headline, core and wages",
        "the composition of inflation decides which policy response is plausible",
        "decide from dated comparable releases of each measure; UNKNOWN when only one aggregate is given"
      ),
      variable(
        "policy rate and expectations",
        "the policy rate and the market-implied path over the relevant horizon",
        "the expected path, not the current level, is what positions are priced against",
        "decide from dated official decisions and dated implied paths; UNKNOWN when the horizon is unstated"
      ),
      variable(
        "employment and participation",
        "employment levels, unemployment breadth and who is in the labour force",
        "participation decides whether employment growth reflects demand or supply",
        "decide from comparable dated surveys read together; UNKNOWN when only one survey is available"
      ),
      variable(
        "credit growth and lending conditions",
        "credit outstanding and the standards lenders report applying",
        "credit is the transmission channel from policy to demand",
        "decide from dated credit and lending-survey releases; UNKNOWN when the series is discontinued or rebased"
      ),
      variable(
        "exchange rate and external balance",
        "the currency and the trade and current-account position",
        "the exchange rate decides how external price changes reach domestic prices",
        "decide from dated official and settlement records; UNKNOWN when only an unofficial rate is quoted"
      ),
      variable(
        "fiscal stance",
        "planned spending, revenue and borrowing relative to the stated baseline",
        "the fiscal impulse decides how much demand policy is adding or withdrawing",
        "decide from enacted budget documents with dates; UNKNOWN when only announced plans exist"
      ),
      variable(
        "inventories and capacity utilization",
        "stock levels and the share of industrial capacity in use",
        "the inventory cycle decides whether production swings amplify or damp demand",
        "decide from dated inventory and utilization releases; UNKNOWN when the series is survey-only and unreconciled"
      )
    ),
    List.of(
      mechanism(
        "policy rate",
        "credit conditions and demand",
        "a higher policy rate raises borrowing costs, which reduces credit growth and demand with a lag",
        "a dated policy decision, a dated credit series, and a dated demand aggregate covering the lag"
      ),
      mechanism(
        "wage growth",
        "services inflation",
        "wages are the largest cost in labour-intensive services, so sustained wage growth passes into service prices",
        "a dated wage series plus a dated services-price series over the same period"
      ),
      mechanism(
        "exchange rate",
        "import prices",
        "a weaker currency raises the domestic price of imported goods and inputs",
        "a dated exchange-rate move plus a dated import-price series"
      ),
      mechanism(
        "fiscal impulse",
        "demand",
        "a change in the fiscal balance adds to or subtracts from demand independently of monetary policy",
        "enacted budget figures with dates plus a dated demand aggregate"
      ),
      mechanism(
        "inventory cycle",
        "production swings",
        "restocking and destocking amplify output movements beyond the change in final demand",
        "a dated inventory series plus a dated production series"
      )
    ),
    List.of(
      metric(
        "official statistics releases with revision history",
        "national statistics agencies and central-bank publications",
        "per release",
        "the revised series confirms the direction the hypothesis requires",
        "the first print is revised away, or the revision history shows no stable move"
      ),
      metric(
        "central-bank statements and minutes",
        "official central-bank publications with dates",
        "per meeting",
        "the stated reaction function and the decided rate match the hypothesis",
        "the decision or the stated reasoning contradicts it"
      ),
      metric(
        "market-implied rate path",
        "dated futures and swap-implied path series",
        "per session",
        "the implied path moves in the direction the hypothesis requires",
        "the implied path is unchanged or moves the other way"
      ),
      metric(
        "trade, customs and survey series with published methodology",
        "customs authorities and survey providers that publish their method",
        "per release",
        "the series moves as the mechanism requires under a stable methodology",
        "the series is rebased or redefined, making the comparison unusable"
      )
    ),
    List.of(
      "a first print is not the final figure; revision history must be read",
      "a seasonal-adjustment convention is not the raw level",
      "a shared level trend is not evidence that two series are causally linked",
      "a leading-indicator claim without a published track record is an assertion",
      "an official release can be politically influenced; provenance is not the same as independence",
      "one country's pattern is not a general rule"
    ),
    "Read levels and changes separately, and always against the revision history of the series being cited. Policy acts with a lag, so a dated sequence is required before any transmission from rates or budgets to demand and prices can be stated."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.MACRO;
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
