package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * REAL_ESTATE dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.8). Recommendations only: an asking price
 * is never treated as a transaction price.
 */
@Component
public final class RealEstateDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.REAL_ESTATE,
    SPECIFICITY,
    List.of(
      variable(
        "credit availability and financing cost",
        "mortgage and development credit terms, and who can still obtain them",
        "credit decides how much of a price is affordable to a buyer at any given rate",
        "decide from dated lending terms and lending-volume records; UNKNOWN when only an advertised rate is quoted"
      ),
      variable(
        "transaction volume and absorption",
        "completed transactions and how quickly available stock is taken up",
        "volume shows whether prices are clearing or only being asked",
        "decide from dated registry completions and absorption records; UNKNOWN when only listings are available"
      ),
      variable(
        "inventory (vacancy, unsold and distressed stock)",
        "the stock available, unsold or in foreclosure",
        "inventory decides how long prices can be supported without sales",
        "decide from dated vacancy, unsold-unit and foreclosure records; UNKNOWN when inventory definitions differ between sources"
      ),
      variable(
        "rents and net operating income",
        "contract rents and the income the property earns after operating cost",
        "rent is the cash return that anchors value when price expectations change",
        "decide from dated lease schedules or registry records; UNKNOWN when only advertised rents exist"
      ),
      variable(
        "prices, cap rates and yields",
        "transaction prices and the yield implied by them",
        "the yield decides whether a price is justified by the income the asset produces",
        "decide from dated transaction records and a stated yield method; UNKNOWN when the method or the comparables are unstated"
      ),
      variable(
        "land supply and zoning",
        "permitted development rights, land available and how easily they can be changed",
        "supply elasticity decides whether a demand increase becomes more building or higher prices",
        "decide from dated planning decisions and land records; UNKNOWN when permitted capacity is not published"
      ),
      variable(
        "construction pipeline and starts",
        "permitted, started and completed construction with dates",
        "the pipeline is supply that arrives later, and it decides future vacancy",
        "decide from dated permit and start records; UNKNOWN when only developer announcements exist"
      ),
      variable(
        "demographics and migration",
        "household formation, population movement and the income of arriving households",
        "household formation is the demand unit that local supply must meet",
        "decide from dated census or registration records; UNKNOWN when projections are used instead of observations"
      )
    ),
    List.of(
      mechanism(
        "financing cost",
        "affordability, transaction volume and prices",
        "a higher financing cost reduces what a buyer can pay, which lowers transaction volume first and prices later",
        "a dated lending-term change plus dated volume and price series covering the lag"
      ),
      mechanism(
        "construction pipeline",
        "vacancy and rents",
        "new supply arriving faster than household formation raises vacancy and weakens rents",
        "dated completion records plus dated vacancy and rent series"
      ),
      mechanism(
        "zoning and land supply",
        "supply elasticity",
        "where permitted density can respond, a demand increase produces building rather than only price increases",
        "dated planning decisions plus dated permit and price series for the same area"
      ),
      mechanism(
        "credit tightening",
        "developer distress",
        "developers who must refinance in a tighter market sell assets or default",
        "a dated credit-condition change plus dated refinancing, distress or auction records"
      ),
      mechanism(
        "migration",
        "local demand",
        "arriving households need housing in a specific place, so local supply elasticity decides the price response",
        "dated migration records plus dated local rent and volume series"
      )
    ),
    List.of(
      metric(
        "land and title registry records",
        "official land registries and recorded transaction records",
        "per registration",
        "a recorded transaction at a dated price confirms the move",
        "records show no transaction, or the recorded price differs from the quoted price"
      ),
      metric(
        "official price indices with a stated method",
        "statistical agencies and index providers that publish their methodology",
        "per index release",
        "the index moves as the hypothesis requires under a stable method",
        "the method or composition changed, breaking comparability"
      ),
      metric(
        "planning permits and applications",
        "local planning authorities and permit registers",
        "per application",
        "dated approvals add permitted capacity in the relevant area",
        "applications are refused, withdrawn, or approved capacity is not built"
      ),
      metric(
        "REIT filings, appraisal records and auction results",
        "listed-vehicle filings, appraisal districts and auction records",
        "per reporting period",
        "dated filings or auction results confirm occupancy, rent or price",
        "disclosures are aggregated away from the relevant segment, or auctions fail to attract bids"
      )
    ),
    List.of(
      "an asking price is not a transaction price",
      "repeat-sales and median indices measure different things and are not interchangeable",
      "a thin market's comparables are not a market price",
      "an appraisal lags the market it describes",
      "developer-reported absorption is a self-report",
      "land banking is not demand"
    ),
    "Use recorded transactions and registry data rather than listings or asking prices, and state which index method the comparison uses. Keep credit, volume, inventory, rent, price, land supply and construction separate, because each moves on a different lag."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.REAL_ESTATE;
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
