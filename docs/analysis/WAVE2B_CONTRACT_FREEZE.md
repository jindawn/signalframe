# Wave 2B Shared Contract Freeze (P1)

Status: **Frozen.** Owner: integrator. Branch: `feat/wave2b-contract`.
Baseline: `wave2a-complete` / `931c4d7`.
Contracts: [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) · [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) · [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) · [proposals/SCHEMA_PROPOSAL_V0_2.md](proposals/SCHEMA_PROPOSAL_V0_2.md) · [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md)

This document freezes the shared surface that TASK-06 (hypothesis engine) and TASK-07
(evidence/prediction) implement against. It does **not** redesign the analysis protocol:
`ANALYSIS_PROTOCOL_V0_1`, `EPISTEMIC_TYPES` and `CONFIDENCE_MODEL_V0_1` are unchanged, and no
meaning of `FACT`, `INFERENCE`, `HYPOTHESIS`, `PREDICTION` or `UNKNOWN` is altered.

## 0. The P0 gap this freeze closes (read this first)

At the `wave2a-complete` baseline, **the Wave 2A contract change had never been applied**.
`contracts/openapi.yaml` had been modified by exactly one commit in repository history
(`3204c12 chore: establish SignalFrame foundation`), so SCH-01…SCH-06 and SCH-08…SCH-13 were
absent from the canonical contract, even though Wave 2A code was already written against their
*semantics*. Wave 2A had compensated with an analysis-private protocol model
(`analysis/application/steps/ProtocolModel.java`) and an analysis-private rubric
(`analysis/application/steps/ConfidenceRubric.java`), and it rendered protocol values the v0.1
schema could not carry into free-text "protocol trailers".

That state made a correct Wave 2B freeze impossible: the transition vocabulary
(`STRENGTHENING`/`WEAKENING`/`CONFIRMED`/`UNRESOLVED`) and `ConfidenceBand` did not exist, and
`research` reusing the deterministic rubric would have had to import `analysis`, inverting the
module direction. P1 therefore applies **SCH-01…SCH-13 in one integrator change** plus the
Wave 2B migrations, migrations verification and the port freeze. The migration-integrity and
gate evidence is in §3 and §9.

## 1. OpenAPI changes

`contracts/openapi.yaml` is the source of truth; `npm run contracts` regenerates
`apps/api/src/main/java/com/signalframe/contract/*.java`, `apps/web/src/lib/api.generated.ts`
and both `analysis-result.schema.json` copies. Generated code is never hand-edited.
`npm run contracts:check` is the drift gate.

### 1.1 Applied proposals

| id | schema | change |
| --- | --- | --- |
| SCH-01 | `SourceAssessment` (new) + `AnalysisResult.sourceAssessment` | optional, nullable |
| SCH-02 | `Fact.verificationStatus` | optional enum `REPORTED｜CORROBORATED｜DISPUTED`; absent reads `REPORTED` |
| SCH-03 | `Statement.factRefs/derivedFromRefs/targetHypothesisRef/rivalsHypothesisRef`, `StakeholderImpact.factRefs` | optional |
| SCH-04 | `Variable.previousState/currentState/magnitude/whyItMatters/factRefs` | optional |
| SCH-05 | `ClaimType += UNKNOWN` | normative EP-06 vocabulary |
| SCH-06 | `ConfidenceBand` (new), `HypothesisStatus` (new), `Hypothesis.status` → `HypothesisStatus`, `Hypothesis` refs/`assumptions`/`falsificationConditions`/`confidenceBand` | protocol status vocabulary; legacy members retained |
| SCH-07 | `AnalysisResult.predictions`, `Prediction.status += OPEN`, `Prediction.observable/whereToCheck/basisFactRefs` | predictions become part of the snapshot |
| SCH-08 | `Indicator.whereToCheck/supportingResult/contradictingResult/priority/deadline/hypothesisRef` | verification plan fields |
| SCH-09 | `ConfidenceDimension` (new), `ConfidenceMethod` (new), `ConfidenceAssessment.band/method/rubricVersion/dimensions/advisoryScore` | rubric state in the snapshot |
| SCH-10 | `CorroboratingSignal` (new); `AnalysisResult.corroboratingSignals` item `$ref` changes | typed expected observables |
| SCH-11 | `Evidence.factRefs`, `Evidence.analysisId` | traceability |
| SCH-12 | `CausalLink.supportLevel/factRefs` | `SUPPORTED｜PLAUSIBLE｜SPECULATIVE` |
| SCH-13 | `Provenance` (new) + `AnalysisResult.protocolVersion/provenance` | PR-06/PR-16 versioning |

