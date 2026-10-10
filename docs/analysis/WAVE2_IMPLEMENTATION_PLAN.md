# SignalFrame Wave 2 Implementation Plan

Status: Planning artifact for Wave 2A/Wave 2B. **No Wave 2 feature code is written in this commit.**
Contracts: [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) · [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) · [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) · [DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) · [SCHEMA_IMPACT_ASSESSMENT.md](SCHEMA_IMPACT_ASSESSMENT.md) · [proposals/SCHEMA_PROPOSAL_V0_2.md](proposals/SCHEMA_PROPOSAL_V0_2.md)

## 1. Wave structure

```
P0  contract change 2A (integrator)                      ── gate for everything in 2A
Wave 2A ── TASK-03 analysis pipeline   (analysis steps, rubric, selection)
        └─ TASK-05 domain strategies   (spec interface, BUSINESS/FINANCE/POLICY, 12 dictionaries)
P1  contract change 2B + migrations     (integrator, after 2A merges)
Wave 2B ── TASK-06 hypothesis engine    (transitions, append-only confidence)
        └─ TASK-07 evidence/prediction  (evidence links, dated predictions, verification)
TASK-10 consumes the integrated revision (out of scope here)
```

Wave 2A is where the protocol becomes real: stages stop returning empty arrays, facts gain
verification status and refs, and confidence stops being a model-chosen number.
Wave 2B is where it becomes longitudinal: hypotheses transition with recorded reasons, and
predictions get verified against the dates they named.

## 2. Owned-path and ownership matrix

Ownership follows AGENTS.md: one writer per path. "Grant request" means the integrator must widen the
task's Owned Paths or own the file itself; it is not permission to edit now.

| path | owner | note |
| --- | --- | --- |
| `apps/api/src/main/java/com/signalframe/analysis/application/steps/**` | TASK-03 | stage implementations |
| `apps/api/src/main/java/com/signalframe/analysis/application/AnalysisPipeline.java` | TASK-03 | specificity-based strategy selection |
| `apps/api/src/main/java/com/signalframe/analysis/application/AnalysisJobService.java` | TASK-03 | job orchestration |
| `apps/api/src/test/java/com/signalframe/analysis/**` | TASK-03 | stage and slice tests |
| `apps/api/src/main/resources/prompts/**` | TASK-03 | reserve each prompt dir individually |
| `apps/api/src/main/java/com/signalframe/analysis/application/strategies/**` | TASK-05 | strategies + spec records |
| `apps/api/src/test/java/com/signalframe/analysis/strategies/**` | TASK-05 | strategy tests |
| `apps/api/src/main/java/com/signalframe/analysis/domain/DomainAnalysisStrategy.java` | **grant request** | interface change; single file, atomic with TASK-05's implementations |
| `apps/api/src/main/java/com/signalframe/shared/confidence/**` | **grant request** | rubric home (neutral module, see §4.3) |
| `apps/api/src/main/java/com/signalframe/contract/**` | integrator | generated |
| `apps/api/src/main/java/com/signalframe/ai/application/AnalysisResultValidator.java` | integrator (P0) | shared validator; TASK-03 keeps its gates inside `steps` |
| `apps/api/src/main/java/com/signalframe/infrastructure/ai/providers/MockModelGateway.java` | integrator (P0) | reference fixture for the protocol |
| `contracts/openapi.yaml`, `scripts/**` | integrator | canonical contract and generation |
| `apps/api/src/main/java/com/signalframe/infrastructure/persistence/**` | **integrator / one grantee per file** | TASK-06 and TASK-07 both need JDBC classes here; split by file |
| `apps/api/src/main/java/com/signalframe/http/**` | **integrator** | new routes are allocated, not added by task agents |
| `apps/api/src/main/resources/db/migration/**` | integrator | immutable, reserved numbers |
| `apps/api/src/main/java/com/signalframe/research/application/hypotheses/**`, `research/domain/hypotheses/**` | TASK-06 | hypothesis engine |
| `apps/api/src/main/java/com/signalframe/research/application/evidence/**`, `research/domain/evidence/**` | TASK-07 | evidence/prediction |
| `apps/web/src/components/research/HypothesisPage.tsx` | TASK-06 | timeline UI |
| `apps/web/src/lib/labels.ts`, `apps/web/src/components/AnalysisView.tsx` | integrator (contract change) | exhaustive enum maps break on SCH-05/06 |

