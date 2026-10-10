# Wave 3A — Two-Stage Synthesis Offline PoC

Date: 2026-10-10 · Branch `feat/wave3a-e2e` · Baseline `472528c`
Scope: offline development and testing only. **No OpenCode Go request was made.**

Verdict: **Offline PoC PASS.** G4 remains **FAIL / BLOCKED** — an offline PoC passing
is not a live G4 pass, and this document does not claim otherwise.

## 1. What was built

The two-stage path replaces exactly one thing: **how the run's draft snapshot is
obtained.** `SnapshotDraftStage` is the only stage that produces a draft, and in the
new `TWO_STAGE` mode it obtains it from two smaller, separately validated model
contracts instead of one monolithic call. Everything downstream is byte-identical
to the existing `DRAFT` topology — the same 14 durable steps, the same artifact
extraction stages, Gate A–E, the deterministic rubric, the snapshot write and the
`ModelRun` audit.

```
stage 1  TwoStageFacts      facts + provenance      small schema, own prompt version
stage 2  TwoStageReasoning  analytical reasoning    small schema, own prompt version,
                                                    shown the stage-1 fact ids
assemble TwoStageAssembly   deterministic           SnapshotProjection + the single
                                                    rubric + AnalysisResultValidator
```

| file | role |
| --- | --- |
| `TwoStageModel.java` | the two intermediate contracts (handwritten stage vocabulary, **not** contract records) |
| `TwoStageSynthesisService.java` | orchestrates both stage calls, validates each, folds them into a `PipelineState` |
| `TwoStageAssembly.java` | deterministic assembly; no model call, no claim of its own |
| `AnalysisPipelineMode` | `TWO_STAGE` added; `DRAFT` stays the default |
| `SnapshotDraftStage` | dispatches on the mode |
| `AnalysisOutputSchema` | new `renderFor(roots)` so each stage prompt carries a projection of the one generated contract |
| `prompts/two-stage-facts`, `prompts/two-stage-reasoning` | stage instructions + `version.txt` |

No new pipeline, no agent framework, no second `AnalysisResult` contract, and no
change to `contracts/openapi.yaml`.

## 2. Stage 1 contract

Reply: `{"facts":[…],"unknowns":[…]}`. Each fact carries `id`, `statement`,
`reasoning`, `sourceRefs[]` and `verificationStatus`.

Validation is the **existing** `StageResponseReader.facts`, unchanged:

- every fact needs at least one source ref (EP-05);
- every ref is resolved against the exact source text: `sourceId` must be the
  analyzed source, offsets must be in range and `substring(start,end) == quote`;
- the statement must equal one of its own quoted spans;
- duplicate spans collapse to one fact;
- an empty fact set is rejected (STG-03.2) — stage 2 is not called at all;
- `CORROBORATED` is refused without an independent source (EP-04).

Stage 1 cannot predict, infer a mechanism or state a score: its schema contains
only `SourceAssessment`, `Fact`, `SourceRef` and `ClaimType`, and the prompt forbids
it. It is also handed the **deterministic** source assessment (STG-02, produced
earlier in the pipeline) instead of re-deciding it.

## 3. Stage 2 contract

Reply: one object with `summary`, `variables`, `mechanisms`, `stakeholders`,
`firstOrderEffects`, `secondOrderEffects`, `hypotheses`, `alternativeExplanations`,
`counterArguments`, `falsificationConditions`, `corroboratingSignals`,
`verificationIndicators`, `predictions`, `unknowns`.

- `facts` is **absent from the schema**, so stage 2 structurally cannot mint a fact
  or a span; every `factRef` it emits is resolved against the stage-1 id set.
- Each array is validated by the reader that already owns its rules — the same code
  the staged topology uses — so hypothesis requirements, falsification observability,
  prediction time bounds and reference resolution are not reimplemented.
- **A missing array is a schema failure, not an empty result** (PR-05): an omitted
  artifact can never read as "evaluated, nothing found".
- Artifacts are folded in **dependency order** (hypotheses first, then everything
  whose refs point at them), exactly as the staged topology does across calls; a
  legitimate reply is not rejected for a reference the same reply introduces.
- Both shapes the rest of the codebase accepts are supported: the narrow contract,
  and a complete `AnalysisResult` snapshot (which is how the offline reference
  fixture answers every purpose). In the snapshot fallback, stage-2 references that
  do not resolve against stage-1 facts are **dropped with an explicit UNKNOWN**,
  never rewritten.

## 4. Assembler

