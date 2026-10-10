package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * GEOPOLITICS dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.7). Recommendations only: a statement is
 * never treated as capability, and a statement is never treated as an action.
 */
@Component
public final class GeopoliticsDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.GEOPOLITICS,
    SPECIFICITY,
    List.of(
      variable(
        "declared interest versus demonstrated priority",
        "the interest a party states and the resources it has actually committed to it",
        "stated interests are cheap; allocation reveals which ones are real",
        "decide from dated resource allocation against the stated interest; UNKNOWN when only statements exist"
      ),
      variable(
        "capability balance",
        "relative military, industrial and technological capability between the parties involved",
        "the balance constrains which outcomes are achievable regardless of intent",
        "decide from dated capability assessments that state their method; UNKNOWN when only claims by a party are available"
      ),
      variable(
        "military and logistics capacity and readiness",
        "forces, stocks, transport and sustainment available for a stated operation",
        "logistics and sustainment decide whether a capability can be used at range",
        "decide from dated force, procurement and movement records; UNKNOWN when assessments are single-sourced"
      ),
      variable(
        "trade, energy and technology dependency",
        "concentration of trade, energy or technology supply in the hands of the other party",
        "dependency decides who can impose cost without military means",
        "decide from dated customs, trade and supply statistics; UNKNOWN when the dependency is asserted without figures"
      ),
      variable(
        "sanctions and export-control capacity and exposure",
        "what each side can restrict, and what it would lose by restricting it",
        "restriction capacity is bounded by the restricting side's own exposure",
        "decide from dated enacted measures, listings and trade exposure; UNKNOWN when only threatened measures exist"
      ),
      variable(
        "alliance alignment and treaty commitment",
        "formal commitments and the observed behaviour of the committed parties",
        "credibility depends on behaviour, not on the text of the commitment",
        "decide from dated treaty text and dated behaviour under it; UNKNOWN when commitments are informal"
      ),
      variable(
        "leverage and escalation-ladder position",
        "who holds leverage over what, and where on a defined ladder the current step sits",
        "leverage decides who can escalate and who must absorb the cost",
        "decide from dated leverage records against a stated threshold; UNKNOWN when the threshold is undefined"
      ),
      variable(
        "actual actions",
        "deployments, transfers, seizures, restrictions and signed agreements with dates",
        "an action is observable and testable in a way a statement is not",
        "decide from a dated, attributable action record; UNKNOWN when reporting is unattributed"
      ),
      variable(
        "domestic political constraint and information environment",
        "the domestic costs a decision maker faces and the information its public receives",
        "constraints decide which options a leadership can actually choose",
        "decide from dated official, electoral and media records; UNKNOWN when the audience is inferred rather than observed"
      )
    ),
    List.of(
      mechanism(
        "sanctions or export controls",
        "trade rerouting and price effects",
        "restricted flows are rerouted through third parties at a higher cost, raising prices and shifting margins",
        "dated enacted measures plus dated trade-flow and price changes with the same timing"
      ),
      mechanism(
        "alliance commitment",
        "deterrence credibility",
        "credibility rests on demonstrated willingness to act, so an untested commitment is discounted by the other side",
        "dated treaty text plus dated behaviour under the commitment"
      ),
      mechanism(
        "export controls",
        "capability gap with lead time",
        "restricting inputs delays capability rather than removing it, because substitution and stockpiles take time",
        "dated control measures plus dated capacity, import or substitution records"
      ),
      mechanism(
        "resource dependence",
        "leverage",
        "a party that cannot easily substitute a supply it needs can be pressured by the supplier",
        "dated supply-concentration data plus a dated use of that leverage"
      ),
      mechanism(
        "information activity",
        "domestic constraint",
        "shaping what a public believes changes the cost a leadership pays for a decision",
        "dated information activity plus a dated change in official or public position"
      )
    ),
    List.of(
      metric(
        "official statements with named signatories",
        "official government and organization publications with dates",
        "per statement",
        "an attributed statement commits the named party to the action the hypothesis requires",
        "the statement is unattributed, retracted, or contradicted by the same party"
      ),
      metric(
        "treaty, alliance and multilateral records",
        "treaty registries and multilateral body documents",
        "per instrument",
        "a dated instrument or record shows the commitment or measure the hypothesis requires",
        "no instrument covers the claim, or the commitment excludes the relevant action"
      ),
      metric(
        "customs, trade and supply statistics",
        "national customs authorities and international trade databases",
        "per reporting period",
        "dated trade flows move as the mechanism requires under a stable classification",
        "flows are unchanged, or the classification changes and the comparison breaks"
      ),
      metric(
        "attributable open-source corroboration with timestamps",
        "geolocated imagery, movement tracking and dated incident records",
        "per incident",
        "independent dated observations corroborate the claimed action or control",
        "observations are absent, single-sourced, or inconsistent on the location and date"
      )
    ),
    List.of(
      "an unattributed official claim is not evidence",
      "single-source conflict reporting is not corroboration",
      "every side in a dispute has an incentive to shape the account",
      "assuming the other side shares your reasoning is not analysis of its intent",
      "escalation language without a defined threshold cannot be checked",
      "a statement is not a capability and a statement is not an action",
      "a territorial claim without control evidence is a claim"
    ),
    "Separate what was said, what a party is able to do and what it actually did, and require an attributable, dated record for each. Treat dependencies and restrictions as measurable exposures rather than as intentions."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.GEOPOLITICS;
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
