# SignalFrame Analysis Protocol v0.1

Status: Frozen for v0.1 (normative contract)
Scope: the analysis contract only. No prompt text, no pipeline implementation, no retrieval, no agent framework.
Applies to: TASK-03 (pipeline), TASK-05 (domain strategies), TASK-06 (hypothesis engine), TASK-07 (evidence/prediction)
Related: [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) · [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) · [DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) · [SCHEMA_IMPACT_ASSESSMENT.md](SCHEMA_IMPACT_ASSESSMENT.md) · [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md)

The protocol turns input information into a fixed chain of epistemic artifacts:

```
Source → Source Assessment → Facts → Key Variables → Mechanism → Stakeholders
→ First-order Effects → Second-order Effects → Hypotheses → Alternative Explanations
→ Counter Arguments → Falsification Conditions → Corroborating Signals → Predictions
→ Verification Plan → Confidence Assessment
```

The rest of this document is written so that TASK-03, TASK-05, TASK-06 and TASK-07 can each be
implemented independently against it without renegotiating semantics.

## 1. Design principles

1. **Provenance first.** Nothing is produced before its inputs exist. A downstream stage may not
   compensate for a missing upstream stage by inventing content.
2. **Epistemic type is explicit and immutable** within a snapshot ([EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) EP-10).
3. **UNKNOWN is an output.** Every stage that cannot produce its artifact must emit an explicit
   UNKNOWN item with a reason. Silence is not an option.
4. **Snapshot semantics.** An analysis is an immutable snapshot. Corrections arrive as a new snapshot
   or as append-only research events, never as an in-place rewrite of stored payloads.
5. **Call-topology agnostic.** The protocol constrains *artifacts and invariants*, not how many model
   calls produce them. One synthesis call and fourteen staged calls are both conformant, as long as
   the artifacts and the validation gates hold. TASK-03 therefore does not have to rewrite the
   provider surface to become conformant.
6. **Deterministic where determinism is claimed.** Rubric scoring, source assessment, provenance
   checks and ID/ref integrity are deterministic. Model-backed content is not, which is why it is
   versioned and audited.
7. **No domain can move the semantics.** Domain strategies recommend variables, mechanisms and
   verification metrics only ([DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md)).
8. **Cheap to be honest.** UNKNOWN, empty arrays with a reason, and "no mechanism found" must be
   cheaper to produce than fabricated content. Review and validation treat unexplained absence as a
   defect and unexplained specificity as a worse one.

## 2. Snapshot envelope

An `AnalysisResult` is the protocol's snapshot payload. Current fields are fixed and must not be
renamed (`summary`, `facts`, `variables`, `mechanisms`, `stakeholders`, `firstOrderEffects`,
`secondOrderEffects`, `hypotheses`, `alternativeExplanations`, `counterArguments`,
`falsificationConditions`, `corroboratingSignals`, `verificationIndicators`, `unknowns`,
`confidenceAssessment`, `upcomingObservations`, `modifiesExistingHypotheses`, `demo`).

Proposed additions (SCH-01, SCH-07, SCH-13) add `sourceAssessment`, `predictions`, `protocolVersion`
and a `provenance` block. All are nullable so that existing stored payloads remain readable.

Snapshot envelope rules:

| id | rule |
| --- | --- |
| PR-01 | The snapshot must be self-contained: every ref inside it resolves inside the same snapshot (`factRefs` → `facts[].id`, `hypothesisRef` → `hypotheses[].id`, `sourceRefs` → the input `Source.id`). |
| PR-02 | `summary` restates what the snapshot concluded, not what the sources said. It may not contain a claim that does not appear in a typed artifact below it. |
| PR-03 | `modifiesExistingHypotheses` is `false` in v0.1. Longitudinal updates belong to TASK-06's append-only events, not to a new snapshot's claims. |
| PR-04 | `demo` is `true` only for mock/demo output. Demo output must never be presented as research. |
| PR-05 | Empty array means "evaluated, and none exist". A stage that was not evaluated must either fail the job or emit an UNKNOWN item; it may not emit `[]` silently. |
| PR-06 | Every snapshot records the analysis protocol version, the confidence rubric version, the domain strategy id and the prompt versions that produced it. Unversioned snapshots may not be scored. |

## 3. Stages

Each stage below defines: purpose, required input, output artifact, permitted epistemic types,
mandatory content, UNKNOWN behaviour, and empty semantics. `STG-nn` ids are normative and are used
by tests and task acceptance criteria.

