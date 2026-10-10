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

---

# Hardening round (offline)

Date: 2026-10-10 · Baseline `098c12b` · No OpenCode Go request was made.

## 12. Cross-stage positive E2E

`TwoStageSynthesisTest.aStageOneFactIsCitedAcrossEveryStageTwoArtifactAndAssembled`
is the deterministic proof that valid data travels through both stages instead of
passing by UNKNOWN downgrade:

```
stage 1  one FACT, statement == the exact source span, offsets 0..length
stage 2  variable, mechanism, hypothesis, falsification condition, plan item and
         prediction, each citing FACT_ID (and carrying the source span)
assemble SnapshotProjection + the single rubric + AnalysisResultValidator
assert   fact id preserved; every factRef resolves; PLAUSIBLE support level kept;
         expectedBy in the future; RUBRIC method with rubricVersion 0.1;
         both stage prompt versions in provenance; two ModelRun rows
```

The fixture is built from the typed stage contracts (`ProtocolModel` records), so
the field names are the ones the readers expect — the earlier hand-escaped JSON
fixtures were the source of several false failures and are gone.

## 13. Stage budgets

| bound | where | default |
| --- | --- | --- |
| stage 1 `max-tokens` / `timeout` | `ai.two-stage.facts.max-tokens` / `.timeout` | `0` = inherit the routed profile |
| stage 2 `max-tokens` / `timeout` | `ai.two-stage.reasoning.max-tokens` / `.timeout` | `0` = inherit the routed profile |
| stage 1 retry / repair | `ai.two-stage.facts.max-attempts` / `.max-repair-attempts` | `ai.retry.*` |
| stage 2 retry / repair | `ai.two-stage.reasoning.max-attempts` / `.max-repair-attempts` | `ai.retry.*` |
| per-analysis provider requests | `ai.two-stage.max-provider-calls` | `4` |
| per-analysis deadline | `ai.two-stage.total-timeout-ms` | `0` = no total budget |

A stage budget is applied to the profile that stage routes to by purpose
(`FACT_EXTRACTION`, `SYNTHESIS`), so routing is unchanged and nothing is hardcoded.
An out-of-contract value fails at **startup** rather than during a paid call: the
bounds mirror the `ModelProfile` contract (`max-tokens` 128..32000, `timeout`
1..120). `StageModelClient` gained an explicit-profile `invoke` overload; the
purpose-routed path is untouched.

**Max provider calls.** `ProviderCallBudget` is a `ModelGateway` decorator applied
per analysis, so it covers every attempt of both stages including the repair round.
Exceeding it is a non-retryable configuration failure, which stops the retry loop
instead of spending more of a metered budget. Measured behaviour: with a ceiling of
one and one repair round allowed, stage 1's repair call is refused and the job
names the ceiling.

**Total timeout and cancellation.** When `total-timeout-ms` is set, the deadline is
checked before stage 2 and before assembly, so a spent budget cannot buy another
request. Interruption is checked at the same points; it is the only cancellation the
in-process queue supports, and there is no job-cancel API to propagate further.

## 14. Stage 2 complexity

Thirteen arrays plus a summary. Measured on the offline reference snapshot
(ESTIMATED tokens at ~4 chars/token):

| output | offline size | mandatory? | may be empty? |
| --- | --- | --- | --- |
| `summary` | 6 | yes, non-blank | no |
| `hypotheses` | 250 | **effectively yes** — a snapshot with none fails `AlternativeExplanation` downstream (`EP-02`, measured) | no |
| `verificationIndicators` | 133 | no | yes |
| `variables` | 99 | no | yes |
| `firstOrderEffects` / `secondOrderEffects` | 79 each | no | yes |
| `counterArguments` | 79 | no | yes |
| `unknowns` | 78 | no | **should not be** for a thin input (PR-05) |
| `alternativeExplanations` | 79 | yes per emitted hypothesis | only with none |
| `falsificationConditions` | 78 | yes per emitted hypothesis | only with none |
| `mechanisms`, `stakeholders`, `corroboratingSignals`, `predictions` | 0 in the fixture | no | yes |

Findings:

1. **Hypotheses dominate** (≈250 of ≈1 121 output tokens) and are the only array
   whose emptiness breaks the pipeline, so the stage-2 prompt must reliably produce
   one. No other array is load-bearing.
2. **The prompt contradicted the schema.** It said "never state a confidence score"
   while every item must carry `confidence`. Fixed in the `two-stage-reasoning-v2`
   text: item-level `confidence` is required advisory diagnostics on the 0–100
   judgment scale, while `confidenceAssessment` is computed deterministically and
   any model number there is discarded. This is a prompt clarification only — no
   semantics changed.
