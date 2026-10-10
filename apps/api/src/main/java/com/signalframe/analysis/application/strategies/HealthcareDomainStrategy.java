package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * HEALTHCARE dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.11). Recommendations only: a topline
 * announcement is never treated as a reviewed result.
 */
@Component
public final class HealthcareDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.HEALTHCARE,
    SPECIFICITY,
    List.of(
      variable(
        "efficacy on a defined endpoint",
        "the measured effect on a pre-specified endpoint, in a stated population",
        "an effect is only interpretable against the endpoint and population it was measured in",
        "decide from a dated protocol and its reported result; UNKNOWN when the endpoint was changed after the results"
      ),
      variable(
        "safety and adverse-event profile",
        "the harms observed, their frequency and their severity",
        "benefit is only meaningful against the harm it carries",
        "decide from dated reported safety data with a denominator; UNKNOWN when only a qualitative safety statement exists"
      ),
      variable(
        "clinical stage and trial design",
        "how far the programme has progressed and how the trial was controlled",
        "design decides how much of the result can be attributed to the intervention",
        "decide from a dated registry entry with protocol version; UNKNOWN when no registration or protocol is available"
      ),
      variable(
        "patient population and eligibility",
        "who the intervention is studied in and who would be eligible for it",
        "a result in a narrow population does not transfer to a broader one",
        "decide from dated eligibility criteria and enrolment records; UNKNOWN when the population is described only in a summary"
      ),
      variable(
        "approval status and label",
        "whether a regulator has approved it and what the approved label permits",
        "the label defines what may be promoted and prescribed",
        "decide from the regulator's own approval documents; UNKNOWN when only a company announcement exists"
      ),
      variable(
        "reimbursement, coding and coverage",
        "whether payers cover it, under what code and with what conditions",
        "without coverage the product has no paying pathway regardless of approval",
        "decide from dated payer coverage policies; UNKNOWN when coverage is described only as pending"
      ),
      variable(
        "net price and rebates",
        "the price actually received after discounts and rebates",
        "net price, not list price, decides the revenue the product generates",
        "decide from dated disclosures or payer records; UNKNOWN when only list price is published"
      ),
      variable(
        "supply and manufacturing capacity",
        "production capacity, input sourcing and any single-source dependency",
        "supply decides whether approved demand can actually be served",
        "decide from dated capacity or sourcing disclosures; UNKNOWN when the process depends on an unreported input"
      ),
      variable(
        "provider and payer adoption",
        "prescribing, administration and coverage uptake in practice",
        "adoption is what converts an approved product into use",
        "decide from dated prescribing or claims data; UNKNOWN when only launch intentions exist"
      )
    ),
    List.of(
      mechanism(
        "approval",
        "reimbursement, adoption and volume",
        "approval creates the legal pathway, coverage creates the paying pathway, and only then does volume follow",
        "a dated approval document, a dated coverage policy, and a dated utilisation record"
      ),
      mechanism(
        "endpoint result",
        "label and prescribing",
        "the measured endpoint result is what the label reflects and what clinicians act on",
        "the dated protocol, the reported result, and the approved label text"
      ),
      mechanism(
        "pricing pressure",
        "rebates and net price",
        "a payer facing budget pressure extracts rebates, so list price and net price move apart",
        "a dated payer policy change plus dated net-price or rebate disclosures"
      ),
      mechanism(
        "supply constraint",
        "substitution",
        "an unavailable product is replaced by an available alternative in practice",
        "a dated supply interruption plus a dated change in the use of alternatives"
      ),
      mechanism(
        "guideline change",
        "practice change",
        "a clinical guideline changes what is recommended, which shifts what is prescribed",
        "a dated guideline publication plus a dated prescribing change"
      )
    ),
    List.of(
      metric(
        "regulator approval documents and labels",
        "regulator publication registers with dates",
        "per decision",
        "the regulator's own document states the approval and label the hypothesis requires",
        "only a company announcement exists, or the label restricts the claimed use"
      ),
      metric(
        "trial registries with protocol versions",
        "public trial registries showing protocol history",
        "per protocol version",
        "the registered protocol matches the reported result and the endpoint did not change",
        "the endpoint, population or analysis was changed after results were known"
      ),
      metric(
        "peer-reviewed publications and surveillance data",
        "indexed journals and official safety surveillance systems",
        "per publication and per reporting period",
        "independent peer review and surveillance data support the result",
        "the result appears only in an unreviewed preprint or press release"
      ),
      metric(
        "payer coverage policies and tender awards",
        "payer policy publications and public procurement awards",
        "per policy cycle",
        "a dated coverage policy or tender award covers the product on the stated terms",
        "coverage is refused, restricted, or the tender is not awarded"
      )
    ),
    List.of(
      "a topline announcement is not a peer-reviewed result",
      "a surrogate endpoint is not a clinical outcome",
      "selecting a subgroup after the results is not a finding",
      "a preprint has not been reviewed",
      "a changed primary endpoint breaks the pre-registration",
      "a single-centre result is not a general result",
      "off-label promotional wording is not an approved indication"
    ),
    "Separate the regulatory pathway, the reimbursement pathway and commercial adoption, and require the regulator's or payer's own document for each step. Distinguish pre-specified results from post-hoc analysis, and treat safety data as part of the result rather than as a footnote."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.HEALTHCARE;
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