## 3. P0 — contract change 2A (integrator, blocking)

Applies SCH-01…SCH-06, SCH-08…SCH-10 and SCH-12…SCH-13 (SCH-07 and SCH-11 are Wave 2B) from
[proposals/SCHEMA_PROPOSAL_V0_2.md](proposals/SCHEMA_PROPOSAL_V0_2.md) as one change, following its §15
and §17. Deliverable: a regenerated contract revision where:

- `AnalysisResult` has `sourceAssessment`, `protocolVersion`, `provenance`, typed `corroboratingSignals`;
- `Fact.verificationStatus`, `Variable` state fields, `Statement` refs, `CausalLink.supportLevel`,
  `Indicator` plan fields, `ConfidenceAssessment` rubric fields, extended `Hypothesis`, `ClaimType`
  with `UNKNOWN`;
- `MockModelGateway`, `AnalysisPipeline`'s `Fact` construction, `AnalysisResultValidator` and
  `labels.ts` compile and pass;
- the synthesis prompt version is bumped to mention the new optional artifacts.

Exit gate: `npm run contracts:check` + `./scripts/mvn.sh -f apps/api/pom.xml test` +
`npm run lint` + `npm run typecheck` + `npm run build`.

**Recommended ADR** (integrator): ADR-009 "Deterministic confidence rubric and nullable protocol
extension" recording the two decisions that shape Wave 2 — rubric replaces model numbers, and protocol
extensions are additive/nullable rather than breaking.

## 4. Wave 2A

### 4.1 TASK-03 — analysis pipeline

**Goal**: real stages behind the fixed 14 step boundaries, deterministic confidence, no
inference-to-fact promotion.

Implementation impact:

| item | detail |
| --- | --- |
| interfaces to implement | `AnalysisStep<PipelineContext,PipelineContext>` (exists, unchanged); new `ConfidenceRubric` consumed by the scoring stage; `DomainAnalysisStrategy.specificity()` consumed by selection |
| schemas to extend | none by TASK-03 directly; consumes the P0 revision (SCH-01…SCH-06, 08, 09, 10, 12, 13), including `ClaimType.UNKNOWN` and the extended `Hypothesis` |
| modules owning changes | `analysis/application/steps`, `analysis/application` (pipeline/service), `analysis/domain` (one granted interface if the rubric is placed there instead of `shared/confidence`) |
| read-only dependencies | `AnalysisStep`, `ModelAnalysisService`, `JobRepository`, `AnalysisRepository`, `contract`, strategies, `ConfidenceRubric` |
| must not change | canonical contracts, generated code, `ai/**` (including `AnalysisResultValidator`), `infrastructure/**`, root build config, migrations |

Increments (each independently testable, each left in a green state):

1. **Stage honesty (smallest useful change).** Replace every scaffold step that returns `c -> {}` with
   either a real implementation or an explicit stage failure carrying the step name. Enforce PR-05:
   no silent `[]` for an unevaluated stage. Add STG-02 Source Assessment as a deterministic step inside
   `NormalizeInput` (publisher from URL host, `independence = SINGLE_SOURCE`, completeness from
   extraction status and text length, `publishedAt` null with a note).
2. **Facts.** `ExtractFacts` produces atomic facts with verbatim `sourceRefs`, dedupe by span,
   `verificationStatus = REPORTED`, and rejects `CORROBORATED` while the snapshot has one source.
   Failure to extract any fact fails the job.
