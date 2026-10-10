# SignalFrame Schema Impact Assessment (Analysis Protocol v0.1)

Status: Assessment for Wave 2 planning. **No schema change is made in this commit.**
Related: [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) · [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) · [proposals/SCHEMA_PROPOSAL_V0_2.md](proposals/SCHEMA_PROPOSAL_V0_2.md) · [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md)

## 1. How this assessment was produced

Inspected: `contracts/openapi.yaml` (31 schemas, 14 paths), `contracts/analysis-result.schema.json`,
all generated records in `apps/api/src/main/java/com/signalframe/contract`, the domain classes in
`analysis/`, `research/`, `ai/` and `infrastructure/`, the web consumers
(`AnalysisView.tsx`, `labels.ts`, `api.generated.ts`), `V1__foundation.sql`,
`scripts/generate-java.py`, and `scripts/check-contracts.mjs`.

Verdict vocabulary:

| verdict | meaning |
| --- | --- |
| `SUFFICIENT` | the current schema already carries the protocol artifact; no change needed |
| `EXTEND` | additive change required (nullable/new field, new enum member, new schema) |
| `STAGED` | needed, but breaking for a reader/generated consumer; must be introduced in a coordinated step |
| `NOT AFFECTED` | out of scope for Wave 2A/2B |

The headline result: **the existing `AnalysisResult` field list already matches the protocol chain
almost field-for-field.** The chain has 16 stages and the snapshot has 18 top-level fields covering
them. The gaps are in *depth inside* the artifacts (provenance refs, verification status, hypothesis
structure, deterministic confidence), not in the chain itself. This is why Wave 2 can proceed with
additive extensions rather than a redesign.

## 2. Contract summary

| schema | verdict | proposals | note |
| --- | --- | --- | --- |
| `AnalysisResult` | `EXTEND` | SCH-01, SCH-07, SCH-13 | add `sourceAssessment`, `predictions`, `provenance`/`protocolVersion`; existing field set stays |
| `ClaimType` | `STAGED` | SCH-05 | add `UNKNOWN`; breaks the exhaustive web label maps |
| `Statement` | `EXTEND` | SCH-03 | add `factRefs`, `derivedFromRefs`, `targetHypothesisRef`, `rivalsHypothesisRef` |
| `SourceRef` | `SUFFICIENT` | — | `sourceId` + `quote` + UTF-16 offsets is exactly the provenance unit |
| `Source` | `SUFFICIENT` | — | ingestion-owned; analysis fields must not be added here |
| `Fact` | `EXTEND` | SCH-02 | add `verificationStatus`; keep `confidence` as reporting reliability |
| `Variable` | `EXTEND` | SCH-04 | add `previousState`, `currentState`, `magnitude`, `whyItMatters`, `factRefs`; `direction` already has `UNKNOWN` |
| `CausalLink` | `EXTEND` | SCH-12 | add `supportLevel`; `cause`/`effect` are the protocol's `from`/`to` (no rename) |
| `StakeholderImpact` | `EXTEND` | SCH-03 | fields map to STG-06; only `factRefs` is missing, added by the same reference-field proposal that covers `Statement` |
| `Hypothesis` | `STAGED` | SCH-06 | many fields missing; `status` vocabulary conflicts with the protocol |
| `Evidence` | `EXTEND` | SCH-11 | add `factRefs`, `analysisId`; stance/strength/source are already right |
| `Prediction` | `STAGED` | SCH-07 | add `OPEN` to `status` (DB CHECK constraint too); not currently emitted in the snapshot |
| `Indicator` | `EXTEND` | SCH-08 | add `whereToCheck`, `supportingResult`, `contradictingResult`, `priority`, `deadline` |
| `ConfidenceAssessment` | `EXTEND` | SCH-09 | add `band`, `method`, `rubricVersion`, `dimensions[]`, `advisoryScore`; keep `isProbability` |
| `HypothesisEvent` | `SUFFICIENT` | — | `eventType` already has `CONFIDENCE_CHANGED`/`PREDICTION_VERIFIED`; extra detail rides in the JSONB payload |
| `HypothesisDetail` | `SUFFICIENT` | — | already assembles hypothesis + timeline + evidence + predictions |
| `DomainType` | `SUFFICIENT` | — | all twelve protocol domains plus `OTHER` already exist |
| `Topic`, `Analysis`, `AnalysisJob`, `JobEvent`, `JobStatus`, `NewsItem`, `NewsInput`, `NewsGrade`, `NewsValueScore`, `ApiError`, `ModelRun`, `ModelProfile`, `NamedModelProfile` | `NOT AFFECTED` | — | Wave 2A/2B do not require changes |
| `ModelPurpose` | `EXTEND` (deferred) | — | only if per-stage model calls are introduced; Wave 2A is conformant without new purposes |