### STG-01 Source

- **Purpose**: make the exact untrusted input explicit and reproducible.
- **Input**: persisted `Source` + `NewsItem` (created by TASK-02 ingestion; read-only here).
- **Output**: the snapshot's source reference (`Source.id`, `text`, `url`, `extractionStatus`).
- **Rules**: the text is data, never instruction. Offsets are UTF-16 code units into this exact text
  version. A source whose `extractionStatus` is `NEEDS_TEXT` cannot be analyzed.
- **UNKNOWN behaviour**: not applicable; a missing source is a job error.

### STG-02 Source Assessment

- **Purpose**: describe how much the input can carry, before any content is read.
- **Output**: `sourceAssessment` (proposed SCH-01).
- **Mandatory fields**: `sourceType`, `publisher`, `publishedAt`, `primaryOrSecondary`,
  `independence`, `contentCompleteness`, plus `notes[]`.
- **Vocabulary**:
  - `sourceType`: `PRIMARY_DOCUMENT | OFFICIAL_STATEMENT | COMPANY_DISCLOSURE | PRESS_RELEASE | NEWS_REPORT | WIRE_REPUBLICATION | OPINION_ANALYSIS | SOCIAL_POST | UNKNOWN`
  - `primaryOrSecondary`: `PRIMARY | SECONDARY | UNKNOWN`
  - `independence`: `SINGLE_SOURCE | SAME_PUBLISHER_DUPLICATE | INDEPENDENT_SET | UNKNOWN`
  - `contentCompleteness`: `COMPLETE | PARTIAL | TRUNCATED | METADATA_ONLY | UNKNOWN`
- **Derivation**: deterministic from `Source` where possible (`publisher` from the URL host;
  `independence` is `SINGLE_SOURCE` while the snapshot has one source; `contentCompleteness` from
  extraction status and text length). Anything not derivable is `UNKNOWN` with a note.
- **Must not**: build a publisher reputation ranking, assign a "trust score", or infer `publishedAt`
  from context. Media-credit leaderboards are explicitly out of scope.
- **UNKNOWN behaviour**: `publishedAt` and `primaryOrSecondary` are commonly UNKNOWN in v0.1 and that
  is a valid, expected result.
- **Empty semantics**: the assessment object always exists; empty `notes` is allowed.

### STG-03 Facts

- **Purpose**: reproduce what the source asserts, with exact spans.
- **Output**: `facts[]`, each `type = FACT`.
- **Mandatory content**: `statement` equal to the exact quoted span, `sourceRefs[]` with
  `startOffset`/`endOffset`/`quote`, `verificationStatus` (default `REPORTED`), `reasoning`
  explaining that this is a reported claim.
- **Rules**:
  1. One assertion per fact. Compound sentences joined by "and" must be split.
  2. Duplicate spans are collapsed; the same fact twice is not two facts.
  3. `CORROBORATED` requires an independent source in the snapshot set; with a single source it is
     unreachable and must not be claimed.
  4. The fact set must be non-empty. Failing to extract any fact fails the job: an analysis with no
     facts cannot support anything downstream.
- **UNKNOWN behaviour**: what the source does not say is not a fact; it becomes an UNKNOWN item in the
  snapshot's `unknowns` list (assembled by STG-16).

### STG-04 Key Variables

- **Purpose**: name what is changing and by how much.
- **Output**: `variables[]`, `type = INFERENCE`.
- **Mandatory content**: `name`, `previousState`, `currentState`, `direction`
  (`UP | DOWN | UNCHANGED | UNKNOWN`), `magnitude`, `whyItMatters`, `factRefs[]`, `sourceRefs[]`.
- **Rules**:
  1. `previousState` may be UNKNOWN, and must be UNKNOWN rather than guessed. A direction other than
     `UNKNOWN` requires `currentState` and at least one `factRef`.
  2. `magnitude` is qualitative or numeric with an explicit anchor; without an anchor it is UNKNOWN.
     No unit conversion, no invented percentages.
  3. `whyItMatters` must state the consequence chain the variable participates in, not restate the
     variable.
  4. A variable that only exists in the analyst's world knowledge is not a variable of this analysis:
     drop it or mark it UNKNOWN.
- **Empty semantics**: `[]` is valid (no variable is changing), but the snapshot must still state what
  the absence means in `unknowns` when the input clearly describes movement.