3. **Deterministic confidence.** Implement the rubric from
   [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) as a pure component, invoke it in
   `SynthesizeAnalysis` after assembly, and enforce CF-08 (recompute equals stored). The model's number
   moves to `advisoryScore`. Unit tests must reproduce the six worked examples exactly, plus a property
   test over level vectors.
4. **Selection.** Replace the class-name-prefix comparator in `AnalysisPipeline` with
   specificity-based selection per [DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) §5,
   consuming TASK-05's `specificity()`; fail fast on duplicate top specificity (DS-04).
5. **Epistemic gates.** Implement Gate B/C/E as a pipeline validation stage (inside the owned `steps`
   package, not inside `ai/**`): UNKNOWN never in `facts`, refs resolve in-snapshot, alternatives and
   counter-arguments non-empty per hypothesis, signals not marked observed, predictions unresolved at
   creation, every OPEN prediction has a plan item.
6. **Optional / deferrable.** Per-step model calls for variables, mechanism and hypotheses. This is
   *not* required for protocol conformance (the protocol is call-topology agnostic), and it needs new
   `ModelPurpose` values, new prompt directories and integrator contract work. Recommendation: keep the
   single synthesis call as the assembler through Wave 2A; revisit in a Wave 3 task.

Acceptance (mapped to the task file): each stage independently testable; durable status per stage;
no inference promoted to fact; bounded retries; no agent framework. Required tests: step tests, failed
stage and retry tests, full PostgreSQL vertical slice.

### 4.2 TASK-05 — domain strategies

**Goal**: Business/Finance/Policy guidance without touching pipeline orchestration.

Implementation impact:

| item | detail |
| --- | --- |
| interfaces to implement | `DomainAnalysisStrategy` v0.1.1 (`specificity()`, `spec()`, `guidance()` rendering); `DomainStrategySpec`, `VariableTemplate`, `MechanismTemplate`, `VerificationMetricTemplate` |
| schemas to extend | none |
| modules owning changes | `analysis/application/strategies` (+ one granted file `analysis/domain/DomainAnalysisStrategy.java`) |
| read-only dependencies | `DomainType`, `AnalysisPipeline`, shared contracts |
| must not change | `AnalysisPipeline.java` (TASK-03 owns selection), canonical contracts, generated code, migrations |

Increments:

1. **Interface freeze (on the critical path).** Land the interface plus the updated
   `TechnologyAnalysisStrategy` and `DefaultDomainStrategy` in a single commit, because adding an
   abstract method to the interface breaks compilation until both implementations are updated. Freeze
   the signatures in §6 of this plan.
2. **Dictionaries.** Implement `spec()` for AI, TECH, BUSINESS, FINANCE, MACRO, POLICY, GEOPOLITICS,
   REAL_ESTATE, ENERGY, CONSUMER, HEALTHCARE, EMPLOYMENT using
   [DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) §7. Wave 2A minimum is BUSINESS,
   FINANCE and POLICY (the TASK-05 goal); the rest may land in the same task or be explicitly mapped to
   the generic strategy with the gap documented.
3. `Wire` nothing: TASK-03 consumes `specificity()`/`guidance()`; TASK-05 must not edit the pipeline.

Acceptance (mapped to the task file): at most the three named strategies for the goal scope plus
existing strategies; specificity/fallback documented; no model/provider names; no financial claims
without sources. Required tests: matching/fallback, duplicate-specificity startup failure, guidance
rendering.

### 4.3 Where the rubric lives

The rubric must be usable by Wave 2A (analysis) and Wave 2B (research). `research` must not depend on
`analysis` (FOUNDATION.md dependency direction), so the rubric may not live in
`analysis/domain` if TASK-06 will call it. Recommended home:

`apps/api/src/main/java/com/signalframe/shared/confidence/` — a pure, dependency-free package holding
`ConfidenceRubric`, `ConfidenceInputs` and `ConfidenceScore`. `ConfidenceBand` and
`ConfidenceDimension` are **not** duplicated here: they are the generated contract records from SCH-06
and SCH-09 (`com.signalframe.contract`), consumed by the rubric. `shared` is already imported by both
modules (`JsonCodec`, `ApplicationException`) and has no module semantics. TASK-03 implements and owns
the rubric under a grant; TASK-06 consumes it read-only.

