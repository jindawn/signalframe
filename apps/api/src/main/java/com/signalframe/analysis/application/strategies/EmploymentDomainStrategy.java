package com.signalframe.analysis.application.strategies;

import static com.signalframe.analysis.application.strategies.DomainTemplates.mechanism;
import static com.signalframe.analysis.application.strategies.DomainTemplates.metric;
import static com.signalframe.analysis.application.strategies.DomainTemplates.variable;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EMPLOYMENT dictionary (DOMAIN_STRATEGY_CONTRACT.md §7.12). Recommendations only: a posting is
 * never treated as a hire and an announced layoff is never treated as an executed one.
 */
@Component
public final class EmploymentDomainStrategy implements DomainAnalysisStrategy {

  public static final int SPECIFICITY = 2;

  private static final DomainStrategySpec SPEC = new DomainStrategySpec(
    DomainType.EMPLOYMENT,
    SPECIFICITY,
    List.of(
      variable(
        "labour demand (openings, hiring and quits)",
        "posted vacancies, actual hires and voluntary separations",
        "openings show intent, hires show demand that was met, and quits show alternatives elsewhere",
        "decide from dated comparable series read together; UNKNOWN when only postings are available"
      ),
      variable(
        "labour supply and participation",
        "who is available for work and who has entered or left the labour force",
        "participation decides whether a hiring change reflects demand or available supply",
        "decide from dated participation records for the matching population; UNKNOWN when migration or re-entry is unmeasured"
      ),
      variable(
        "unemployment and underemployment breadth",
        "unemployment plus the people working fewer hours or below their capacity than they want",
        "narrow unemployment can hide unused capacity in the workforce",
        "decide from dated measures of both concepts; UNKNOWN when only the headline rate is reported"
      ),
      variable(
        "wage growth by segment",
        "pay growth measured separately across occupations, industries and pay levels",
        "an average wage can rise while most workers see no gain, or the reverse",
        "decide from dated segment-level wage series; UNKNOWN when only an aggregate is published"
      ),
      variable(
        "entry barriers, licensing and qualification requirements",
        "formal requirements that decide who may take a job",
        "barriers decide how quickly supply can respond to higher pay",
        "decide from the applicable requirements and dated entry flows; UNKNOWN when requirements vary by jurisdiction and only one is described"
      ),
      variable(
        "automation and task displacement",
        "which tasks have been automated and what happened to the workers who performed them",
        "automation displaces tasks rather than whole occupations, so the effect depends on the task boundary",
        "decide from dated deployment records plus dated employment changes in the affected tasks; UNKNOWN when the automation is announced but not deployed"
      ),
      variable(
        "hours worked and work arrangements",
        "hours per worker and the share of work that is part-time, temporary or contracted",
        "classification decides whether employment growth is durable and how much income it supports",
        "decide from dated hours and arrangement data; UNKNOWN when contractor work is not counted"
      ),
      variable(
        "geography, sector mix and skills mismatch",
        "where the jobs and the workers are, in which sectors, and how well the skills match",
        "a national total can coexist with a local shortage and a local surplus",
        "decide from dated sub-national and sector data; UNKNOWN when only national totals are published"
      )
    ),
    List.of(
      mechanism(
        "labour demand",
        "hiring and wages",
        "sustained demand for workers first raises hiring, and only then raises pay for the occupations that are short",
        "a dated demand series plus dated hiring and segment-level wage data"
      ),
      mechanism(
        "policy tightening",
        "hiring slowdown and unemployment with a lag",
        "tighter financial conditions reduce planned activity, so hiring slows before separations rise",
        "a dated policy change plus dated hiring and unemployment series covering the lag"
      ),
      mechanism(
        "automation",
        "task displacement and reskilling",
        "automating a task removes demand for that task and creates demand for the tasks around it",
        "dated deployment records plus dated employment and vacancy data for the affected tasks"
      ),
      mechanism(
        "migration",
        "labour supply",
        "arriving workers add to supply in the places and occupations they can enter",
        "dated migration records plus dated employment or wage data for the matching segment"
      ),
      mechanism(
        "benefit expiry",
        "labour-force re-entry",
        "when support ends, some recipients re-enter the labour force, raising measured participation",
        "a dated benefit change plus dated participation data"
      )
    ),
    List.of(
      metric(
        "establishment and household surveys with revision history",
        "statistical agencies' employment releases with revisions",
        "per release",
        "both surveys agree on the direction the hypothesis requires",
        "the surveys diverge, or the first print is revised away"
      ),
      metric(
        "job-openings series",
        "official vacancy and turnover releases",
        "per release",
        "openings move as the hypothesis requires against a comparable period",
        "openings series is revised or redefined, or moves against the reading"
      ),
      metric(
        "unemployment-insurance claims and layoff filings",
        "official claims records and required layoff notices",
        "per reporting period",
        "dated claims or filings show separations at the scale the hypothesis requires",
        "claims are unchanged, or announced separations do not appear in filings"
      ),
      metric(
        "job-posting panels and union contracts",
        "posting aggregators with a disclosed method and published bargaining agreements",
        "per reporting period",
        "dated postings or negotiated terms confirm the change in demand or pay",
        "postings duplicate, are withdrawn, or the negotiated terms do not match the claim"
      )
    ),
    List.of(
      "establishment and household surveys measure different things and can diverge",
      "seasonal adjustment is not the raw series",
      "contractor and part-time classification changes the headline",
      "an announced layoff is not an executed layoff",
      "a posting is not a hire",
      "one month of movement is noise, not a trend"
    ),
    "Read demand, supply, pay and classification together, and prefer matched series that cover the same population and period. Treat announced hiring and announced layoffs as intentions until executed records appear."
  );

  @Override
  public boolean supports(DomainType domain) {
    return domain == DomainType.EMPLOYMENT;
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