## 3. Element detail

### 3.1 `AnalysisResult` — `EXTEND`

Existing fields map to the protocol stages as follows, and none of them need renaming:

| protocol stage | current field | verdict |
| --- | --- | --- |
| STG-02 Source Assessment | *(missing)* | SCH-01 |
| STG-03 Facts | `facts: Fact[]` | ok |
| STG-04 Key Variables | `variables: Variable[]` | ok |
| STG-05 Mechanism | `mechanisms: CausalLink[]` | ok |
| STG-06 Stakeholders | `stakeholders: StakeholderImpact[]` | ok |
| STG-07/08 effects | `firstOrderEffects`, `secondOrderEffects: Statement[]` | ok |
| STG-09 Hypotheses | `hypotheses: Hypothesis[]` | ok |
| STG-10 Alternatives | `alternativeExplanations: Statement[]` | ok |
| STG-11 Counter arguments | `counterArguments: Statement[]` | ok |
| STG-12 Falsification | `falsificationConditions: Statement[]` | ok (per-hypothesis nesting is SCH-06) |
| STG-13 Corroborating signals | `corroboratingSignals: Statement[]` | SCH-10 (typed schema) |
| STG-14 Predictions | *(missing)*; `upcomingObservations` is not a substitute | SCH-07 |
| STG-15 Verification plan | `verificationIndicators: Indicator[]` | SCH-08 |
| STG-16 UNKNOWN | `unknowns: Statement[]` | SCH-05 (type semantics) |
| STG-16 Confidence | `confidenceAssessment` | SCH-09 |
| envelope | `summary`, `demo`, `modifiesExistingHypotheses` | ok; add version/provenance (SCH-13) |

The existing empty-array semantics are exactly what PR-05 replaces, which is also why `demo` and
`modifiesExistingHypotheses` must stay: they carry honesty signal at the envelope level.

### 3.2 `ClaimType` — `STAGED (SCH-05)`

Currently `FACT | INFERENCE | HYPOTHESIS | PREDICTION`. `UNKNOWN` is not a claim type, it is an
epistemic outcome; today it is smuggled in as an `INFERENCE` in `unknowns[]` (see
`MockModelGateway`, which reuses one `unknown` Statement five times with `type = INFERENCE`).

Adding `UNKNOWN` is the minimal correction, but it is breaking for exhaustive readers:

- `apps/web/src/lib/labels.ts` declares `Record<Schema<"ClaimType">, string>` twice → `npm run typecheck`
  fails until both maps gain an `UNKNOWN` entry.
- `AnalysisResultValidator.inference(...)` and the FACT-only loops need explicit UNKNOWN handling so
  that UNKNOWN is rejected in `facts` and permitted where the protocol requires it.
- The generated Java enum gains a member; any `switch` over `ClaimType` becomes non-exhaustive (none
  exists today, but the compiler warning would surface it).

Therefore SCH-05 is one atomic change: OpenAPI + regeneration + `labels.ts` + validator + tests.

### 3.3 `Fact` — `EXTEND (SCH-02)`

`Fact` already has id, type, statement, reasoning, confidence and `sourceRefs`. The only protocol
requirement missing is `verificationStatus`. `confidence` must be documented as *source-report
reliability* (CF scope table), not truth. The current validator's "statement must quote source
verbatim" rule is stronger than the protocol requires and should be kept.

### 3.4 `Variable` — `EXTEND (SCH-04)`

Missing: `previousState`, `currentState`, `magnitude`, `whyItMatters`, `factRefs`. `direction` already
carries `UNKNOWN`, which satisfies the protocol requirement that a missing previous state must not be
invented. Note the current `Variable` reuses `statement`/`reasoning`/`confidence`, so the new fields
are additive and nullable.

### 3.5 `CausalLink` — `EXTEND (SCH-12)`

`cause`/`effect` are the protocol's `from`/`to`; no rename is needed (renaming would break
`AnalysisView.tsx`'s causal rendering for no semantic gain). Missing: `supportLevel`
(`SUPPORTED | PLAUSIBLE | SPECULATIVE`) and `factRefs`. A `supportLevel` without `factRefs` is
unenforceable, so SCH-12 and SCH-03 must land together.

### 3.6 `Hypothesis` — `STAGED (SCH-06)`

Present: id, title, description, status, confidenceReason, createdAt, updatedAt, type, statement,
reasoning, confidence, sourceRefs.