This also satisfies `ArchitectureTest` (no infrastructure/provider imports in domain/application code)
and keeps the rubric deterministic and free of Spring.

### 4.4 Wave 2A parallelism

| allowed in parallel | why |
| --- | --- |
| TASK-03 increment 1 (source assessment, stage honesty) with TASK-05 increment 1 | different files; no interface dependency yet |
| TASK-03 increment 3 (rubric) with TASK-05 increment 2 (dictionaries) | rubric needs `shared/confidence`; dictionaries need only the interface |
| sequencing constraint | TASK-03 increment 4 (selection) needs TASK-05 increment 1 merged first |
| conflict hazard | both tasks read `DomainType` and the contract; neither writes them |

## 5. Wave 2B

P1 — contract change 2B (integrator, after 2A merges): SCH-07 (`predictions` in the snapshot,
`Prediction.status += OPEN`) and SCH-11 (`Evidence.factRefs`, `Evidence.analysisId`), plus migrations
M2–M4 from the proposal §14 with reserved numbers.

### 5.1 TASK-06 — hypothesis engine

| item | detail |
| --- | --- |
| goal | evidence-driven confidence changes and durable timeline using explicit transition rules |
| interfaces to implement | `HypothesisRepository`/`HypothesisTransitionPort` in `research/domain/hypotheses` (new ports); transitions consume `shared/confidence/ConfidenceRubric` read-only |
| schemas to extend | consumes SCH-06 (status vocabulary, `confidenceBand`) and SCH-13 provenance; `HypothesisEvent` payload carries `rubricVersion` + dimension breakdown (no contract change needed) |
| modules owning changes | `research/application/hypotheses`, `research/domain/hypotheses`, `apps/web/src/components/research/HypothesisPage.tsx` |
| grant requests | one JDBC class under `infrastructure/persistence` (e.g. `JdbcHypothesisTransitionRepository.java`), new HTTP routes in `http/` |
| migrations | M3 `hypotheses.version` (optimistic concurrency) — reserve before starting |
| must not change | `analysis/**`, canonical contracts, generated code, existing migrations, `JdbcResearchRepository` unless granted |

Rules to implement ([CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) §10 Phase 2):

1. Every confidence change has a reason and names the dimension(s) that moved.
2. Confidence events are append-only; previous confidence remains traceable; history is never rewritten
   (transactional append, optimistic concurrency via `version`).
3. Status transitions are deterministic functions of evidence deltas and band movement:
   independent supporting evidence → `STRENGTHENING`; contradicting evidence → `WEAKENING`; a met
   falsification condition → `REJECTED`; decidable window closed with no data → `UNRESOLVED`;
   `CONFIRMED` only from TASK-07's verification record.
4. Legacy status values are read via the mapping in EPISTEMIC_TYPES §6 and are never rewritten in
   stored payloads.

Acceptance: reason required per change; no history rewrite; transactional timeline; previous
confidence traceable; optimistic concurrency. Tests: transition, conflict, invalid range, PostgreSQL
atomicity, timeline smoke.

### 5.2 TASK-07 — evidence and prediction

| item | detail |
| --- | --- |
| goal | link sourced evidence and verifiable dated predictions to hypotheses |
| interfaces to implement | `EvidenceRepository`, `PredictionRepository`, `VerificationService` in `research/domain/evidence` + `research/application/evidence` |
| schemas to extend | consumes SCH-07 and SCH-11 |
| modules owning changes | `research/application/evidence`, `research/domain/evidence` |
| grant requests | one JDBC class under `infrastructure/persistence` (e.g. `JdbcEvidencePredictionRepository.java`), new HTTP routes, migration M2/M4 |
| migrations | M2 (predictions CHECK accepts `OPEN`), M4 (`evidence.analysis_id`, `predictions.verified_at`) — reserve before starting |
| must not change | `analysis/**`, TASK-06's hypothesis package, canonical contracts, generated code, existing migrations |