### STG-05 Mechanism

- **Purpose**: state how the variables influence each other.
- **Output**: `mechanisms[]` of type `CausalLink`, `type = INFERENCE`.
- **Mandatory content**: `from`, `to`, `explanation`, `supportLevel`, `factRefs[]`, `sourceRefs[]`.
- **`supportLevel` vocabulary** (normative, exactly three values):

  | level | meaning | minimum evidence |
  | --- | --- | --- |
  | `SUPPORTED` | the source documents this link in substance | fact ref for the cause premise *and* the effect observation |
  | `PLAUSIBLE` | a mechanism consistent with the facts but not documented | at least one fact ref, an explicit intermediate step, and stated assumptions |
  | `SPECULATIVE` | a hypothesis about the link, offered for testing | no fact ref may be used as if it supported the link; must name a corroborating signal |

- **Rules**:
  1. `from` and `to` must resolve to a variable or fact already in the snapshot. Free-floating nouns
     are invalid.
  2. No causal link may be invented to make the narrative cohere (EP-03). If none is documented,
     `mechanisms: []` plus an UNKNOWN item is the correct output.
  3. Correlation in the source is not a mechanism; it may only be reported as a FACT.
  4. A `SUPPORTED` link whose fact refs only cover one end is invalid.
- **Empty semantics**: `[]` is expected for thin inputs and is a quality signal, not a failure.

### STG-06 Stakeholders

- **Purpose**: who is exposed to the effects, and in which direction.
- **Output**: `stakeholders[]`, `type = INFERENCE`.
- **Mandatory content**: `stakeholder`, `direction`
  (`BENEFITS | HARMED | MIXED | UNKNOWN`), `statement`, `reasoning`, `factRefs[]`, `sourceRefs[]`.