3. **Items must carry the source span, not only the fact id.** Measured: the
   assembled draft is re-extracted by the same downstream stages a monolithic draft
   is, and that path links an item to its fact by **span overlap**, ignoring
   `factRefs`. A reply that gives only fact ids loses its evidence links. The prompt
   now asks for both; the fixture carries both.
4. **Deterministically computable fields are already optional in the stage
   vocabulary**: `id`s default, `status` defaults to `OPEN`/`NOT_OBSERVED`,
   `previousState`/`magnitude`/`verificationStatus` default. Nothing is padded to
   satisfy the schema, and no placeholder is generated to fill an array.
5. **Item-level `confidence` is the one model-supplied number that is persisted**
   (`SnapshotProjection` copies it). It is advisory by protocol (CONFIDENCE_MODEL
   §8) and is never summed, but a future increment could compute it from the item
   profile instead of asking the model — deliberately **not** done here, because it
   would change the monolithic path too.
6. **No redundancy worth removing.** The prompt's output-contract text repeats the
   array list the schema already carries (~400 chars of ~12 700). Removing it would
   trade the framing that fixed the wrapper failure class for ~3 % of the prompt.

## 15. Full test results

| check | result |
| --- | --- |
| Contract check | PASS — no drift |
| Backend tests | **569 tests, 0 failures, 0 errors, 5 skipped** |
| PostgreSQL integration (two-stage) | PASS — 2 tests on real PostgreSQL + Flyway V1→V4 |
| Lint | PASS |
| Typecheck | PASS |
| Frontend tests | PASS — 26 |
| Build | PASS |
| Smoke | **24/24** on this worktree's API (8090), isolated PostgreSQL (55432) and its own build (3101) |

## 16. Files modified

Modified: `TwoStageSynthesisService.java` (budgets, call ceiling, deadline,
cancellation, envelope-key fixes, cross-stage ordering), `StageModelClient.java`
(explicit-profile overload, redacted failure detail in the log and the failure),
`StageFailure.java` (bounded, redacted diagnostic in the operator copy),
`AnalysisJobService.java` (unexpected failures are now diagnosable),
`application.yml`, `prompts/two-stage-reasoning/{prompt.txt,version.txt}`,
`OpenAiStubServer.java` (public, reusable), and the two-stage test suites.

## 17. Live Stage 1 readiness

Stage 1 is the smallest request in the system (≈4 400 characters, a 4-definition
schema, a reply of one small array) and it is fully validated by the existing facts
reader. It is ready to be exercised live as soon as a call is authorized; the
budgets above let a deployment keep it single-shot while stage 2 keeps a repair
round.

## 18. Known gaps

1. **The draft re-extraction is stale relative to the frozen contract, and it is the
   real blocker for a complete cross-stage chain.** Measured with the rich fixture:
   `SnapshotEnvelope` hardcodes every mechanism to `SPECULATIVE` (so
   `strictMechanisms` drops all of them) and reads neither `predictions` nor the
   structured plan fields, emitting instead
   `Predictions are not part of the v0.1 snapshot schema (SCH-07 pending)` and
   `The v0.1 snapshot's indicators cannot serve as verification plan items
   (SCH-08 pending)` — for SCH-07/SCH-08/SCH-06, which the contract freeze already
   records as applied. **This affects the monolithic path identically**: a live
   model that returns a perfect snapshot would still lose its mechanisms, plan and
   predictions before persistence, and the strict live tier would not notice
   because it asserts facts, demo and audit only. Fixing it changes the monolithic
   path's artifacts and therefore the rubric's caps and scores, so it belongs in
   its own change with its own regression round.
2. **A real-Spring run of the rich fixture was intermittent** (one run completed and
   produced the conservative-reader UNKNOWNs; a later run failed with the generic
   `分析失败，请重试。` and no application log line, while the same fixture through the
   in-memory pipeline always succeeds). The flaky test was removed rather than
   committed; the real-PostgreSQL proof uses the reference provider, which is
   deterministic. This needs a dedicated investigation.
3. **The `ModelProfile` bound still caps each stage at 120 s.**
4. **Reasoning tokens are still not persisted**, so a two-phase reasoning cost
   cannot be read back from the audit.
5. **No job-cancel API exists**, so cancellation propagation stops at thread
   interruption.
6. **Two stages mean two reasoning phases**; the split bounds each request but does
   not reduce total reasoning work.

**G4 remains FAIL / BLOCKED.**