Missing: `supportingFactRefs`, `supportingEvidenceRefs`, `contradictingEvidenceRefs`, `assumptions`,
`alternativeHypothesisRefs`, per-hypothesis `falsificationConditions`, `confidenceBand`.

Conflict: the current `status` vocabulary (`OPEN | SUPPORTED | CHALLENGED | REJECTED | ARCHIVED`) is not
the protocol vocabulary (`OPEN | STRENGTHENING | WEAKENING | CONFIRMED | REJECTED | UNRESOLVED`).
The safe path is to **extend and map, not replace**: keep the legacy members for stored payloads, add
the protocol members, and record the mapping in [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) §6. Replacing
the enum would make every stored `hypotheses.payload` unreadable and would break
`hypothesisStatusLabel` in `labels.ts`.

`Hypothesis.confidence` already exists as 0–100, so hypothesis-level rubric scoring needs no schema
change beyond `confidenceBand`.

### 3.7 `Prediction` and `Evidence` — `STAGED` / `EXTEND`

`Prediction` exists as a relational record and is served through `HypothesisDetail`, but
`AnalysisResult` has **no predictions array**. `upcomingObservations` is a `Statement[]` reading list
and is explicitly not a prediction (EPISTEMIC_TYPES §5.4). Two consequences:

1. A fresh analysis cannot emit the dated, checkable predictions the protocol requires (STG-14).
2. `Prediction.status` lacks `OPEN`, so a newly created prediction has no valid state. The DB CHECK
   constraint in `V1__foundation.sql` (`status IN ('CONFIRMED','REJECTED','PARTIAL','UNRESOLVED')`)
   must be altered in the same change.

`Evidence` has the right shape (hypothesisId, stance, strength, sourceId, reason, createdAt) but lacks
`factRefs` and any link back to the analysis that produced it, which TASK-07 needs for traceability.

Cross-cutting note: `Prediction.hypothesisId` is a UUID. Inside a snapshot, hypotheses are generated
ids, so a snapshot prediction referencing the snapshot's own hypothesis id is valid and resolvable
(PR-01). Persistence must keep that id stable when hypotheses are written to the `hypotheses` table.

### 3.8 `Indicator` — `EXTEND (SCH-08)`

The protocol's verification plan needs `whatToCheck`, `whereToCheck`, `supportingResult`,
`contradictingResult`, `priority`, `deadline`. Current `Indicator` has `name` (→ whatToCheck),
`measurement`, `frequency`, nullable `predictionId`. Additive fields cover the rest; `name` is not
renamed so the web view keeps rendering.

### 3.9 `ConfidenceAssessment` — `EXTEND (SCH-09)`

Current: `score` (0–100, required), `reason` (required), `isProbability` (must be `false`).
`isProbability` is load-bearing and stays. Additive: `band`, `method`, `rubricVersion`,
`dimensions[]`, `advisoryScore`. `score` and `reason` stay required so that existing payloads and readers
keep working. Adding components still changes the record's single canonical constructor, so the
`MockModelGateway` call site must be updated in the same change — records have no overloaded constructors
(see proposal §9).

### 3.10 What is already sufficient and must not be "improved"

- `SourceRef` — do not add publisher or trust data; provenance is a span.
- `Source` — do not add `publisher`/`publishedAt` here; those are analysis-time (STG-02) and `Source` is
  ingestion-owned.
- `StakeholderImpact` — the four-value direction enum with `UNKNOWN` is right; do not add magnitude.
  The only addition is `factRefs` (SCH-03).
- `HypothesisEvent` — do not add columns for rubric detail; the payload JSONB is the extension point.
- `HypothesisDetail` — already the correct read model for TASK-06/07.
- `DomainType` — the twelve domains are already present; new values are integrator-owned.
- `JobStatus` — the fourteen steps need no new job state, contrary to the first instinct that a
  "protocol" implies more statuses.

### 3.11 Java domain classes and ports (not OpenAPI schemas)

The contract records are only half of deliverable 5; the handwritten domain classes have their own
sufficiency verdicts.

