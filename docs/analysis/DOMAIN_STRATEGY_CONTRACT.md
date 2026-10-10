# SignalFrame Domain Strategy Contract

Status: Frozen for v0.1 (boundary) with the v0.1.1 interface addition marked as a proposal
Related: [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) · [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) · [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) · [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md)

## 1. The boundary in one sentence

The epistemic protocol is domain-invariant. A domain strategy recommends **what to look for**
(relevant variables, plausible mechanisms, verification metrics) and **what mistakes this domain
invites**; it never changes what FACT, INFERENCE, HYPOTHESIS, PREDICTION or UNKNOWN mean, never
assigns confidence, and never states a conclusion.

## 2. What a strategy may do

| allowed | detail |
| --- | --- |
| recommend variables | names, what the variable tracks, why it matters, how direction should be decided from evidence |
| recommend mechanisms | candidate causal patterns with the evidence each would require, always starting at `SPECULATIVE` |
| recommend verification metrics | what to check, where, how often, and what result would support vs contradict |
| declare domain hazards | recurring inference traps in the domain, phrased as warnings |
| declare specificity | how specialized the strategy is, for deterministic selection |
| render guidance text | a bounded, deterministic rendering of the above for the synthesis prompt |

## 3. What a strategy may never do

- Change, rename, add or remove an epistemic type (EP-12).
- Assign, adjust or override confidence (CF-01) or hypothesis/prediction status.
- Emit claims, facts, evidence or predictions directly. Strategies produce *recommendations*; only the
  analysis stages produce artifacts.
- Assert a `supportLevel` above `SPECULATIVE` for a mechanism template.
- Fill in a direction, previous state, or magnitude. Direction is an evidence-derived output of STG-04
  and must start as `UNKNOWN` when derived from a template.
- Introduce publisher reputation, media-credit rankings, or "trusted source" lists. Source assessment
  is a structural description (STG-02), not a leaderboard.
- Contain provider or model names, API/model identifiers, or routing hints.
- Contain invented prices, financial figures, or quantitative forecasts.
- Perform I/O, read the clock, call a model, or touch the database. Strategies are pure.
- Change rubric weights or level definitions, or claim a domain-specific scoring profile.

## 4. Interface

Current contract (`analysis/domain/DomainAnalysisStrategy.java`):

```java
public interface DomainAnalysisStrategy {
  boolean supports(DomainType domain);
  String guidance();
}
```

v0.1.1 addition (proposal; the interface change and both implementations must land atomically in
TASK-05 — see [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md) §4):

```java
public interface DomainAnalysisStrategy {
  boolean supports(DomainType domain);

  /** 0 = fallback, 1 = generic domain, 2+ = specialized. Higher wins. */
  default int specificity() { return 1; }

  /** Structured recommendations. */
  DomainStrategySpec spec();

  /** Legacy text rendering; must equal render(spec()) once spec() exists. */
  default String guidance() { return DomainStrategySpecRenderer.render(spec()); }
}

public record DomainStrategySpec(
    DomainType domain,
    int specificity,
    List<VariableTemplate> variables,
    List<MechanismTemplate> mechanisms,
    List<VerificationMetricTemplate> metrics,
    List<String> epistemicHazards,
    String guidanceText) {}

public record VariableTemplate(
    String name,            // "unit cost per unit of capability"
    String tracks,          // what changes and how it is observed
    String whyItMatters,    // consequence chain, not a restatement
    String directionRule)   // how to decide UP/DOWN/UNCHANGED from evidence; never a direction

public record MechanismTemplate(
    String fromConcept,
    String toConcept,
    String explanationPattern,
    String requiredEvidence,
    String startingSupportLevel)  // MUST be "SPECULATIVE"

public record VerificationMetricTemplate(
    String whatToCheck,
    String whereToCheck,
    String frequency,
    String supportingResult,
    String contradictingResult)
```

Rules for the interface:

