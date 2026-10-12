# Wave 3A — Snapshot Preservation

Date: 2026-10-10 · Branch `feat/wave3a-e2e` · Baseline `8734688`
Scope: the existing pipeline's snapshot extraction and information retention.
**No OpenCode Go request was made.**

Verdict: the silent losses are fixed, and a compliant reply now survives the whole
chain into PostgreSQL and the analysis API. G4 remains **FAIL / BLOCKED** — an offline
fix is not a live pass.

## 1. Root Cause

`SnapshotDraftStage` produces a draft; every downstream artifact stage re-extracts its
artifact from that draft through `SnapshotEnvelope` (the "snapshot path" of
`StageResponseReader`). That reader was written for the v0.1 snapshot shape and was
**never updated when SCH-01…SCH-13 landed**, so it hardcoded or discarded fields the
frozen contract already carries. Its own notes said so: *"SCH-06 pending"*,
*"SCH-07 pending"*, *"SCH-08 pending"* — for schema changes the contract freeze records
as applied.

Two independent causes, both affecting **MONOLITHIC and TWO_STAGE identically** (both
build a draft and both re-extract from it):

1. **Hardcoded/dropped fields in `SnapshotEnvelope`.** `CausalLink.supportLevel` was
   forced to `SPECULATIVE` and `corroboratingSignal` to `"UNKNOWN"`, which made
   `strictMechanisms` discard **every** mechanism. `Prediction` was not read at all.
   The plan's `supportingResult`/`contradictingResult`/`priority`/`deadline` were
   replaced by `NOT_DECLARED`/`UNKNOWN`/`null`, so the plan reader refused every plan
   item. Falsification conditions lost `observable`/`comparison`/`decisionBoundary`.
2. **A persistence gap that the fix exposed.** `indicators.prediction_id` is a foreign
   key to `predictions` (V1), but `JdbcAnalysisRepository.complete` never inserted the
   snapshot's predictions. Once the plan survived with a `predictionId`, the indicator
   insert failed with a `DataIntegrityViolationException`, and the job died with the
   generic `分析失败，请重试。`. This was the previously unexplained intermittent failure:
   it depended on whether the snapshot produced an indicator attached to a prediction.

## 2. Field Loss Matrix

| contract field | before | after |
| --- | --- | --- |
| `Fact.verificationStatus` | hardcoded `REPORTED` | read; unknown values fall back to `REPORTED` |
| `Variable.previousState/currentState/magnitude/whyItMatters` | hardcoded `UNKNOWN` | read |
| `Variable.factRefs` / `CausalLink.factRefs` | span-derived only | declared refs filtered to the snapshot's facts, else span-derived |
| `CausalLink.supportLevel` | hardcoded `SPECULATIVE` | read; an unsupported level is **demoted with a visible note**, never left claiming `SUPPORTED` |
| `CausalLink` (all) | dropped whenever `SPECULATIVE` | kept when it can carry its level; the one case the contract cannot express is dropped **with a note** |
| `FalsificationCondition.observable/comparison/decisionBoundary` | `NOT_DECLARED` | read from the documented `FalsificationConditionText` trailer; the "SCH-06 pending" note is now emitted **only when the parts are genuinely absent** |
| `CorroboratingSignal.whereToCheck/window` | `NOT_DECLARED` | read |
| `Indicator.whereToCheck/supportingResult/contradictingResult/priority/deadline/predictionId` | `NOT_DECLARED`/`UNKNOWN`/`null` | read; `hypothesisRef` followed through the prediction |
| `AnalysisResult.predictions` | **not read** (empty + "SCH-07 pending") | read, validated, kept when compliant; a non-compliant one is dropped **with a reason** |
| `AnalysisResult.verificationIndicators` | refused ("SCH-08 pending") | mapped to plan items and validated by the same STG-15 rules |
| `AnalysisResult.predictions` → `predictions` table | never persisted | persisted in the same transaction as the snapshot |

Nothing was added to the contract, and no second schema was designed.

## 3. Exact Code Changes

`SnapshotEnvelope.java`
- reads `verificationStatus`, the four `Variable` state fields, `supportLevel`,
  signal venue/window, the plan's structured fields and the falsification parts;