| class / interface | location | verdict | note |
| --- | --- | --- | --- |
| `AnalysisResult` and generated records | `contract` | `EXTEND` via SCH | generated; never hand-edited |
| `AnalysisStep<PipelineContext,PipelineContext>` | `analysis/domain` | `SUFFICIENT` | the fixed 14-step boundary holds; only the bodies change |
| `PipelineContext` | `analysis/application` | `EXTEND` (TASK-03) | currently carries only `facts`, `domain`, `score`, `result`; staged artifacts need fields for source assessment, variables, mechanisms, hypotheses, predictions and the plan |
| `DomainAnalysisStrategy` | `analysis/domain` | `EXTEND` | returns a single `String guidance()`; cannot express variable/mechanism/metric dictionaries ([DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) §4) |
| `DefaultDomainStrategy`, `TechnologyAnalysisStrategy` | `analysis/application/strategies` | `EXTEND` (TASK-05) | must implement `spec()`/`specificity()` atomically with the interface |
| `NewsValueScorer`, `DefaultNewsValueScorer` | `analysis` | `NOT AFFECTED` | value grading is orthogonal to epistemic confidence; do not merge them |
| `AnalysisRepository` | `analysis/domain` | `SUFFICIENT` | snapshot write path is already immutable-complete |
| `ResearchRepository` | `research/domain` | `EXTEND` (2B) | read-only today; TASK-06/07 need write/transition ports in `research/domain/hypotheses` and `research/domain/evidence` |
| `SemanticRetriever` | `research/domain` | `NOT AFFECTED` | retrieval stays deferred (ADR-005) |
| `ModelAnalysisService` | `ai/application` | `NOT AFFECTED` | call topology is out of protocol scope; only the validator's rules change |
| `AnalysisResultValidator` | `ai/application` | `EXTEND` | Gate B/C rules become expressible once SCH-02/03/05 land |
| `JsonCodec`, `ApplicationException` | `shared` | `SUFFICIENT` | the natural home for the new `shared/confidence` rubric |

Two consequences worth planning for:

1. `PipelineContext` currently holds `List<Fact> facts` only, so TASK-03 must extend it before staged
   artifacts can accumulate. That is inside TASK-03's owned path
   (`analysis/application/PipelineContext.java`).
2. `ResearchRepository` has no write surface at all (`hypotheses()`, `hypothesis(id)`, `topics()`,
   `topic(id)`). Wave 2B therefore adds ports rather than extending a fat repository, which keeps
   TASK-06 and TASK-07 from colliding inside one interface.

## 4. Persistence assessment

`V1__foundation.sql` is the only migration. The snapshot strategy (immutable JSONB in `analyses.payload`)
means the SCH-01…SCH-13 **snapshot fields** require **no new columns**: they ride inside the payload.

| table | verdict | note |
| --- | --- | --- |
| `analyses` | `SUFFICIENT` | payload JSONB carries the whole snapshot including version/provenance |
| `sources`, `news_items`, `analysis_jobs`, `job_events`, `model_runs` | `SUFFICIENT` | untouched |
| `hypotheses` | `EXTEND` (migration) | needs an optimistic-concurrency column (`version`) for TASK-06 acceptance |
| `hypothesis_events` | `SUFFICIENT` | append-only timeline already correct |
| `evidence` | `EXTEND` (migration) | needs `analysis_id` and, if TASK-07 exposes it, a `fact_refs` column or payload-only storage |
| `predictions` | `STAGED` (migration) | `status` CHECK must accept `OPEN`; needs `verified_at` for TASK-07 |
| `indicators` | `SUFFICIENT` | already nullable `prediction_id` and NOT NULL `analysis_id` |
| `topics`, `topic_news`, `topic_hypotheses` | `NOT AFFECTED` | Wave 2 out of scope |

Migration reservations are requested, not created, by this round — see
[WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md) §7. All migrations stay integrator-owned
and immutable once applied (AGENTS.md).

## 5. Tooling constraints that shape the proposals

`scripts/generate-java.py` is a deliberately small OpenAPI subset generator. It supports:

- `type: object` with `properties` + `required` (it preserves but does not read
  `additionalProperties: false`, which is harmless because records have exactly the declared components);
- string enums (including inline enum on a string property → a `Pattern` constraint);
- arrays of primitives or `$ref`;
- `nullable: true`; `minimum`/`maximum`; `minLength`/`maxLength`; `pattern`; `format: uuid|date-time`;
- `enum: [false]` → `@AssertFalse` (used by `isProbability`).

It raises `ValueError` for an unsupported property type, silently skips a top-level schema that is
neither an enum nor `type: object`, and `check-contracts.mjs` fails on generation drift. So:

1. **No `oneOf`/`anyOf`/`allOf` unions.** State machines are modelled as an enum with an `UNKNOWN`
   member, not as a union type.
2. **New components are optional, and call sites still move together.** Optionality keeps legacy
   *payloads* and third-party readers working. Every new component still changes the record's single
   canonical constructor, so all in-repo construction sites (`MockModelGateway`, `AnalysisPipeline`)
   must be updated in the same change; no proposal adds a new `required` field to an existing schema.