| id | rule |
| --- | --- |
| DS-01 | `supports(DomainType)` is pure and depends on the domain only. |
| DS-02 | `specificity()`: `0` is reserved for the fallback strategy, which must `supports()` every domain. Higher specificity wins. |
| DS-03 | The fallback (`DefaultDomainStrategy`) stays `guidance`-compatible and must remain the only all-domain strategy. |
| DS-04 | Two strategies claiming the same domain at the same highest specificity is a **startup configuration error**, not a runtime coin flip. Selection must be deterministic; class-name ordering is not an acceptable tie-break. |
| DS-05 | No strategy matching a domain is a job failure. It must never silently fall through to empty guidance. |
| DS-06 | `spec()` values are compile-time constants. No file I/O, no database, no clock, no randomness. |
| DS-07 | `guidance()` is a pure rendering of `spec()` with a bounded length (≤ 2000 characters). |
| DS-08 | `startingSupportLevel` must be `SPECULATIVE`; the analysis stage may raise it only with fact refs. |
| DS-09 | Template lists are recommendations, not checklists to complete. An analysis that finds nothing for a recommended variable must say so in `unknowns`, not invent a value. |
| DS-10 | Domain hazards are warnings appended to guidance. They never justify a confidence adjustment. |
| DS-11 | Recommendations must be defeasible: no template may be phrased so that it presupposes the conclusion. |
| DS-12 | The twelve domains below must each have a dictionary or be explicitly mapped to a generic strategy with a documented gap. |

## 5. Selection and wiring

Today `AnalysisPipeline` selects a strategy with
`filter(supports).sorted(comparing(s -> s.getClass().getSimpleName().startsWith("Default"))).findFirst()`
— a string check on a class name. The protocol requires replacing this with explicit specificity
ordering, which is a `AnalysisPipeline` edit owned by TASK-03, consuming `specificity()` supplied by
TASK-05. Neither task may do both edits.

Selection algorithm (deterministic):

1. collect all strategies where `supports(domain)` is true;
2. take the maximum `specificity()`;
3. if more than one strategy has that maximum → fail startup configuration validation;
4. otherwise use it; if none matched → job failure (DS-05).

How recommendations reach the protocol stages:

| recommendation | consumed by |
| --- | --- |
| `variables[]` | STG-04 (ExtractVariables), as a checklist of what to look for |
| `mechanisms[]` | STG-05 (AnalyzeMechanism), as candidate patterns needing evidence |
| `metrics[]` | STG-15 (GenerateVerificationPlan) and STG-13 (signals) |
| `epistemicHazards[]` | appended to guidance for the reasoning stages; advisory only |
| `guidanceText` | prompt guidance string, exactly as today |

## 6. Configuration form