- `declaredFactRefs` / `declaredOrSpanFactRefs`: a declared ref is preferred but
  **filtered to the facts this snapshot holds**, so a dangling ref is dropped rather
  than invented or trusted;
- `predictions()`: maps the contract's predictions into the stage vocabulary;
- safe parsers `proofStatus` / `supportLevel` / `planPriority` / `predictionStatus`
  default conservatively on an unrecognized value.

`StageResponseReader.java`
- one rule set per artifact (`predictionProblem`, `planItemProblem`) used with two
  policies: a **stage envelope stays strict** (a violation fails the stage), while a
  **snapshot drops the item with an explicit UNKNOWN naming the reason**;
- the snapshot branches for `predictions` and `verificationPlan` now read the
  snapshot's own artifacts instead of returning empty with a stale note;
- mechanisms: an unsupported level is demoted and stated; a dropped speculative link
  is stated; the falsification "SCH-06 pending" note is conditional.

`JdbcAnalysisRepository.java`
- inserts the snapshot's predictions before the indicators that reference them. The
  tables already exist (V1/V2/V4), so this needs **no migration and no contract
  change**; it is a writer bug, not a schema gap.

## 4. Monolithic Compatibility

The monolithic path uses the same draft and the same reader, so it benefits from
every fix. No behaviour was relaxed: the strict envelope path is unchanged, the
epistemic guards still refuse an unsupported level, and the full suite passes without
a single expectation being rewritten — because the offline reference snapshot declares
no mechanisms, no predictions and no compliant plan item, so its conservative outcome
is unchanged.

## 5. Two-Stage Compatibility

Unchanged in shape: two stages, deterministic assembly, the same validator, the same
rubric. What changed is that the artifacts the assembler already validated are no
longer discarded on the way to persistence.

## 6. Spring + PostgreSQL Positive E2E

`SnapshotPreservationE2EIntegrationTest` — real Spring orchestration, real Flyway
V1→V4 on an isolated PostgreSQL container, a deterministic local HTTP stub as the
provider (test-sentinel credential), two-stage mode, exactly two provider requests.
Chain: news → stage 1 → stage 2 → assembler → `SnapshotDraftStage` → the 14-step
pipeline → PostgreSQL → the analysis API.

Fixture: 2 facts (one sentence each, exact UTF-16 spans), a `PLAUSIBLE` mechanism with
its fact basis, a hypothesis, an alternative, a counter-argument, a falsification
condition, a dated OPEN prediction and a plan item attached to it.

Asserted field by field: both fact ids and spans preserved; `verificationStatus`;
the variable's declared state; the mechanism kept **at `PLAUSIBLE`** with its spans;
hypothesis id; the alternative and counter-argument; the falsification condition's
free text **and** its observable/decision boundary; the prediction present, `OPEN`,
dated in the future, never verified, with its fact basis; the plan item's venue,
distinguishable results, priority, deadline and prediction link; `method=RUBRIC`;
`demo=false`; and **no note claiming a declared artifact was dropped**. The persisted
`analyses.payload` is then re-read and compared with what the API returned, and the
hypothesis is read through the Wave 2B engine (`version = 0`, one `CREATED` event).

Run three times consecutively: **3/3 pass** — stable, because the cause of the earlier
intermittency (the indicator/prediction foreign key) is now fixed rather than avoided.

## 7. Negative Tests

`SnapshotPreservationNegativeTest` (7) — each mutates exactly one thing in a valid
snapshot and asserts the conservative outcome **and** the visible note:

| case | result |
| --- | --- |
| `SUPPORTED` mechanism with no fact basis | not left as `SUPPORTED`; demotion stated |
| `PLAUSIBLE` mechanism with its fact basis | kept at `PLAUSIBLE` |
| prediction whose deadline has passed | dropped with a reason |
| prediction for a hypothesis outside the snapshot | refused, not repaired |
| plan item with indistinguishable results | dropped with a reason |
| declared fact reference outside the snapshot | resolves to nothing, never invented |
| pre-SCH snapshot (newer fields absent) | still parses, conservative defaults |