3. Adding a new `$ref` inside an existing array (e.g. `CorroboratingSignal`) is supported but changes the
   generated record type; consumers must be updated in the same change.
4. Adding an enum member to an existing enum is supported by the generator and by
   `openapi-typescript`, but **breaks exhaustive TypeScript `Record` maps** (§3.2, §3.6).
5. Proposals must be applied through `npm run contracts` so that the Java records,
   `apps/web/src/lib/api.generated.ts`, `contracts/analysis-result.schema.json` and the classpath copy
   stay in sync; `npm run contracts:check` is the gate.

## 6. Proposals index

Full YAML patches are in [proposals/SCHEMA_PROPOSAL_V0_2.md](proposals/SCHEMA_PROPOSAL_V0_2.md).

| id | change | kind | wave |
| --- | --- | --- | --- |
| SCH-01 | `SourceAssessment` schema + `AnalysisResult.sourceAssessment` | additive | 2A |
| SCH-02 | `Fact.verificationStatus` | additive | 2A |
| SCH-03 | reference fields on `Statement` + `StakeholderImpact.factRefs` | additive | 2A |
| SCH-04 | `Variable` state fields | additive | 2A |
| SCH-05 | `ClaimType += UNKNOWN` | staged/breaking readers | 2A |
| SCH-06 | `Hypothesis` structure + status extension | staged/breaking readers | 2A (fields) / 2B (transitions) |
| SCH-07 | `AnalysisResult.predictions` + `Prediction.status += OPEN` + `observable`/`whereToCheck` | staged + migration | 2B |
| SCH-08 | `Indicator` verification-plan fields | additive | 2A |
| SCH-09 | `ConfidenceAssessment` rubric fields | additive | 2A |
| SCH-10 | `CorroboratingSignal` schema (Statement-compatible superset) | additive | 2A |
| SCH-11 | `Evidence.factRefs` + `analysisId` | additive + migration | 2B |
| SCH-12 | `CausalLink.supportLevel` | additive | 2A |
| SCH-13 | `AnalysisResult.protocolVersion` + `Provenance` | additive | 2A |
| SCH-14 | persistence deltas (migrations, not OpenAPI) | migration | 2B |

## 7. What happens if Wave 2 proceeds without the proposals

Not hypothetical — each has a concrete failure:

| missing | concrete consequence |
| --- | --- |
| SCH-01 | no `publisher`/`publishedAt`, so D1 (Source Quality) cannot be derived and the rubric cannot run at all |
| SCH-02 | `DISPUTED`/`CORROBORATED` cannot be represented; CAP-B is unimplementable; facts stay flat claims |
| SCH-03 | INFERENCE cannot cite facts, so EP-05 is unenforceable and the "no unsupported inference" rule degrades to an honour system |
| SCH-04 | variable direction remains a free-floating judgment; `previousState` cannot be shown as unknown |
| SCH-05 | UNKNOWN keeps masquerading as INFERENCE, which is precisely the collapse the protocol exists to prevent |
| SCH-06 | hypotheses cannot reference evidence, alternatives or per-hypothesis falsification — TASK-06 has nothing durable to transition |
| SCH-07 | no predictions in a snapshot; TASK-07 has no input and the "verifiable" half of the product is unrepresentable |
| SCH-08 | the verification plan cannot say what result would contradict; Gate C/E cannot pass |
| SCH-09 | confidence remains a model-supplied number, contradicting the core requirement of this round |
| SCH-10 | signals remain indistinguishable from evidence in the contract |
| SCH-11 | evidence cannot be tied back to facts or to the analysis that produced it |
| SCH-12 | mechanisms have no honest strength label, so "do not invent causal links" cannot be checked |
| SCH-13 | snapshots are unversioned, so scores cannot be compared or reproduced |

## 8. Conclusion

The existing contract is closer to the protocol than expected: the stage chain, the four claim types,
the four-value direction enums, the twelve domains, the job states and the provenance unit all already
exist and need no redesign. Wave 2 requires **additive depth inside artifacts**, one enum extension
(`UNKNOWN`), one vocabulary extension (`Hypothesis.status`), six new schemas (`SourceAssessment`,
`CorroboratingSignal`, `ConfidenceBand`, `ConfidenceDimension`, `ConfidenceMethod`, `Provenance`), two
new snapshot fields (`predictions`, `protocolVersion` alongside the `provenance` block), and
three small migrations. None of it requires touching the news, jobs or ai module boundaries, and none
of it requires re-implementing the pipeline's step names or `JobStatus`.