`TwoStageAssembly` performs no model call and owns no conversion logic: it folds the
validated artifacts into a `PipelineState` and then uses the components the pipeline
already uses at the end of any analysis —

1. `ConfidenceProjection.snapshotScope(state)` → rubric inputs;
2. the **single** `ConfidenceRubric` → score, band and dimensions (the model never
   supplies a number, and no advisory score is adopted);
3. `SnapshotProjection.project(...)` → the contract `AnalysisResult`;
4. the existing **`AnalysisResultValidator`** → Gate A plus the expressible parts of
   Gate B/C. A rejection is a stage failure, never a partial success.

The domain is derived exactly as the draft stage derives it, and the assembled draft
records both stage prompt versions so its own provenance is truthful.

## 5. Compatibility

| concern | handling |
| --- | --- |
| default behaviour | `analysis.pipeline.mode` defaults to `draft`; the monolithic path is untouched and still records `synthesis-v3` |
| opt-in | `analysis.pipeline.mode=two-stage` (hyphen or underscore accepted) |
| provider access | both stages go through the existing `ModelGateway` by `ModelPurpose` (`FACT_EXTRACTION`, `SYNTHESIS`); no direct HTTP call, no provider or model name in the PoC |
| budgets | per stage: the profile each purpose routes to (`ai.routes`) supplies `max-tokens`/`timeout`; per-stage `ai.two-stage.facts.*` / `ai.two-stage.reasoning.*` supply `max-attempts`, `max-repair-attempts`, `backoff-ms` |
| prompt versions | separate `version.txt` per stage, recorded in the audit and in the snapshot provenance (PR-06/PR-15) |
| audit | `StageModelClient` gives each stage its own per-attempt `ModelRun` rows — two rows, never one row pretending to be both |
| contract | no OpenAPI change; the stage schemas are projections of the generated resource, so they cannot drift |
| epistemic rules | FACT/INFERENCE/HYPOTHESIS/PREDICTION/UNKNOWN unchanged; UNKNOWN preserved end to end |
| research engine | the persisted snapshot is an ordinary immutable `Analysis`; hypotheses rows and CREATED events are created as before |
| failure | a stage failure is a job failure naming the stage; nothing partial is persisted; Mock is never silently substituted |

## 6. Test results

| suite | tests | what it proves |
| --- | --- | --- |
| `TwoStageSynthesisTest` | 16 | happy path; offset mismatch; fabricated statement; empty fact set; duplicate spans; malformed JSON; unknown fact id; foreign source; omitted stage-2 array; UNKNOWN preserved; rubric-derived confidence; per-stage audit; stage-1 failure stops before stage 2; prompt budgets and schema isolation; default mode unchanged; assembly adds no claim |
| `TwoStagePersistenceBoundaryTest` | 6 | successful two-stage run persists exactly one analysis; stage-2 failure persists nothing; stage-1 failure never reaches stage 2; monolithic mode unchanged; failure names the stage; the narrow contracts are the required shapes |
| `TwoStagePostgresIntegrationTest` | 2 | real PostgreSQL + Flyway V1→V4: one analysis row, 16 job events, rubric method persisted, both stage prompt versions in provenance, exactly two `ModelRun` rows, hypotheses/events agree with the snapshot, a second run writes a new snapshot without overwriting |

Full offline regression: `contracts:check` PASS · **565 backend tests, 0 failures,
0 errors, 5 skipped** · lint PASS · typecheck PASS · Vitest 26 PASS · build PASS ·
**smoke 24/24** against this worktree's own API (8090), isolated PostgreSQL (55432)
and its own production build (3101), in the default mode.

Acceptance gate (all ten):

| # | criterion | result |
| --- | --- | --- |
| 1 | stage 1 structured output valid | PASS |
| 2 | provenance validation effective | PASS |
| 3 | stage 2 references consistent | PASS |
| 4 | assembler output passes the full `AnalysisResult` schema | PASS |
| 5 | `ConfidenceRubric` not bypassed | PASS |
| 6 | PostgreSQL persistence consistent | PASS |
| 7 | monolithic path no regression | PASS |
| 8 | `ModelRun` audit complete per stage | PASS |
| 9 | no paid request | PASS (none made) |
| 10 | full offline regression | PASS |

## 7. Token / latency budget

Prompt sizes measured from the resources and the schema projections (character
counts are exact; token figures are ESTIMATED at ~4 chars/token and marked as such):

