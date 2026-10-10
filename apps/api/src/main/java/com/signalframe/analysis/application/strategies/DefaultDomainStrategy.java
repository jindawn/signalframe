package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The fallback strategy for {@link DomainType#OTHER} and for every domain without a dictionary
 * (DS-02, DS-03, DS-05).
 *
 * <p>It is the only strategy with specificity 0 and the only one that claims every domain, so an
 * unknown or future domain can never leave the pipeline without guidance. Its dictionary is the
 * protocol itself: separate the reported claim from its verification status, require a mechanism
 * with evidence on both ends, and emit UNKNOWN where the source is silent.
 */
@Component
public final class DefaultDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 0;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.OTHER,
    SPECIFICITY,
    List.of(
      variable(
        "the change the source asserts",
        "what changed, over which period, and against which comparison",
        "a change with no comparison cannot be verified or sized",
        "decide from two dated comparable states of the same thing; UNKNOWN when the source gives only one state"
      ),
      variable(
        "claim versus verification status",
        "whether the central statement is reported, corroborated or disputed",
        "reported is the default status and it limits what may be concluded",
        "decide from independent confirmation or conflict; repetition is not verification"
      ),
      variable(
        "expectation versus outcome",
        "what was expected before the event and what is observed after it",
        "the gap between expectation and outcome is what an event changes",
        "decide from a dated pre-event expectation and the realized outcome; UNKNOWN without a pre-event record"
      ),
      variable(
        "mechanism and its evidence",
        "the causal step offered and the evidence attached to each end of it",
        "a relation with evidence on only one end is not a mechanism",
        "decide from fact references at both ends; UNKNOWN when only correlation is stated"
      ),
      variable(
        "stakeholders and exposure",
        "the named actors exposed to the change and the direction of their exposure",
        "exposure decides who is affected and through which channel",
        "decide from a traced path through the mechanism; UNKNOWN when the path is unstated"
      ),
      variable(
        "time horizon",
        "when an outcome from the change would be observable",
        "an unbounded claim cannot be checked and cannot be falsified",
        "decide from a stated date or window; UNKNOWN when no horizon is given"
      ),
      variable(
        "the strongest objection",
        "the best available reason the current reading is wrong",
        "a reading that was never challenged is not an analysis",
        "decide from a stated objection with a reference; UNKNOWN when objections were not sought"
      )
    ),
    List.of(
      mechanism(
        "announcement",
        "implementation",
        "a stated intention changes outcomes only once an implementation step follows it",
        "the dated announcement plus a dated implementation, filing or delivery record"
      ),
      mechanism(
        "expectation",
        "repricing",
        "an outcome reprices a claim only relative to the expectation that was in place before it",
        "a dated pre-event expectation plus the realized outcome"
      ),
      mechanism(
        "binding constraint",
        "price",
        "a limit on supply raises price until the limit eases or demand falls",
        "a dated statement of the constraint plus dated price and quantity data"
      ),
      mechanism(
        "incentive change",
        "behaviour",
        "participants respond to the incentive they are actually paid on",
        "the dated incentive terms plus a dated change in observed behaviour"
      ),
      mechanism(
        "claim",
        "belief",
        "a claim can move expectations before any observable change occurs",
        "the dated claim plus a dated expectation measure and a later observation that tests it"
      )
    ),
    List.of(
      metric(
        "primary documents and official releases",
        "official registers, filings and statistical releases",
        "per event",
        "a dated primary document states the change the reading requires",
        "no primary document exists, or it contradicts or restates the sourced account"
      ),
      metric(
        "an independent second source using a different method",
        "publications owned by a different publisher with a different method",
        "per event",
        "an independent source reports the same substance with its own evidence",
        "only copies of the same original appear, which is not corroboration"
      ),
      metric(
        "a dated series that would move if the mechanism held",
        "official time series with a published methodology",
        "per release",
        "the series moves in the direction the mechanism requires",
        "the series is flat, unavailable, or moves against the mechanism"
      ),
      metric(
        "the pre-registered falsification record",
        "the snapshot's own falsification conditions and the place named for verification",
        "at the stated deadline",
        "the named observable is present at the deadline",
        "the named observable is absent, or the condition cannot be checked at the deadline"
      )
    ),
    List.of(
      "a single source is reported, not corroborated",
      "repetition is not evidence",
      "a missing previous state is an UNKNOWN, not a direction",
      "an unbounded claim cannot be falsified and is not a hypothesis",
      "a plausible story is not a documented mechanism"
    ),
    "There is no specialised dictionary for this domain. Apply the protocol directly: separate reported claims from verification, prefer UNKNOWN over unsupported inference, and require a mechanism with evidence on both ends before treating a relation as causal."
  );

  /** DS-03: the fallback claims every domain. */
  @Override
  public boolean supports(DomainType domain) {
    return true;
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
