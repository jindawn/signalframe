package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ENERGY dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.9). Recommendations only: a reserve estimate is
 * never treated as production and a target is never treated as built capacity.
 */
@Component
public final class EnergyDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.ENERGY,
    SPECIFICITY,
    List.of(
      variable(
        "supply and demand balance",
        "production and consumption at a stated place and time, and the difference between them",
        "the balance decides whether the system is pricing scarcity or surplus",
        "decide from comparable dated balance data under one reporting convention; UNKNOWN when place or period is unstated"
      ),
      variable(
        "inventory and storage levels",
        "stocks held in storage against their seasonal range",
        "inventory absorbs shocks, so the same disruption matters differently at different stock levels",
        "decide from dated storage reports against their seasonal comparison; UNKNOWN when the comparison basis is unstated"
      ),
      variable(
        "capacity additions and retirements",
        "capacity that has actually been commissioned or withdrawn, with dates",
        "commissioning and retirement dates decide when the balance actually changes",
        "decide from dated commissioning or retirement records; UNKNOWN when only announced projects exist"
      ),
      variable(
        "marginal cost and cost-curve position",
        "the cost of the next unit supplied and where it sits on the cost curve",
        "the marginal unit sets the price at the margin, not the average cost",
        "decide from dated cost or price assessments with a stated method; UNKNOWN when costs are asserted without a method"
      ),
      variable(
        "transport, grid and interconnection constraints",
        "whether a route or connection can carry the volume, and the wait to obtain one",
        "constraints separate one location's price from another's and decide whether supply can clear",
        "decide from dated queue, outage and congestion records; UNKNOWN when only nameplate capacity is known"
      ),
      variable(
        "substitution and fuel switching",
        "the relative cost and feasibility of switching between sources for the same use",
        "substitution sets the ceiling on how far one source's price can move demand",
        "decide from dated relative-cost and switching records; UNKNOWN when switching requires unavailable equipment"
      ),
      variable(
        "regulation, incentives and taxation",
        "binding rules, subsidies and taxes that change the cost of one source relative to another",
        "policy changes relative cost, which moves demand and investment between sources",
        "decide from enacted text and effective dates; UNKNOWN when only announced targets exist"
      ),
      variable(
        "geopolitical exposure of supply",
        "how much of the supply or transit passes through a single controlling party",
        "concentration of supply or transit is what makes a disruption consequential",
        "decide from dated trade and transit data; UNKNOWN when exposure is asserted without figures"
      )
    ),
    List.of(
      mechanism(
        "inventory level",
        "spot price and forward curve",
        "low stocks relative to the seasonal range leave little buffer, so a small disruption moves the spot price and reshapes the forward curve",
        "dated inventory data against a seasonal range plus dated spot and forward prices"
      ),
      mechanism(
        "retirement of capacity",
        "tightness and price spikes",
        "removing capacity without replacement reduces the buffer, so the same demand surprise produces a larger price move",
        "dated retirement records plus dated price and reserve-margin data"
      ),
      mechanism(
        "incentive or subsidy",
        "capital spending and supply with a lead time",
        "an incentive improves project returns, and the resulting supply arrives only after construction lead time",
        "enacted incentive text, dated investment decisions, and dated commissioning records"
      ),
      mechanism(
        "congestion",
        "locational price divergence",
        "a constrained route cannot carry the cheap supply to where it is needed, so the two locations price differently",
        "dated congestion or dispatch data plus dated locational prices"
      ),
      mechanism(
        "relative fuel cost",
        "switching and relative demand",
        "a cheaper substitute changes which source is dispatched for the same use",
        "dated relative-cost series plus dated generation or consumption mix data"
      )
    ),
    List.of(
      metric(
        "national statistics and international agency series",
        "statistical agencies and agency publications with stated methodology",
        "per release",
        "the series moves as the mechanism requires under a stable methodology",
        "the series is rebased or the methodology changed, breaking comparability"
      ),
      metric(
        "grid-operator dispatch, congestion and outage data",
        "system operators' published operational records",
        "per dispatch interval and per outage",
        "dispatch or outage records show the constraint or shift the hypothesis requires",
        "operational records show no change, or the constraint is relieved earlier than stated"
      ),
      metric(
        "storage and inventory reports",
        "official and industry storage reports with dates",
        "per reporting period",
        "stocks fall below their seasonal range as the hypothesis requires",
        "stocks remain within range or rebuild"
      ),
      metric(
        "interconnection queues and capacity auction results",
        "system-operator queue registers and auction results",
        "per auction and per queue update",
        "dated queue progress or auction clearance shows new capacity arriving",
        "projects leave the queue without being built, or auctions clear with no new capacity"
      )
    ),
    List.of(
      "a single-session price print is not a trend",
      "weather attribution without a comparable baseline is not explanation",
      "a reserve estimate is not production",
      "a quota announcement is not compliance",
      "a policy target is not built capacity",
      "energy and capacity are different units and cannot be compared directly"
    ),
    "Distinguish flows from stocks and energy from capacity before comparing anything, and state the place, period and method of every series. Treat announced projects, targets and reserves as intentions until dated commissioning or production records exist."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.ENERGY;
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