Rules to implement:

1. Evidence writes verify the source FK, stance (`SUPPORTS`/`CONTRADICTS`/`NEUTRAL`), strength range and
   a non-empty reason; `factRefs`/`analysisId` recorded when known.
2. Predictions are created `OPEN` with `expectedBy` in the future and distinguishable verification
   criteria; prediction text is immutable after creation.
3. Verification records a result (`CONFIRMED`/`PARTIAL`/`REJECTED`/`UNRESOLVED`) with a reason and
   timestamp; idempotent on re-submission.
4. A due-unresolved query exists (`OPEN` and `expectedBy < now`), and marking a passed deadline
   `UNRESOLVED` is an explicit action, never automatic.
5. No automatic claim of truth: a `CONFIRMED` prediction updates the parent hypothesis through TASK-06's
   transition port, not by direct table writes.

Acceptance: source FK verified; strength range; due unresolved query; verification reason/timeline; no
automatic truth claim. Tests: stance transitions, due-date boundary, idempotent verification, FK and
rollback.

### 5.3 Wave 2B parallelism and serialization

TASK-06 and TASK-07 are disjoint in `research/**` (hypotheses vs evidence packages) and can run in
parallel. They collide on `infrastructure/persistence` and `http`, so:

- each task gets exactly one JDBC file and one controller file, granted by name;
- migrations are reserved as a block (M2 to TASK-07, M3 to TASK-06, M4 to TASK-07) and applied by the
  integrator;
- TASK-06 must not depend on TASK-07's tables beyond reading `evidence`/`predictions` through its
  existing read ports; TASK-07's verification calls TASK-06's transition port, which makes TASK-06's
  port interface a soft prerequisite for TASK-07's final increment.

## 6. Interface freeze register

These signatures are frozen for Wave 2. Changing one requires a protocol version bump, not a silent
adjustment.

| interface | home | owner | consumers |
| --- | --- | --- | --- |
| `AnalysisStep<PipelineContext,PipelineContext>` | `analysis/domain` (existing) | unchanged | TASK-03 |
| `DomainAnalysisStrategy { supports, specificity, spec, guidance }` | `analysis/domain` (grant) | TASK-05 | TASK-03 |
| `DomainStrategySpec`, `VariableTemplate`, `MechanismTemplate`, `VerificationMetricTemplate` | `analysis/application/strategies` | TASK-05 | TASK-03 |
| `ConfidenceRubric { ConfidenceScore score(ConfidenceInputs) }`, `ConfidenceInputs`, `ConfidenceScore` (with generated `ConfidenceDimension`, `ConfidenceBand` from SCH-09/SCH-06) | `shared/confidence` (grant) + generated `contract` | TASK-03 | TASK-06 |
| `HypothesisTransitionPort`, `HypothesisRepository` | `research/domain/hypotheses` | TASK-06 | TASK-07 |
| `EvidenceRepository`, `PredictionRepository`, `VerificationService` | `research/domain/evidence`, `research/application/evidence` | TASK-07 | HTTP |

## 7. Migrations

| number | content | wave | owner |
| --- | --- | --- | --- |
| — | no migration needed | 2A | — |
| M2 / V2 | predictions status CHECK accepts `OPEN` | 2B | integrator (TASK-07 request) |
| M3 / V3 | `hypotheses.version` | 2B | integrator (TASK-06 request) |
| M4 / V4 | `evidence.analysis_id`, `predictions.verified_at` | 2B | integrator (TASK-07 request) |

Numbers are **requests** and must be reserved with the integrator before the task starts; applied
migrations are never edited. Existing `V1__foundation.sql` stays untouched.

## 8. Dependency order

