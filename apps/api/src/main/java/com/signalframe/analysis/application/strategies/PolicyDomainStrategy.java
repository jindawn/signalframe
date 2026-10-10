package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * POLICY dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.6). Wave 2A scope; recommendations only.
 */
@Component
public final class PolicyDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.POLICY,
    SPECIFICITY,
    List.of(
      variable(
        "instrument type",
        "whether the instrument is a statute, regulation, guidance or an enforcement action",
        "the instrument type decides whether the obligation is binding and who must act",
        "decide from the published instrument itself; UNKNOWN when only a summary or a leak is available"
      ),
      variable(
        "regulator and enforcement body",
        "which body holds the mandate and the jurisdiction it can act in",
        "the enforcing body decides whether a rule can become an action",
        "decide from the enabling text and the body's own records; UNKNOWN when jurisdiction is ambiguous"
      ),
      variable(
        "implementation intensity",
        "staffing, budget and inspection activity devoted to the instrument",
        "an unstaffed obligation changes nothing even when it is law",
        "decide from dated appropriations, staffing and inspection records; UNKNOWN when no activity data exists"
      ),
      variable(
        "timeline and effective dates",
        "when obligations start, whether they are staged, and what happens at sunset",
        "dates decide whether an effect is present, pending or expired",
        "decide from published effective dates and transition clauses; UNKNOWN when only an intended date is announced"
      ),
      variable(
        "thresholds and exemptions",
        "size, sector or activity thresholds that decide who is in scope",
        "threshold design decides who is regulated and who is excluded",
        "decide from the operative text; UNKNOWN when thresholds are left to later rulemaking"
      ),
      variable(
        "compliance cost and cost bearer",
        "the cost of compliance and which party is legally required to bear it",
        "who pays decides who is incentivized to change behaviour",
        "decide from published impact assessments or filed compliance records; UNKNOWN when costs are not published"
      ),
      variable(
        "beneficiaries and subsidy size or duration",
        "who receives support, how much and for how long",
        "subsidy design decides whether demand changes durably or only for the subsidy window",
        "decide from enacted appropriations and payment records; UNKNOWN when only eligibility is announced"
      ),
      variable(
        "judicial exposure and preemption",
        "pending litigation and whether a higher authority can override the instrument",
        "a rule under challenge may never take effect, and stays uncertain while it is litigated",
        "decide from court dockets and the enabling hierarchy; UNKNOWN when no filing or ruling exists"
      )
    ),
    List.of(
      mechanism(
        "statute",
        "rule compliance and behaviour",
        "a statute changes behaviour only after rulemaking writes the operative rules and the obligations take effect",
        "enacted text with dates, the implementing rule text, and a dated compliance or behaviour record"
      ),
      mechanism(
        "enforcement action",
        "deterrence",
        "observed enforcement raises the expected cost of non-compliance for others in scope",
        "a dated enforcement action or penalty plus a dated change in inspection or filing activity"
      ),
      mechanism(
        "subsidy",
        "adoption",
        "a transfer lowers the net cost of the supported activity while it lasts",
        "enacted appropriation, dated payment records, and a dated take-up measure"
      ),
      mechanism(
        "regulatory uncertainty",
        "deferred investment",
        "when the eventual rule is unknowable, committing irreversible capital becomes unattractive",
        "a dated statement of uncertainty plus a dated investment deferral, cancellation or delay record"
      ),
      mechanism(
        "threshold design",
        "gaming and avoidance",
        "actors near a threshold can restructure to fall outside the obligation",
        "the operative threshold plus a dated change in the affected population or filings"
      )
    ),
    List.of(
      metric(
        "official gazette or register text",
        "the official legislative or regulatory register, with publication and effective dates",
        "per instrument",
        "the operative text with an effective date exists and says what the hypothesis requires",
        "only a draft, a summary or an announced intention exists; no operative text with a date"
      ),
      metric(
        "rulemaking dockets and comment periods",
        "agency dockets, notices and comment records",
        "per docket stage",
        "the docket advances toward a final rule with a stated date",
        "the docket stalls, is withdrawn, or the final rule differs from the proposal"
      ),
      metric(
        "enforcement actions, penalties and inspection records",
        "agency enforcement releases and court dockets",
        "per action",
        "dated enforcement actions appear against the named obligated parties",
        "no enforcement action appears within the stated window"
      ),
      metric(
        "appropriations, staffing and payment records",
        "budget documents, staffing reports and subsidy payment records",
        "per budget cycle",
        "funding and staffing are appropriated and actually spent",
        "funding is announced but not appropriated, or appropriated but unspent"
      )
    ),
    List.of(
      "announced is not enacted, enacted is not implemented, and implemented is not enforced",
      "a leaked draft is not a policy",
      "federal, state and local jurisdiction can each govern the same activity",
      "a sunset or clawback clause changes whether the instrument still applies",
      "guidance is not binding law even when it reads like a rule",
      "an enforcement action names a party; it does not establish general compliance"
    ),
    "Track the instrument through its lifecycle and state where it currently sits: proposal, enacted text, effective date, implementation capacity or observed enforcement. Never let an announcement stand in for an obligation that has taken effect."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.POLICY;
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