`SnapshotPreservationE2EIntegrationTest.aStageFailureLeavesNoPartialSnapshot` — stage 2
fails: the job is `FAILED`, it names the stage, `analysisId` is null, no analysis row
exists, the audit stays truthful (one `SUCCEEDED` for stage 1, at least one `FAILED`
for stage 2), and no orphan prediction is written.

Existing coverage still applies for stage-1 failure, malformed JSON, omitted arrays,
dangling fact ids and the no-persist-on-failure boundary
(`TwoStageSynthesisTest`, `TwoStagePersistenceBoundaryTest`).

## 8. Confidence Impact

**No score changed.** The rubric's inputs only move when an artifact becomes available,
and every existing expectation still passes:
- the offline reference snapshot declares no mechanisms (so D4 is unchanged),
- its indicator has no distinguishable results and no deadline, so it is still not a
  plan item (CAP-C still binds),
- its falsification conditions carry no trailer, so the "SCH-06 pending" note is still
  emitted — the note is now conditional rather than unconditional.

A snapshot that *does* declare a compliant plan releases CAP-C and raises the score —
that is the rubric working as specified on richer input, not a rubric change. The
single `ConfidenceRubric` is untouched and remains the only scoring implementation.

## 9. Full Regression Results

| check | result |
| --- | --- |
| `npm run contracts:check` | PASS — no drift |
| Backend tests | **578 tests, 0 failures, 0 errors, 5 skipped** |
| Real PostgreSQL integration | PASS — the preservation E2E and the two-stage PostgreSQL suite, on isolated containers |
| Lint / typecheck | PASS |
| Frontend tests | PASS — 26 |
| Build | PASS |
| Playwright smoke | **24/24** on this worktree's API (8090), isolated PostgreSQL (55432) and its own build (3101) |
| `git diff --check` | clean |

## 10. Known Gaps

1. **Two contract gaps are reported, not worked around.**
   - `CausalLink` carries `supportLevel` but **no `assumptions` and no
     `corroboratingSignal`**, which STG-05 requires for `PLAUSIBLE` and `SPECULATIVE`
     respectively. A snapshot mechanism therefore cannot satisfy those levels fully;
     the runtime keeps the mechanism when it can carry its level and drops it with a
     note otherwise. **Minimal contract proposal:** add optional
     `assumptions: string[]` and `corroboratingSignal: string` to `CausalLink`
     (nullable, so stored payloads stay readable) — not done here, because a shared
     contract change is the integrator's call and would need regeneration.
   - `Indicator` carries `predictionId` but **no `hypothesisRef`** (SCH-08 lists one).
     The runtime follows the link through the prediction instead of inventing it.
     **Minimal contract proposal:** add nullable `hypothesisRef: uuid` to `Indicator`.
2. **`SnapshotEnvelope` still derives some values conservatively**: `ProofStatus` for a
   mechanism's corroborating signal, `SignalStatus`, and the positional pairing of
   alternatives/counter-arguments to hypotheses when several exist (the ambiguity is
   noted rather than hidden).
3. **Predictions are now persisted by an analysis**, so the Wave 2B prediction engine
   will see them; a snapshot prediction is future-dated and `OPEN`, so it does not
   appear in the due query. This is intended, but it changes what that table contains
   for new analyses.
4. **`ModelProfile` still caps a stage at 120 s**; reasoning tokens are still not
   persisted; there is no job-cancel API.
5. **G4 is still unverified against a live provider.**

## 11. Live G4 Readiness

The preservation fix removes the reason a *successful* live analysis could have
persisted an incomplete snapshot: mechanisms, predictions, the plan and the
structured falsification parts now survive, and the indicator/prediction foreign key
is satisfiable. Two things are therefore true for the next authorized run:

- the strict live tier's existing assertions (facts, demo, audit) were never enough to
  detect this class of loss; the preservation E2E is the test that would, and it runs
  offline against a stub, so it can be re-run without spending quota;
- a live run that returns a compliant `AnalysisResult` should now persist all of it.

**G4 remains FAIL / BLOCKED.**