```
P0 contract 2A ─┬─> TASK-03 inc 1..3 ──┐
                └─> TASK-05 inc 1 ──────┴─> TASK-03 inc 4 (needs specificity())
                          │
                          └─> TASK-05 inc 2 (dictionaries) ──> Wave 2A exit gate
P1 contract 2B + M2..M4 (after 2A gate) ─┬─> TASK-06 (M3) ──> TASK-07 final increment
                                          └─> TASK-07 (M2, M4)
Wave 2B exit gate ──> TASK-10 integration tests
```

## 9. Gates

Every wave exit requires, on the integrated revision:

```sh
npm run contracts:check
./scripts/mvn.sh -f apps/api/pom.xml test
npm run lint
npm run typecheck
npm run test
npm run build
npm run smoke        # requires API + database + production web running
```

Wave 2A gate additionally requires the six rubric examples and the CF-08 recomputation test to pass.
Wave 2B gate additionally requires an end-to-end path: snapshot with an `OPEN` prediction → evidence
added → confidence event → prediction verified → hypothesis transitioned, with no history rewrite.

## 10. Risks and mitigations

| risk | mitigation |
| --- | --- |
| Contract application breaks compilation of mock/pipeline/tests | atomic single-change application (§3), regenerate, update the four main call sites and `labels.ts` |
| Enum extensions silently break the web build | exhaustive `Record` maps are updated in the same integrator change |
| Rubric placed in the wrong module inverts `research → analysis` | rubric lives in `shared/confidence` (§4.3) |
| TASK-03 and TASK-05 both edit `AnalysisPipeline` | selection change is TASK-03 only; TASK-05 provides `specificity()` |
| TASK-06 and TASK-07 collide in persistence/HTTP | one granted file per task, per name (§2, §5.3) |
| Migration number clash | reserve M2–M4 as a block before Wave 2B |
| `predictions.status` CHECK rejects `OPEN` at runtime | M2 lands in P1, before TASK-07 writes predictions |
| Protocol scope creep into prompts/RAG/agents | Wave 2 plan contains no retrieval, no agent framework, no UI rewrite; keep ADR-004/005 in force |
| Documentation and code drift | docs are versioned `v0.1`; any semantic change requires a protocol version bump and an ADR, not an edit in place |
| Both `demo/mock` and real providers must satisfy new gates | mock is the reference fixture; the same validator runs for both (already true today) |

## 11. Explicitly out of scope for Wave 2

- Full prompt suite for every stage (increment 6 above is deferred).
- Per-step model call topology and new `ModelPurpose` values.
- RAG, vector store, semantic retrieval, LangGraph or any agent framework.
- News ingestion changes (TASK-02 complete) and `ModelGateway` refactor (TASK-04 complete).
- Topic auto-association, Today cross-analysis aggregation, UI redesign beyond the two named components.
- Publisher reputation or any media-credit scoring.
- Probability/calibration output of any kind.

## 12. Traceability summary

| protocol area | TASK-03 | TASK-05 | TASK-06 | TASK-07 |
| --- | --- | --- | --- | --- |
| STG-02 source assessment | implements | — | — | — |
| STG-03 facts + verificationStatus | implements | — | — | — |
| STG-04 variables | implements | recommends | — | — |
| STG-05 mechanism + supportLevel | implements | recommends | — | — |
| STG-06 stakeholders | implements | recommends | — | — |
| STG-07/08 effects | implements | — | — | — |
| STG-09 hypotheses | generates | — | transitions | links evidence |
| STG-10 alternatives | generates | — | refs | — |
| STG-11 counter arguments | generates | — | — | — |
| STG-12 falsification | generates | — | evaluates | — |
| STG-13 corroborating signals | generates | recommends | — | — |
| STG-14 predictions | generates | — | — | persists/verifies |
| STG-15 verification plan | generates | recommends metrics | — | executes |
| STG-16 confidence | rubric | — | rubric at hypothesis scope | provides evidence inputs |
| Gate B/C/E | implements | — | enforces on transitions | enforces on writes |
| Gate D | implements | — | consumes | — |