| | monolith | stage 1 | stage 2 | two-stage total |
| --- | --- | --- | --- | --- |
| prompt text | 1 002 + contract 3 873 | 938 | 1 294 | 2 232 |
| schema | 13 127 (19 defs) | 1 982 (4 defs) | 7 993 (12 defs) | 9 975 |
| source + context | 473 + 1 798 guidance | 473 + 320 assessment | 473 + 1 798 + ~300 facts | — |
| **prompt chars** | **20 273** | **4 413** | **12 758** | **17 171** |
| prompt tokens (ESTIMATED) | ~5 100 | ~1 100 | ~3 200 | ~4 300 |
| reply tokens (offline floor, ESTIMATED) | 1 190 | 84 | 1 037 | 1 121 |

Findings:

- **Total input is 15 % smaller than the monolith**, because each stage carries only
  its own definitions and neither carries the assembly-only ones (`AnalysisResult`
  root, `Confidence*`, `Provenance`).
- **Each request is bounded**: stage 1 is ~22 % of the monolith prompt, stage 2 ~63 %.
- **Two requests, not one**, and therefore **two reasoning phases**. Reasoning is the
  dominant term for this model, so total reasoning tokens may be *higher* than the
  monolith even though total input is lower. This is the PoC's honest cost, and it is
  the reason the architecture decision recommended reasoning-effort control first.
- Latency and completion tokens for a real provider are **UNKNOWN**: no live call was
  made, and the two live attempts recorded earlier (8192 → no content in 61.9 s;
  16384 → `TIMEOUT` at 120.3 s) were monolithic.

## 8. Known gaps

1. **Not exercised against a real provider.** Every number above is offline.
2. **The offline reference fixture cannot prove reference resolution across stages.**
   `MockModelGateway` answers each purpose with its own snapshot, so stage-2 fact
   references do not match the stage-1 ids and are conservatively dropped with an
   UNKNOWN. A real provider sees the stage-1 ids in its prompt; the offline
   persistence test therefore asserts agreement rather than a fixed count.
3. **A two-stage draft with no hypothesis fails a downstream stage** (`EP-02`), which
   is correct behaviour but means the stage-2 prompt must reliably produce at least
   one falsifiable hypothesis. The prompt asks for it; whether the model complies is
   unverified.
4. **Two reasoning phases may cost more than one** (§7). Staging bounds each request;
   it does not reduce total reasoning work.
5. **Stage budgets are inherited from the routed profile.** Per-stage `max-tokens`
   and `timeout` require pointing the two purposes at different profiles; there is no
   per-stage override inside one profile.
6. **`promptSizes()` is diagnostics only** and is not persisted.
7. **Reasoning tokens are still not persisted** (`ModelRun` is frozen), so the
   two-phase reasoning cost cannot be read back from the audit.
8. **The 120 s `ModelProfile` bound still caps each stage.** Stage 2 at ~63 % of the
   monolith prompt is likely to fit; that is an expectation, not a measurement.

## 9. Files modified

Added: `TwoStageModel.java`, `TwoStageSynthesisService.java`, `TwoStageAssembly.java`,
`prompts/two-stage-facts/{prompt.txt,version.txt}`,
`prompts/two-stage-reasoning/{prompt.txt,version.txt}`,
`TwoStageSynthesisTest.java`, `TwoStagePersistenceBoundaryTest.java`,
`TwoStagePostgresIntegrationTest.java`.

Modified: `AnalysisOutputSchema.java` (additive `renderFor`),
`AnalysisPipelineMode.java`, `SnapshotDraftStage.java`, `application.yml`,
`PipelineFixtures.java` (test wiring). ~1 000 lines of new production code and
~1 100 lines of tests.

## 10. Commit

`feat(ai): implement two-stage synthesis offline poc` — local only; not pushed, main
not merged, no tag.

## 11. Live G4 readiness

The two-stage mode is **ready to be exercised**, not proven:

- it is opt-in, so switching it on cannot affect the default path;
- the monolithic path remains available as the fallback and is regression-tested;
- both stages are separately budgeted and separately audited;
- no credential, provider or model name is compiled in;
- nothing about it changes the acceptance rules — the same validator, the same
  provenance checks, the same rubric and the same persistence gate apply.

What is still missing for G4: an authorized live run. The recommended order remains
**reasoning-effort control first, two-stage second** (Architecture Decision Report
§8), because a reasoning model that spends its whole completion budget before
emitting JSON will not be fixed by splitting the task — but a split does bound each
request, and this PoC makes that option real, tested and reversible.

**G4 stays FAIL / BLOCKED.**