- **Rules**:
  1. A stakeholder must be named at a granularity the input supports ("hyperscalers", not "the
     market").
  2. `direction` is UNKNOWN whenever exposure cannot be traced through STG-05/STG-07/STG-08.
  3. No magnitude claims without a ref; no roster of generic stakeholders.
- **Empty semantics**: `[]` valid.

### STG-07 First-order Effects

- **Purpose**: direct, near-term consequences.
- **Output**: `firstOrderEffects[]` as `Statement`, `type = INFERENCE`.
- **Mandatory content**: `statement`, `reasoning`, `factRefs[]`, plus the mechanism it follows from.
- **Rules**: each first-order effect must be traceable to a mechanism or a directly reported
  consequence. Time horizon is implied by "first-order" and must not be stated as a date unless the
  source gives one.

### STG-08 Second-order Effects

- **Purpose**: consequences of the first-order effects.
- **Output**: `secondOrderEffects[]` as `Statement`, `type = INFERENCE`.
- **Mandatory content**: `statement`, `reasoning`, `factRefs[]`, and the first-order effect it
  derives from (`derivedFromRefs[]`, SCH-03).
- **Rules**: a second-order effect with no first-order parent is invalid; this is the stage where
  fabrication is most common, so the parent link is mandatory. Effects build only on effects already
  stated above — no new premises.

### STG-09 Hypotheses

- **Purpose**: the best current explanation of the fact set, held provisionally.
- **Output**: `hypotheses[]` per [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) §2.3.
- **Mandatory content**: `id`, `title`, `statement`, `sourceRefs[]`, `supportingFactRefs[]`,
  `supportingEvidenceRefs[]`, `contradictingEvidenceRefs[]`, `assumptions[]`,
  `alternativeHypothesisRefs[]`, `falsificationConditions[]`, `confidenceScore`, `confidenceBand`,
  `confidenceReason`, `status`. Protocol `confidenceScore` is the contract's `Hypothesis.confidence`
  (0–100); `confidenceBand` is the band derived from it (SCH-06, SCH-09).
- **Rules**:
  1. At least one hypothesis whenever the input invites explanation; otherwise the snapshot must say
     so in `unknowns`.
  2. Every hypothesis carries at least one falsification condition (STG-12) and at least one
     alternative explanation (STG-10). A hypothesis that cannot be falsified is not emitted as a
     hypothesis.
  3. `contradictingEvidenceRefs` may be empty, but then `assumptions[]` must record what was not
     checked. An empty contradicting list without a note is invalid.
  4. `confidenceScore`/`confidenceBand` come from the deterministic rubric
     ([CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md)); a model-supplied number is advisory and
     must not be stored as the rubric score.
  5. Status starts at `OPEN` in a new snapshot. `CONFIRMED`/`REJECTED` require verification records
     owned by TASK-07 and are never asserted by a generation stage.
  6. Hypotheses are stated as explanations ("the decline is consistent with X because…"), never as
     facts.

### STG-10 Alternative Explanations

- **Purpose**: the rival explanatory model(s) that account for the *same* facts.
- **Output**: `alternativeExplanations[]` as `Statement`, `type = INFERENCE`.
- **Mandatory content**: `statement`, `reasoning`, the facts it explains, and the hypothesis it
  rivals (`rivalsHypothesisRef`, SCH-03).
- **Rules**: each alternative must explain the same fact set (§5.1 of EPISTEMIC_TYPES). Stylistic
  restatements, weaker versions of the main hypothesis, and "it could be anything" entries are
  invalid. At least one per emitted hypothesis.

### STG-11 Counter Arguments

- **Purpose**: the strongest objection to the current main hypothesis.
- **Output**: `counterArguments[]` as `Statement`, `type = INFERENCE`.
- **Mandatory content**: `statement`, `reasoning`, `targetHypothesisRef`, `factRefs[]` where the
  objection rests on a fact.
- **Rules**: the strongest objection must be attempted, not a list of generic caveats. An objection
  that is actually a disagreement with the source (rather than with the hypothesis) must be
  restated as a fact-status issue (`DISPUTED`) or dropped.

### STG-12 Falsification Conditions

- **Purpose**: pre-register what would show the hypothesis is wrong.
- **Output**: per-hypothesis conditions nested in `Hypothesis.falsificationConditions[]` (SCH-06),
  plus optional snapshot-level conditions in the top-level `falsificationConditions: Statement[]`.
  Nesting is what makes the condition traceable to the hypothesis it protects; the top-level list is
  kept for snapshot-wide conditions only.
- **Mandatory content**: for each condition: the observable, the comparison, and the decision
  boundary. "If circumstances change" is invalid.
- **Rules**:
  1. Conditions are written **before** results are known. They are not revised to fit outcomes.
  2. Conditions must be observable in principle and decidable by a third party.
  3. A hypothesis with no falsification condition is dropped from `hypotheses` and moved to
     `unknowns`.

### STG-13 Corroborating Signals

- **Purpose**: state where else a true hypothesis should leave a trace.
- **Output**: `corroboratingSignals[]` (proposed typed `CorroboratingSignal`, SCH-10).
- **Mandatory content**: `signal` (what should be observable), `where` (where to look),
  `hypothesisRef`, `window` (when it should be observable), `status`
  (`NOT_OBSERVED` at creation).
- **Rules**: signals are hypothetical expected observables, never existing evidence (§5.2 of
  EPISTEMIC_TYPES). A signal that was already observed belongs in `evidence`, not here. Signals must
  be checkable without privileged access.

### STG-14 Predictions

- **Purpose**: turn hypotheses into dated, checkable claims.
- **Output**: `predictions[]` (proposed as part of the snapshot, SCH-07), each
  `type = PREDICTION`.
- **Mandatory content**: `id`, `hypothesisRef`, `statement`, `observable`, `expectedBy`,
  `verificationCriteria`, `whereToCheck`, `status = OPEN`.
- **Rules**:
  1. `expectedBy` must be in the future relative to snapshot creation and within a horizon where the
     claim can actually be checked.
  2. `verificationCriteria` must distinguish confirmation from partial confirmation from rejection.
  3. Predictions are immutable once created: outcomes are recorded as verification events, never by
     editing the prediction text.
  4. A hypothesis may have zero predictions; then it must have a corroborating signal, so there is
     still something to watch.

### STG-15 Verification Plan

- **Purpose**: state what to check, where, and what each result would mean.
- **Output**: `verificationIndicators[]` (`Indicator` records with the plan fields proposed in SCH-08:
  `whereToCheck`, `supportingResult`, `contradictingResult`, `priority`, `deadline`, `hypothesisRef`).
  `Indicator.name` is the protocol's `whatToCheck`.
- **Mandatory content**: `whatToCheck`, `whereToCheck`, `supportingResult`, `contradictingResult`,
  `priority` (`HIGH | MEDIUM | LOW | UNKNOWN`), `deadline`, and the prediction or hypothesis it serves.
- **Rules**:
  1. Every `OPEN` prediction has at least one plan item with `deadline ≤ expectedBy`.
  2. `supportingResult` and `contradictingResult` must be distinguishable in advance; if they are the
     same, the plan item is not a verification.
  3. `whereToCheck` names a checkable venue or dataset. "Further research" is invalid.
  4. The plan is the protocol's answer to "what would change our mind", and it must be able to
     resolve to `CONFIRMED`, `PARTIAL`, `REJECTED` or `UNRESOLVED` without reinterpretation.

### STG-16 Confidence Assessment

- **Purpose**: a deterministic, auditable judgment of how well the snapshot is supported.
- **Output**: `confidenceAssessment` (proposed extended fields SCH-09) plus `unknowns[]`.
- **Mandatory content**: `score` (0–100), `band`, `dimensions[]`, `rubricVersion`, `method`, `reason`,
  `isProbability = false`.
- **Rules**:
  1. The score is computed by the rubric in [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md),
     not chosen by a model (EP-09, CF-01).
  2. `unknowns[]` lists what is missing, why, and what would resolve it. An empty `unknowns` on a
     single-source snapshot is itself a defect.
  3. The confidence score is **not** a probability of truth and must never be rendered as one.
  4. Confidence is assessed for the snapshot as a whole (summary + main hypothesis). Item-level
     confidence, where the contract has it, follows the scope table in
     [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) §8.

## 4. Stage to pipeline mapping

The pipeline keeps its 14 fixed step names and its `JobStatus` sequence (ADR-004). Protocol stages map
onto it as follows; TASK-03 implements the stages behind these boundaries without renaming them.
`JobStatus` needs no new values.

| pipeline step | `JobStatus` | protocol stages produced | note |
| --- | --- | --- | --- |
| `NormalizeInput` | `NORMALIZING` | STG-01, STG-02 | deterministic |
| `ExtractFacts` | `EXTRACTING_FACTS` | STG-03 | fact extraction |
| `ClassifyDomain` | `ANALYZING` | (context only) | sets `DomainType`; no epistemic artifact |
| `ScoreNews` | `ANALYZING` | (value only) | `NewsValueScore`; not an epistemic artifact |
| `ExtractVariables` | `ANALYZING` | STG-04 | |
| `AnalyzeMechanism` | `ANALYZING` | STG-05, STG-07, STG-08 | effects derive from mechanisms |
| `AnalyzeStakeholders` | `ANALYZING` | STG-06 | |
| `GenerateHypotheses` | `GENERATING_HYPOTHESES` | STG-09 | |
| `GenerateCounterArguments` | `GENERATING_HYPOTHESES` | STG-10, STG-11 | alternatives and objections |
| `GenerateFalsificationConditions` | `VERIFYING` | STG-12 | |
| `GenerateVerificationPlan` | `VERIFYING` | STG-14, STG-15 | predictions and plan are one decision |
| `GenerateCorroboratingSignals` | `VERIFYING` | STG-13 | |
| `SynthesizeAnalysis` | `SYNTHESIZING` | STG-16 + assembly | assembles and validates; must not invent content |
| `Persist` | `SYNTHESIZING` | envelope | immutable snapshot write |

Rules for the mapping:

- `SynthesizeAnalysis` may repair, reject and assemble; it may not author claims that no stage
  produced. If an upstream stage produced nothing, it may only emit UNKNOWN items or fail the job.
- A stage that is still a scaffold (no artifact produced) must fail the job with an explicit error.
  It must never return an empty artifact that reads as "evaluated, nothing found" (PR-05). This is the
  main behavioural change TASK-03 introduces for currently empty steps.
- Model purposes stay within the existing `ModelPurpose` enum. Per-stage purposes beyond the current
  six values require an integrator-approved contract change; Wave 2A can be conformant without one.
- Step order is fixed. Stages may not be reordered to hide a missing input; a missing input fails the
  stage.
- The chain order is a *dependency* order, not a production order: STG-13 (corroborating signals),
  STG-14 (predictions) and STG-15 (verification plan) are mutually independent and may be produced in
  any order inside `VERIFYING`, as long as the field mapping above holds and STG-16 runs last.

## 5. Provenance and reference rules

| id | rule |
| --- | --- |
| PR-07 | `SourceRef` quotes are verbatim substrings of the source text; `endOffset > startOffset`; offsets are UTF-16 code units. |
| PR-08 | Fact ids are UUIDs unique inside the snapshot; every ref list uses ids from this snapshot only. |
| PR-09 | A ref that cannot be resolved is a validation failure, not a warning. Invalid refs must not be silently dropped. |
| PR-10 | Provenance is never "repaired" by editing the source text; mismatched spans fail the snapshot. |
| PR-11 | A claim with no provenance anywhere (no `sourceRefs`, no `factRefs`) can only be UNKNOWN. |
| PR-12 | Republished agency copy does not count as independent corroboration (`independenceKey` dedupe). |
| PR-13 | Confidence and refs travel together: raising confidence for an item without adding refs is invalid. |

## 6. Determinism, versioning and audit

| id | rule |
| --- | --- |
| PR-14 | Deterministic stages (STG-02, rubric scoring, ref validation, assembly) produce identical output for identical input and identical protocol/rubric versions. |
| PR-15 | Model-backed stages are non-deterministic and must record prompt version and model run id; the snapshot references them. |
| PR-16 | `protocolVersion`, `rubricVersion`, `domainStrategyId` and prompt versions are recorded per snapshot (PR-06). |
| PR-17 | A stored snapshot is never rewritten. Changes are new snapshots or append-only research events. |
| PR-18 | Validation failures are explicit job failures with a durable event. One bounded repair attempt is allowed, as today; unbounded re-prompting is not. |

## 7. Validation gates

Gate A — **structural**: schema validation plus the `NotNull`/range/pattern constraints generated from
OpenAPI. Owned today by `AnalysisResultValidator`.

Gate B — **epistemic**: the EP-01…EP-12 rules; FACT-only in `facts`, refs present, UNKNOWN never cited
as support, alternatives/counter-arguments distinct, signals not treated as evidence.

Gate C — **provenance**: every `SourceRef` resolves verbatim against the source text; every
`factRefs`/`hypothesisRef` resolves inside the snapshot.

Gate D — **confidence**: `isProbability` false; rubric recomputation equals the stored score;
dimension levels present when `method = RUBRIC`; band matches the score.

Gate E — **completeness**: every protocol stage either produced its artifact or produced an explicit
UNKNOWN item; no scaffold-returned `[]`.

A snapshot is persisted only if A–E pass. Gate D and E enforcement is staged across Wave 2A/2B as
described in [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md); until then the current
validator behaviour (A+C plus part of B) remains the enforced floor.

## 8. Explicitly out of scope for this protocol

The protocol deliberately does not specify:

- prompt text or prompt structure;
- number, order or granularity of model calls;
- retrieval, embeddings, vector stores, RAG;
- cross-snapshot topic association, semantic search or longitudinal aggregation mechanics (TASK-06
  and TASK-07 own the research-side rules);
- any agent framework, planner, or multi-agent topology;
- publisher reputation scoring;
- calibrated probability output of any kind.

## 9. Conformance checklist

A Wave 2 deliverable conforms to this protocol when:

1. it produces exactly the artifacts of the stages it owns, using the stage's mandatory fields;
2. it never promotes an INFERENCE to a FACT, and never asserts a HYPOTHESIS or PREDICTION as settled;
3. it emits UNKNOWN instead of guessing, per EP-01/EP-02;
4. it fails the job rather than emitting a silent empty artifact for an unevaluated stage (PR-05);
5. its refs resolve inside the snapshot (Gates B/C);
6. it obtains confidence only from the rubric (Gate D) and records the versions it used (PR-16);
7. it treats domain strategy input as recommendations only
   ([DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) DS-01…DS-12).

## 10. Traceability

| protocol area | implementation home | task |
| --- | --- | --- |
| STG-02 source assessment | `analysis/application/steps` (`NormalizeInput`) | TASK-03 |
| STG-03 facts | `analysis/application/steps` (`ExtractFacts`) | TASK-03 |
| STG-04…STG-08, STG-10…STG-13 | `analysis/application/steps` | TASK-03 |
| STG-16 rubric scoring | deterministic scorer invoked by `SynthesizeAnalysis` | TASK-03 |
| domain recommendations feeding STG-04/05/13/15 | `analysis/application/strategies` | TASK-05 |
| hypothesis status transitions, hypothesis-level confidence events | `research/application/hypotheses` | TASK-06 |
| evidence, predictions, verification outcomes | `research/application/evidence` | TASK-07 |
| schema proposals SCH-01…SCH-14 | OpenAPI + generated contracts | integrator, per [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md) |