Wave 2A keeps recommendations as Java constants inside
`apps/api/src/main/java/com/signalframe/analysis/application/strategies` (TASK-05's owned path), so no
new resource paths are needed. If and when dictionaries must be edited without a recompile, the
resource form below is the approved shape; loading it requires an integrator-granted path
(`apps/api/src/main/resources/analysis/domains/*.yaml`) because `resources` is not in TASK-05's owned
paths.

```yaml
# apps/api/src/main/resources/analysis/domains/FINANCE.yaml  (future, integrator-owned path)
domain: FINANCE
specificity: 1
variables:
  - name: policy rate expectations
    tracks: market-implied path for the policy rate
    whyItMatters: changes the discount rate applied to every cash flow in the story
    directionRule: UP if implied path rose between the two dates being compared, else DOWN/UNCHANGED; UNKNOWN if no comparable dates
mechanisms:
  - fromConcept: policy rate expectations
    toConcept: valuation multiples
    explanationPattern: higher discount rate compresses the present value of distant cash flows
    requiredEvidence: a dated rate-expectation series and a dated multiple for the same asset class
    startingSupportLevel: SPECULATIVE
metrics:
  - whatToCheck: the next central-bank statement and the following rate decision
    whereToCheck: official central-bank release and the bond futures curve
    frequency: per meeting
    supportingResult: guidance shifts toward the direction the hypothesis requires
    contradictingResult: guidance or pricing moves the other way
epistemicHazards:
  - a price move after the fact is not confirmation of a causal story
  - consensus estimates are not outcomes
```

## 7. Domain dictionaries (recommendations, not logic)

Each block is a specification for a `DomainStrategySpec`. Variable names are illustrative; strategies
may refine wording. No block may add a scoring rule.

### 7.1 AI

- **Variables**: deployed capability vs benchmark capability; cost per unit of capability; adoption
  and deployment penetration; data access and licensing rights; compute supply and lead time; model
  release cadence of the frontier; reliability/latency in production; regulatory and safety posture;
  talent and research-lab movement.
- **Mechanisms**: capability gain → task substitution → labor and process change; cost decline →
  adoption → demand for complements (compute, power, data); open-weight release → price competition →
  margin compression; regulation → compliance cost → concentration; compute constraint → capability
  inequality.
- **Verification metrics**: independent evaluation releases; model cards and technical reports;
  API/pricing page changes with dates; dated deployment announcements from the deploying organization;
  procurement and data-center/power contracts; open-weight release cadence; safety-evaluation filings.
- **Hazards**: benchmark scores are not deployed capability; unreproducible demos; unfalsifiable
  capability claims; vendor-reported usage numbers; silent model updates invalidating comparisons;
  "AGI" framing that cannot be tested.

### 7.2 TECH

- **Variables**: measured unit cost curve; performance/reliability against stated SLOs; install base
  and attach rate; standards adoption; supply-chain lead time; developer ecosystem breadth; switching
  costs; version/compatibility fragmentation.
- **Mechanisms**: cost curve → adoption; standard adoption → interoperability → commoditization;
  reliability → trust → volume; supply constraint → price and lead time; ecosystem breadth → lock-in.
- **Verification metrics**: official specifications and release notes; standards-body documents;
  changelogs and firmware advisories; shipment/install data; public issue trackers; procurement
  notices; independent teardown or benchmark reports.
- **Hazards**: spec sheets vs measured performance; roadmap press releases as shipped capability;
  cross-version benchmark comparisons with changed test setups; "industry-leading" without a metric
  definition.

### 7.3 BUSINESS

- **Variables**: revenue mix by segment; gross margin and unit economics; customer acquisition cost
  and churn; capacity utilization; pricing actions; customer and supplier concentration; headcount and
  hiring mix; capital structure and covenants; competitive share.
- **Mechanisms**: price action → volume and margin; churn → lifetime value; capacity utilization →
  fixed-cost absorption; concentration → bargaining power; incentive design → channel behavior.
- **Verification metrics**: audited filings and annual reports; earnings-call transcripts; official
  price lists; job postings; trademark/patent filings; customer case studies with named parties;
  regulatory registries; supplier disclosures.
- **Hazards**: non-GAAP self-reported metrics; "record quarter" without a comparable base; channel
  checks presented as measured data; survivor bias in case studies; definitional changes in reported
  KPIs.

### 7.4 FINANCE

- **Variables**: valuation multiples and prices; realized vs consensus earnings; credit spreads and
  ratings; funding/liquidity conditions; positioning and flows; cost of capital; guidance revisions;
  covenant headroom.
- **Mechanisms**: rates → discount rate → valuations; guidance → estimate revisions → price; credit
  conditions → refinancing → capex; positioning → squeeze dynamics; index inclusion → flow effects.
- **Verification metrics**: exchange filings and prospectuses; central-bank and statistical releases;
  official index and settlement prices; rating-agency actions; auction and issuance results; short
  interest and flow reports.
- **Hazards**: a price move is not confirmation of a post-hoc causal narrative; single-session noise;
  consensus estimates are not outcomes; backtest overfitting; survivorship and delisting bias;
  illiquid marks presented as market prices.

### 7.5 MACRO

- **Variables**: activity (GDP/PMI); inflation (headline, core, wages); policy rate and expectations;
  employment and participation; credit growth; exchange rate; fiscal stance; inventories; capacity
  utilization.
- **Mechanisms**: policy rate → credit conditions → demand → inflation, with lags; wages → services
  inflation; currency → import prices; fiscal impulse → demand; inventory cycle → production swings.
- **Verification metrics**: national statistics releases with revision history; central-bank
  statements and minutes; market-implied rate path; survey series with published methodology; customs
  and trade data; nowcast comparisons.
- **Hazards**: first prints vs later revisions; seasonal-adjustment confusion; level vs change
  correlation; "leading indicator" claims without a published track record; politically influenced
  statistical releases; single-country extrapolation.

### 7.6 POLICY

- **Variables**: instrument type (statute, regulation, guidance, enforcement action); implementation
  timeline; agency capacity and budget; thresholds and exemptions; judicial exposure; preemption;
  compliance cost; subsidy size and duration.
- **Mechanisms**: statute → rulemaking → compliance → behavior change, with delay; enforcement →
  deterrence; subsidy → adoption; regulatory uncertainty → deferred investment; threshold design →
  gaming.
- **Verification metrics**: official gazette/register text; rulemaking dockets and comment periods;
  effective dates; court filings and dockets; appropriations and budget documents; procurement
  records; agency staffing.
- **Hazards**: announced ≠ enacted ≠ implemented ≠ enforced; leaked drafts treated as policy;
  jurisdiction confusion (federal/state/local, EU/national); sunset and clawback clauses ignored;
  guidance treated as binding law.

### 7.7 GEOPOLITICS

- **Variables**: alliance alignment; capability balance; trade and energy dependence; sanctions
  exposure; domestic political constraint; escalation-ladder position; information environment;
  territorial control claims.
- **Mechanisms**: sanctions → trade rerouting → price and margin effects; alliance commitment →
  deterrence credibility; export controls → capability gaps with lead time; resource dependence →
  leverage; information operations → domestic constraint.
- **Verification metrics**: official statements with named signatories; treaty and alliance documents;
  UN and multilateral records; customs and trade statistics; satellite/OSINT corroboration with
  timestamps; mobilization and budget appropriation data; flight/vessel tracking.
- **Hazards**: unattributed official claims; single-source conflict reporting; propaganda incentives
  on all sides; mirror-imaging adversary intent; "escalation" language with no defined threshold;
  territorial claims without control evidence.

### 7.8 REAL_ESTATE

- **Variables**: cap rates and yields; rents; vacancy and absorption; construction pipeline and starts;
  financing cost; transaction volume; demographics/migration; zoning and land supply; distressed
  inventory.
- **Mechanisms**: rates → affordability → transaction volume → prices; pipeline → vacancy → rents;
  zoning → supply elasticity; credit tightening → developer distress; migration → local demand.
- **Verification metrics**: land/title registry records; official price indices with stated method;
  planning permits and applications; REIT filings; appraisal-district data; rental listing panels;
  auction and foreclosure records.
- **Hazards**: asking prices ≠ transaction prices; index methodology differences (repeat-sales vs
  median); thin-market comparables; appraisal lag; developer-reported absorption; land-bank
  speculation read as demand.

### 7.9 ENERGY

- **Variables**: supply/demand balance; inventory levels; capacity additions and retirements; marginal
  cost; policy incentives and taxes; weather-driven demand; grid constraints and interconnection
  queue; commodity spreads.
- **Mechanisms**: inventory → spot price → forward curve; retirement → tightness → price spikes;
  incentive → capex → supply with lead time; congestion → locational price divergence; fuel switching
  → relative demand.
- **Verification metrics**: national statistics agencies and IEA/EIA series; grid-operator dispatch and
  outage data; storage/inventory reports; interconnection queues; capacity auction results; official
  price assessments with methodology.
- **Hazards**: single-session price prints; weather attribution without a baseline; reserve estimates
  treated as production; quota announcements ≠ compliance; policy targets ≠ built capacity; unit
  confusion (energy vs capacity).

### 7.10 CONSUMER

- **Variables**: real disposable income; savings rate; sentiment/expectations; category price
  elasticity; premium/value mix; channel shift; credit availability and delinquency; penetration and
  purchase frequency.
- **Mechanisms**: income → spending; price increase → elasticity → volume/mix; promotion → trial →
  repeat; credit → big-ticket demand; channel shift → margin structure.
- **Verification metrics**: national accounts and retail sales; company-reported comparable sales with
  stated definitions; card-panel data with disclosed methodology; survey series with sample design;
  official price lists; bankruptcy and import data.
- **Hazards**: consumer-confidence indices as predictors without a track record; comps definition
  changes; panel attrition and representativeness; stated intentions ≠ behavior; seasonality and
  promotional calendar effects.

### 7.11 HEALTHCARE

- **Variables**: approval status; reimbursement and coding decisions; clinical endpoint results; trial
  phase and enrollment; net pricing and rebates; supply and API sourcing; incidence/prevalence;
  provider and payer adoption.
- **Mechanisms**: approval → reimbursement → adoption → volume; endpoint result → label → prescribing;
  pricing pressure → rebates → net price; supply constraint → substitution; guideline change →
  practice change.
- **Verification metrics**: regulator approval documents and labels; trial registries with protocol
  versions; peer-reviewed publications; payer coverage policies; company filings; surveillance data;
  tender and procurement awards.
- **Hazards**: topline press releases vs peer-reviewed results; surrogate endpoints vs outcomes;
  subgroup mining; preprints without review; registry outcome switching; off-label promotion claims;
  single-center results generalized.

### 7.12 EMPLOYMENT

- **Variables**: payroll levels; participation rate; unemployment and underemployment breadth; wage
  growth by segment; openings and quits; announced hiring/freezes; hours worked; sector mix; skills
  mismatch.
- **Mechanisms**: demand → hiring → wages; policy tightening → hiring slowdown → unemployment with a
  lag; automation → task displacement → reskilling; migration → labor supply; benefit expiry → labor
  force re-entry.
- **Verification metrics**: establishment and household surveys with revision history; job-openings
  series; unemployment-insurance claims; WARN/layoff filings; job-posting panels; union contracts;
  census/immigration data.
- **Hazards**: establishment vs household survey divergence; seasonal adjustment; contractor and
  part-time classification; announced layoffs ≠ executed layoffs; postings ≠ hires; single-month noise
  treated as trend.

### 7.13 Other domains

`OTHER` is served by the fallback strategy. A domain outside the list above must be mapped to a
generic strategy with its gap documented in the strategy's `epistemicHazards` or the analysis
`unknowns`. Adding a new `DomainType` value is an OpenAPI change and therefore integrator-owned.

## 8. Acceptance checklist for TASK-05

1. `DefaultDomainStrategy` remains the only all-domain strategy with `specificity() == 0`.
2. Every other strategy declares a specificity ≥ 1 and non-overlapping domain coverage at the top
   specificity (DS-04).
3. No strategy contains a provider/model name, an invented figure, or a confidence value.
4. Mechanism templates all declare `SPECULATIVE` (DS-08); variable templates all provide a
   `directionRule` and no direction.
5. Dictionaries exist for at least BUSINESS, FINANCE and POLICY (the Wave 2A scope), with the remaining
   domains either implemented or explicitly documented as generic-fallback.
6. Tests cover matching, fallback, duplicate-specificity failure, and a representative guidance
   rendering.
7. **No pipeline file is edited by TASK-05.** The comparator change is TASK-03's.