All new fields on existing schemas are optional and nullable where absence is meaningful, so
historical `analyses.payload` rows remain readable and are never rewritten (PR-17).

### 1.2 Integrator extensions beyond SCH-01…13

These are additions the two tasks provably need and the proposal did not cover. Each is additive
and nullable; none changes an epistemic type's meaning.

| addition | why it is required |
| --- | --- |
| `HypothesisStatus` as a **named** enum (member list exactly SCH-06's) referenced by `Hypothesis.status`, `HypothesisEvent.status/previousStatus`, `HypothesisTransitionResult.status/previousStatus` | with an inline enum the generated Java field is a `String` with a `@Pattern`, so the frozen port could not be typed on the protocol vocabulary. Same members, same meaning. |
| `HypothesisEvent.eventType += STATUS_CHANGED`, `+= previousStatus`, `+= status` (nullable) | the Wave 2B exit gate is "→ hypothesis **transitioned**"; a timeline that can only record confidence movement cannot show a status transition, and `CONFIRMED` must be distinguishable from a confidence change. |
| `HypothesisDetail.version` (`int64`, required) | the optimistic-lock token. A client cannot send `expectedVersion` if no read endpoint exposes the current version. Populated from the `hypotheses.version` column (V3). |
| `Prediction.type` stays an inline enum | unchanged from v0.1; generated as a `String` and always written as `PREDICTION`. Not worth a contract change. |

### 1.3 Wave 2B API schemas (new)

`HypothesisTransitionCommand`, `HypothesisTransitionResult`, `HypothesisTimeline`,
`EvidenceCreateRequest`, `PredictionCreateRequest`, `PredictionVerificationCommand`,
`PredictionVerificationResult`, `DuePredictions`, plus enums `HypothesisTransitionCause` and
`VerificationOutcome`.

`HypothesisTransitionCommand` and `HypothesisTransitionResult` are deliberately the *same*
records the port uses, so HTTP, the port and the persisted event share one immutable vocabulary
with no second DTO to drift (AGENTS.md: `contract` records are generated stable shared
vocabulary).

## 2. HTTP route allocation

`ResearchController` keeps its four read routes unchanged. New routes are allocated, never added
ad hoc by a task.

| method | path | owner | success | errors |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/hypotheses/{id}/transitions` | TASK-06 `HypothesisCommandController` | `200` `HypothesisTransitionResult` | 400, 404, 409, 422, 503 |
| `GET` | `/api/v1/hypotheses/{id}/timeline` | TASK-06 `HypothesisCommandController` | `200` `HypothesisTimeline` | 400, 404, 409, 503 |
| `POST` | `/api/v1/hypotheses/{id}/evidence` | TASK-07 `EvidencePredictionController` | `201` `Evidence` | 400, 404, 409, 422, 503 |
| `POST` | `/api/v1/hypotheses/{id}/predictions` | TASK-07 `EvidencePredictionController` | `201` `Prediction` | 400, 404, 409, 422, 503 |
| `GET` | `/api/v1/predictions/due` | TASK-07 `EvidencePredictionController` | `200` `DuePredictions` | 400, 404, 409, 503 |
| `POST` | `/api/v1/predictions/{id}/verification` | TASK-07 `EvidencePredictionController` | `200` `PredictionVerificationResult` | 400, 404, 409, 422, 503 |

Collision analysis: the existing routes are `GET /hypotheses`, `GET /hypotheses/{id}`,
`GET /topics`, `GET /topics/{id}`. Every new route adds a distinct sub-segment under a path
variable, and `GET /predictions/due` is a literal with no competing `GET /predictions/{id}`, so
no route is ambiguous. `{id}` is always a UUID; a malformed one is a `400`.

### 2.1 Error contract

All errors are `ApiError{code,message,requestId}` (unchanged shape).

| status | code | when |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | structural: missing/blank required field, out-of-range strength, malformed UUID. Produced by the existing `ErrorAdvice` handlers. |
| `404` | `NOT_FOUND` | unknown hypothesis, prediction, evidence or source id. `ApplicationException.missing()`. |
| `409` | `VERSION_CONFLICT` | `expectedVersion` ≠ stored `hypotheses.version`; nothing is written. |
| `409` | `IDEMPOTENCY_MISMATCH` | an `operationId` already exists with a different request fingerprint. |
| `422` | `UNPROCESSABLE_TRANSITION` | well-formed but not allowed: transition impossible from the current status, `CONFIRMED` asserted with no verification record, `expectedBy` not in the future, verification criteria not distinguishable. |
| `503` | `UNAVAILABLE` | as today. |

The existing `ErrorAdvice` already maps `ApplicationException(status, code, message)` to that
status, so `404`/`409`/`422` need no new handler and no change to the error envelope.

### 2.2 Idempotency behaviour (normative)

`operationId` is the idempotency key of a transition and of a verification.

- First application: perform the transition, persist `operationId` with a fingerprint of the
  request, return the result with `applied = true`.
- Replay with the same `operationId` and the same fingerprint: **no** state change, **no** second
  event, HTTP `200`, the originally recorded result with `applied = false`.
- Replay with the same `operationId` and a different fingerprint: `409`
  `IDEMPOTENCY_MISMATCH`, never a silent second application.
- `POST /predictions/{id}/verification` returns `PredictionVerificationResult`, so a replay
  returns the same `verificationId` and the same `hypothesisTransition`.

## 3. Flyway migrations

New, immutable, append-only. `V1__foundation.sql` is untouched.

| file | content | requester |
| --- | --- | --- |
| `V2__prediction_open_status.sql` | drops and re-adds `predictions_status_check` accepting `('OPEN','CONFIRMED','REJECTED','PARTIAL','UNRESOLVED')`; adds partial index `predictions_open_due ON predictions(expected_by) WHERE status = 'OPEN'` | TASK-07 |
| `V3__hypothesis_version.sql` | `ALTER TABLE hypotheses ADD COLUMN version integer NOT NULL DEFAULT 0` | TASK-06 |
| `V4__evidence_prediction_traceability.sql` | `evidence.analysis_id uuid NULL REFERENCES analyses(id)`; `predictions.verified_at timestamptz NULL` | TASK-07 |

Notes.

- The CHECK constraint name was **read back from the live catalogue** on a database migrated to
  V1: PostgreSQL named the inline constraint `predictions_status_check`. The migration depends on
  that exact name and therefore fails loudly if the catalogue ever differs.
- V1's `predictions_unresolved_due` partial index covers `status = 'UNRESOLVED'` only and cannot
  serve the new due query, so `predictions_open_due` is **added alongside** it: both are
  legitimately small due-date subsets, and the "deadline passed with no data → UNRESOLVED" path
  still needs the old one.
- No backfill anywhere. Existing rows read `version = 0`, `analysis_id = NULL` and
  `verified_at = NULL`, which is exactly "unknown" (PR-17).

Verification: `MigrationUpgradeIntegrationTest` starts a real PostgreSQL 17.6 container, migrates
to V1, inserts legacy data, migrates to V4, and asserts that every legacy value is unchanged, that
`hypotheses.version` is `NOT NULL DEFAULT 0` and reads 0 on the legacy row, that the widened CHECK
accepts `OPEN` and still rejects an unknown status, that `evidence.analysis_id` is nullable and
FK-enforced, that `predictions.verified_at` accepts a timestamp, that the `OPEN` due query uses
`predictions_open_due`, that both partial indexes exist, and that Flyway applied versions 1→4
successfully.

## 4. Frozen port: `HypothesisTransitionPort`

Home: `apps/api/src/main/java/com/signalframe/research/domain/hypotheses/`.
Owner: **TASK-06** (port and implementation). Consumer: **TASK-07**.

```java
public interface HypothesisTransitionPort {
  HypothesisTransitionResult transition(HypothesisTransitionCommand command);
}
```

Command — the generated contract record, built through the frozen factories in
`HypothesisTransitions`:

```java
public record HypothesisTransitionCommand(
    UUID operationId,                       // idempotency key, required
    Long expectedVersion,                   // optimistic lock, required, >= 0
    HypothesisTransitionCause cause,        // EVIDENCE_ADDED | EVIDENCE_CHANGED
                                            // | PREDICTION_VERIFIED | FALSIFICATION_OBSERVED
                                            // | DEADLINE_PASSED
    String reason,                          // required, non-blank
    UUID evidenceRef,                       // nullable; required for evidence causes
    UUID predictionRef,                     // nullable; required for PREDICTION_VERIFIED
    VerificationOutcome verificationOutcome // nullable; CONFIRMED|PARTIAL|REJECTED|UNRESOLVED
) {}
```

Result:

```java
public record HypothesisTransitionResult(
    UUID operationId, UUID hypothesisId, Boolean applied,
    Long previousVersion, Long version,
    HypothesisStatus previousStatus, HypothesisStatus status,
    Integer previousConfidence, Integer confidence,
    ConfidenceBand confidenceBand, String rubricVersion,
    UUID eventId, Instant occurredAt
) {}
```

Factories (TASK-07's entry points):

```java
HypothesisTransitions.evidenceAdded(operationId, hypothesisId, expectedVersion, evidenceId, reason)
HypothesisTransitions.evidenceChanged(operationId, hypothesisId, expectedVersion, evidenceId, reason)
HypothesisTransitions.predictionVerified(operationId, hypothesisId, expectedVersion, predictionId, outcome, reason)
```

### 4.1 Invariants the implementation must honour

1. **Reason required.** A confidence number never moves without a non-blank `reason` and the
   dimension(s) that moved (CONFIDENCE_MODEL §10 Phase 2).
2. **Confidence is recomputed, never chosen.** The new score comes from
   `com.signalframe.shared.confidence.ConfidenceRubric` scored at hypothesis scope over the
   hypothesis's own artifacts. No model number is ever stored as the score (EP-09/CF-01).
3. **Append-only timeline.** The result carries the `eventId` of the appended event; stored events
   and previous confidence values are never rewritten.
4. **Optimistic concurrency.** `expectedVersion` must equal the stored version; on success the
   stored version increases by exactly one. A mismatch writes nothing and reports a conflict.
5. **Idempotency** exactly as §2.2.
6. **No automatic truth.** A `CONFIRMED` prediction never sets the hypothesis to `CONFIRMED` by
   itself. The transition records the verification outcome; status is a deterministic function of
   evidence deltas and band movement (EPISTEMIC_TYPES §2.3: `CONFIRMED` requires a verification
   record and still never means "true").
7. **TASK-07 cannot bypass the port.** The interface exposes no way to write
   `hypotheses.confidence`, `hypotheses.status` or `hypotheses.version`. TASK-07 submits commands
   and reads results.

### 4.2 Transaction boundary

Status change, confidence change, version bump, timeline append and idempotency-record insert
commit together or not at all. A caller that sees a failure must observe an unchanged hypothesis,
version and timeline. A lost update between two concurrent evidence submissions must surface as a
`409`, never as a silently overwritten confidence event.

## 5. Shared objects and field semantics

### 5.1 Rubric relocation (plan §4.3)

The deterministic rubric now lives in `com.signalframe.shared.confidence` and consumes **only**
generated contract records:

`ConfidenceRubric`, `ConfidenceScore`, `ConfidenceInputs`, `DeterministicConfidenceRubric`,
`RubricDimensions`, `ConfidenceBands`, `FalsificationConditionText`.

`ConfidenceBand`, `ConfidenceDimension` and `ConfidenceMethod` are the generated contract records
(SCH-06/SCH-09), not duplicates. `RubricDimensions` holds the profile weights (which are a
property of the rubric version, not of a stored row).

Consequences:

- `research` and `analysis` both consume the rubric with **no** `research → analysis` dependency
  and no cycle. `shared` still depends only on `contract`.
- The rubric is Spring-free; the bean is declared by the consuming module in
  `analysis/application/AnalysisConfiguration.java`.
- The former analysis-private rubric types and the free-text "protocol trailer" are gone. Protocol
  values are now written to their real schema fields.

### 5.2 Field semantics that tasks must not reinterpret

| field | meaning |
| --- | --- |
| `Fact.verificationStatus` | source-report status, never objective truth; `CORROBORATED` needs an independent source and is unreachable in the single-source MVP |
| `Hypothesis.status` | `OPEN｜STRENGTHENING｜WEAKENING｜CONFIRMED｜REJECTED｜UNRESOLVED`; legacy `SUPPORTED→STRENGTHENING`, `CHALLENGED→WEAKENING`, `ARCHIVED→UNRESOLVED` are **read** and never rewritten (EPISTEMIC_TYPES §6) |
| `Hypothesis.confidenceBand` | band of `confidence` from the deterministic rubric, never a probability |
| `Prediction.status` | `OPEN` at creation; `CONFIRMED｜PARTIAL｜REJECTED｜UNRESOLVED` only from a verification record |
| `Prediction.hypothesisId` | the **local** hypothesis id of the same snapshot (PR-01) |
| `Evidence.stance` | `SUPPORTS｜CONTRADICTS｜NEUTRAL`; evidence is *already existing* material, never a corroborating signal (§5.2) |
| `CorroboratingSignal.status` | `NOT_OBSERVED` at creation; an observed signal becomes evidence in a later snapshot, never by being described |
| `ConfidenceAssessment.advisoryScore` | model-supplied diagnostics only; no reader may use it as the score |
| `Provenance` | protocol/rubric/strategy/prompt versions; no prompt text (AGENTS.md) |

### 5.3 Representational limitation: falsification conditions

SCH-06 nests a falsification condition as a `Statement`, which has no structured
`observable`/`comparison`/`decisionBoundary` fields, although STG-12 makes all three mandatory. The
freeze keeps the frozen schema and carries the three parts in the statement's `reasoning` through
`FalsificationConditionText` in a fixed, machine-readable form. The rubric reads them back
deterministically; D5 level 3 would otherwise be unreachable. Promoting
`FalsificationCondition` to a first-class schema is a recommended Wave 2C follow-up, not a P1
change.

## 6. Consistency and transaction requirements

- A stored snapshot is never rewritten; corrections are new snapshots or append-only research
  events (PR-17).
- Every confidence change is a timeline event with a reason, and previous confidence stays
  traceable.
- Evidence writes verify the source FK, the stance, the strength range and a non-empty reason
  before any hypothesis transition is attempted.
- Verification is idempotent (§2.2) and records a reason and a timestamp.
- The due-unresolved query is `status = 'OPEN' AND expected_by < now()`. Marking a passed deadline
  `UNRESOLVED` is an explicit action or an explicit transition — never an automatic job.
- A prediction's text is immutable after creation; outcomes are verification records.

## 7. File ownership

One writer per path. "Grant" means the integrator has widened the task's Owned Paths; it is not
permission to edit anything else.

| owner | paths |
| --- | --- |
| **TASK-06** | `research/application/hypotheses/**`, `research/domain/hypotheses/**`, `apps/api/src/test/java/com/signalframe/research/hypotheses/**`, `apps/web/src/components/research/HypothesisPage.tsx` |
| **TASK-06 grant** | `JdbcHypothesisTransitionRepository.java` (under `infrastructure/persistence/`), `HypothesisCommandController.java` (under `http/`) |
| **TASK-07** | `research/application/evidence/**`, `research/domain/evidence/**`, `apps/api/src/test/java/com/signalframe/research/evidence/**` |
| **TASK-07 grant** | `JdbcEvidencePredictionRepository.java` (under `infrastructure/persistence/`), `EvidencePredictionController.java` (under `http/`) |
| **integrator only** | `contracts/openapi.yaml`, generated Java/TS/schema artifacts, `db/migration/**`, `scripts/**`, `JdbcResearchRepository.java`, `JdbcAnalysisRepository.java`, `AnalysisPipeline*`, `infrastructure/ai/providers/**`, `http/ResearchController.java`, `http/ErrorAdvice.java`, root build config |
| **both tasks forbidden** | `contracts/openapi.yaml`, generated code, Flyway migrations, the pre-existing `JdbcResearchRepository`, the analysis pipeline, `ModelGateway` |

Integration Grant Requests (do **not** self-grant; request through the integrator):

- TASK-06: a read method for the current `hypotheses.version` on a non-granted port if the granted
  repository cannot answer it. `HypothesisDetail.version` already covers the HTTP read path.
- TASK-07: none identified. Evidence/prediction writes need their own JDBC class and controller,
  both granted by name.
- Either task needing a change to an existing shared file must stop and request it; expanding
  ownership privately is what this table exists to prevent.

## 8. TASK-06 / TASK-07 dependency order

```
P1 freeze (this change) ─┬─> TASK-06: port impl, transitions, timeline, V3 consumed
                         └─> TASK-07: evidence/prediction writes, V2/V4 consumed
                                          │
                                          └─> TASK-07 verification calls TASK-06's port
                                              (soft prerequisite: the port signature is frozen now,
                                               the implementation lands with TASK-06)
```

- TASK-06 and TASK-07 can start in parallel: their `research/**` packages, JDBC classes and
  controllers are disjoint.
- TASK-07's final increment (prediction verification driving a hypothesis transition) is blocked
  until TASK-06's port implementation exists. TASK-07 can build and test evidence association,
  prediction creation and the due query before that.
- Neither task may modify the other's package, the contract, migrations or generated code.

## 9. Integration Gate (acceptance for this freeze, and for Wave 2B)

On the integrated revision:

```sh
npm run contracts:check
./scripts/mvn.sh -B -f apps/api/pom.xml test
npm run lint
npm run typecheck
npm run test
npm run build
git diff --check
```

Requirements:

1. P1 merges independently: the system compiles, starts, and the existing news → analysis →
   research chain still runs. Achieved — the whole suite is green on this branch (§10).
2. `MigrationUpgradeIntegrationTest` proves V2/V3/V4 apply to a database that already holds V1
   data without rewriting it, and that the `OPEN` due query works.
3. `HypothesisTransitionsTest` freezes the port shape (one operation, command/result components,
   factory guards).
4. The six worked rubric examples and the CF-08 recomputation test still pass after the rubric
   moved to `shared/confidence`.
5. No historical `analyses.payload` row is rewritten; no `V1__foundation.sql` byte changes.
6. Wave 2B exit additionally requires the end-to-end path from the implementation plan §9:
   snapshot with an `OPEN` prediction → evidence added → confidence event → prediction verified →
   hypothesis transitioned, with no history rewrite and a `409` on a stale version.

## 10. Compatibility

| concern | handling |
| --- | --- |
| historical `analyses.payload` | read as-is; absent new fields mean `MODEL_JUDGMENT`, `REPORTED`, `SPECULATIVE`, no refs, protocol version unknown. No backfill. |
| legacy hypothesis statuses | retained in `HypothesisStatus` and readable; the protocol reading is mapped at read time and stored values are never rewritten |
| generated Java | new fields are nullable record components; Jackson tolerates absence |
| TypeScript | new optional properties; the only compile breaks are exhaustive `Record` maps, updated in this change (`labels.ts`) |
| rollback | revert the contract change and regenerated artifacts; the V2–V4 migrations are additive and stay applied |
| epistemic semantics | FACT/INFERENCE/HYPOTHESIS/PREDICTION/UNKNOWN unchanged; `ClaimType.UNKNOWN` becomes expressible rather than being a mislabelled INFERENCE |

## 11. Known limitations and follow-ups (state them, do not hide them)

1. **Synthesis prompt not revised.** `prompts/synthesis/prompt.txt` still says "Use empty arrays
   where reasoning is unknown", which now contradicts PR-05, and it does not describe the new
   optional artifacts (SCH-01/07/13). It is a TASK-03-owned path; revising it requires bumping
   `synthesis-v1` → `synthesis-v2` and the audit rows that assert the version. Recorded here as
   a required follow-up rather than changed silently in an integrator commit.
2. **`Provenance.modelRunIds` is empty.** `PipelineState` does not carry model run ids; the audit
   rows exist in `model_runs` keyed by job. Linking them is a TASK-03 follow-up.
3. **The reference fixture does not emit predictions yet.**
   `StageResponseReader.predictions()` still falls back to an UNKNOWN item whose text says
   predictions are not part of the snapshot schema. The contract now carries them, so the
   fallback text and the fixture are stale: TASK-03 should populate `AnalysisResult.predictions`
   in `MockModelGateway` and map it in `SnapshotEnvelope`. Until then the demo path keeps working
   but produces no dated prediction.
4. **`SnapshotEnvelope` remains conservative.** It still derives several protocol values from the
   v0.1 shape (for example `verificationStatus = REPORTED`, `previousState = UNKNOWN`) instead of
   reading the now-available contract fields. The stage-path (`StageResponseReader` envelopes)
   is unaffected. Tightening it is TASK-03 work and cannot break the gate in the meantime.
5. **Falsification-condition representation** (§5.3) — schema gap, workaround documented.
6. **Prediction `status` is a `String`** in generated Java (inline enum in SCH-07), so it is not
   compiler-checked the way `Hypothesis.status` now is. The validator checks the value.
7. **No `verification` table.** Verification outcomes and their reasons live in the prediction
   payload plus `predictions.verified_at`; the append-only hypothesis timeline is the audit
   trail. A dedicated verification-event table is not proposed by the frozen schema.
