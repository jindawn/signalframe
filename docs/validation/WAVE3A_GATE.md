# Wave 3A Gate — Real Model E2E & Research Loop Validation

Date: 2026-10-10 (Asia/Shanghai)
Worktree: `/Users/wumj/workspace/signalframe-worktrees/wave3a-e2e`
Branch: `feat/wave3a-e2e` · Baseline: `wave2b-complete` · HEAD at start: `c9e566e merge: complete wave 2b research engines`

## 0. Verdict

**WAVE 3A PARTIAL — LIVE E2E NOT VERIFIED.**

| gate | scope | result |
| --- | --- | --- |
| G1 | Contract / Build | **PASS** |
| G2 | PostgreSQL Research Loop | **PASS** |
| G3 | Live Provider Connectivity | **PASS** |
| G4 | Full Real AnalysisResult | **FAIL** |
| G5 | Web / Mobile Real Result | **PASS** |
| G6 | Security / Audit | **PASS** |

`WAVE 3A READY FOR MERGE` requires G1–G6 all PASS. G4 is FAIL, so that statement is
**not** made. Everything except a contract-satisfying real `AnalysisResult` is verified
and reproducible; §7 states exactly what is missing and why.

This round changed no production behaviour. It added two tests and this document.

## 1. Baseline audit

Classification is per capability, not per file. `IMPLEMENTED` = the code exists;
`TESTED` = an executed test asserts the behaviour; `NOT_TESTED` = no executed test
covers it; `BLOCKED` = an external condition prevents verification.

| capability | classification | evidence |
| --- | --- | --- |
| Wave 2B code complete | IMPLEMENTED · TESTED | full suite 494 → 497 tests, 0 failures; research packages present |
| Contract, no drift | TESTED | `npm run contracts:check` → "OpenAPI valid; generated Java and TypeScript are current" |
| Flyway V1…V4 executable | TESTED | applied on every isolated container; `V1__foundation.sql` untouched |
| `ModelGateway` MOCK/LIVE switch | IMPLEMENTED · TESTED | `RoutingModelGateway` + `RoutingProfilePolicy` + `EnvironmentCredentialSource`; live tiers PASS (§5) |
| `HypothesisTransitionPort` real implementation | IMPLEMENTED · TESTED | `HypothesisTransitionService`; `HypothesisTransitionIntegrationTest`, `Wave2BResearchLoopIntegrationTest` |
| Evidence / Prediction / Verification wiring | IMPLEMENTED · TESTED | `EvidencePredictionService` → `HypothesisTransitionGateway` → port, one transaction |
| `ConfidenceRubric` is the only scoring implementation | IMPLEMENTED · TESTED | exactly one implementation (`DeterministicConfidenceRubric`); one bean in `AnalysisConfiguration`; `shared/confidence`, Spring-free |
| Actual LLM call topology | TESTED | DRAFT = **1** `SYNTHESIS` call per analysis (`synthesis-v1`); STAGED = **14** (13 stage prompts + synthesis) |
| `AnalysisResult.predictions` shown in the web detail page | IMPLEMENTED contract · **NOT_TESTED** | the offline fixture emits no predictions, `AnalysisView` renders none (WAVE2B_CONTRACT_FREEZE §11.3) |
| Paid real provider | **BLOCKED** | no authorized credential (§5.3) — `BLOCKED_EXTERNAL_PROVIDER` |

Call topology, from `AnalysisPipelineMode` (`analysis.pipeline.mode`, default `draft`):

- **DRAFT (default)** — `SnapshotDraftStage` calls `ModelAnalysisService.synthesize` once,
  purpose `SYNTHESIS`, prompt `synthesis-v1`. All 14 protocol stages then derive their own
  artifact from that one draft. Exactly one audited `ModelRun` per analysis
  (`ProtocolPipelineTest.draftTopologyCompletesWithExactlyOneAuditedProviderRun`).
- **STAGED** (`mode=staged`) — 13 typed per-stage calls plus the synthesis call, 14 audited
  `ModelRun` rows, verified in `AnalysisPipelineSliceTest` against real PostgreSQL.

`AnalysisResultValidator` owns Gate A plus the expressible parts of B/C: bean-validation
constraints, `isProbability == false`, `modifiesExistingHypotheses == false`, non-empty
facts, FACT-only in `facts`, every fact statement equal to one of its own verbatim source
quotes, `UNKNOWN` typed `UNKNOWN` and never inside a claiming list, and every `SourceRef`
resolving verbatim against the input text. Gate D is the rubric stage; Gate E is stage
completeness.

## 2. G1 — Contract / Build: PASS

| command | result |
| --- | --- |
| `npm run contracts:check` | PASS — no drift |
| `./scripts/mvn.sh -B -f apps/api/pom.xml test` | PASS — **497 tests, 0 failures, 0 errors, 5 skipped** |
| `npm run lint` | PASS — no findings |
| `npm run typecheck` | PASS — `tsc --noEmit` clean |
| `npm run test` | PASS — 26 Vitest tests, 4 files |
| `npm run build` | PASS — optimized Next.js build |
| `npm run smoke` | PASS — **24 Playwright tests** in 3 projects |
| `git diff --check` | clean |

The 5 skipped tests are `RealModelEndToEndTest`, which is opt-in by design
(`@EnabledIfEnvironmentVariable(named = "AI_REAL_MODEL_E2E", matches = "(?i)true")`). A
skip is not a pass; §5 reports those tiers separately, with the flag set.

## 3. G2 — PostgreSQL Research Loop: PASS

Real PostgreSQL 17.6 (Testcontainers, Flyway V1→V4), no fake repository anywhere in the
asserted path. Two layers:

**Wave 2B layer (existing, re-executed green).** `Wave2BResearchLoopIntegrationTest`
drives the loop on a hand-seeded snapshot and proves: rubric recomputation, append-only
`hypothesis_events`, optimistic concurrency (`409 VERSION_CONFLICT`, nothing written),
idempotency (replay returns the recorded result, `applied = false`), rollback of a
verification record when the transition is refused, terminal-hypothesis refusal, and CF-06
fail-closed scoring.

**Wave 3A layer (new).**
`apps/api/src/test/java/com/signalframe/research/integration/Wave3aResearchLoopIntegrationTest.java`
closes the seam no test covered: the loop is fed by a snapshot a **real analysis job**
produced, not by rows the harness inserted.

```
POST /api/v1/news                       → 201, real Source + NewsItem
POST /api/v1/news/{id}/analyze          → 202, 14 durable steps, COMPLETED
GET  /api/v1/analyses/{id}              → rubric state + provenance + facts
GET  /api/v1/hypotheses/{id}            → version 0, one CREATED event
POST /api/v1/hypotheses/{id}/evidence   → SUPPORTS from an independent real source
                                          → rubric recomputes, version 1, STATUS_CHANGED
POST …/evidence                         → same source again: score must not move
POST …/evidence                         → CONTRADICTS, version 3, WEAKENING
POST …/predictions                      → OPEN prediction, expectedBy + criteria
GET  /api/v1/predictions/due            → passed deadline reported, never auto-resolved
POST /api/v1/predictions/{id}/verification → CONFIRMED, version 4, history appended
GET  /api/v1/hypotheses/{id}/timeline   → 5 events, version and score chains intact
POST …/verification (replay)            → applied = false, nothing written
```

What it proves that the seeded harness could not:

1. **The pipeline's research objects are usable by the engine.** A hypothesis the analysis
   job persisted starts at `version = 0` with exactly one `CREATED` event, which is the
   state the optimistic lock depends on.
2. **UTF-16 offsets are real.** The fixture contains an astral character (U+20BB7, two
   UTF-16 code units) inside the stored span; every `fact.sourceRefs[].quote` must equal
   `text.substring(startOffset, endOffset)`. A code-point or byte-based offset fails here.
3. **Confidence is a pure function of the rubric inputs.** Adding a second `SUPPORTS` item
   from the *same* independent source must leave the score **identical** — evidence changes
   confidence only by changing rubric inputs (D2/D3/D4/D5), never by a delta.
4. **Every stored score is rubric-shaped.** For each transition: the five profile
   dimensions are present, each dimension's `points == weight × level / 5` (20/25/20/20/15),
   the score never exceeds the raw sum (caps only lower), and `band == bandOf(score)`.
5. **No automatic truth.** A `CONFIRMED` prediction returns the hypothesis to
   `STRENGTHENING`, never `CONFIRMED`, while its own source still contradicts it.
6. **A stale token is a real stale token.** The stale `expectedVersion` used is the version
   read before a legitimate evidence write bumped it — the lost-update race, not a bogus
   number — and it writes nothing.
7. **Rollback is atomic on the real path.** A refused transition rolls back the
   verification record, leaving the prediction `OPEN` with `verified_at IS NULL`.
8. **Prediction text is immutable.** Statement and criteria are byte-identical before and
   after verification.

`aRefusedTransitionRollsBackTheVerificationOnARealSnapshot` moves the hypothesis to
`REJECTED` with SQL to model a hypothesis that already met a falsification condition; that
is the one place the test writes around the service, and it is a setup step, not the
assertion.

## 4. G2 evidence — PostgreSQL transaction properties

| property | how it is proven |
| --- | --- |
| Transaction atomicity | `HypothesisTransitionService.transition` is `@Transactional`; evidence write, transition, event append and version bump commit together. Refused transition → no verification row, no event, no version bump. |
| Optimistic concurrency | `SELECT … FOR UPDATE` on `hypotheses` is the first statement; `UPDATE … WHERE version = ?` bumps exactly one. Two concurrent submissions → exactly one 200 and one 409. |
| Idempotency | `operationId` is the key; a replay returns the recorded `verificationId` and `eventId` with `applied = false`; a different request under the same key is `409 IDEMPOTENCY_MISMATCH`. |
| Append-only history | `hypothesis_events` rows are inserted, never updated; payload lists are compared before/after and must be a strict prefix-preserving extension. |
| Rollback on failure | `aRefusedTransitionRollsBackTheVerification` (Wave 2B) and its Wave 3A twin. |
| Immutable prediction statement | statement and criteria compared before/after verification. |
| No automatic truth | `requireVerificationRecord` + `standsAgainst` in the engine; a `CONFIRMED` prediction cannot set `CONFIRMED` while a contradiction stands. |

## 5. G3 — Live Provider Connectivity: PASS

### 5.1 What was used

| item | value |
| --- | --- |
| Provider | `openai-compatible` (OpenAI `POST /chat/completions`) |
| Endpoint | `http://127.0.0.1:11434/v1` — **local Ollama**, loopback HTTP |
| Model | `gemma3:1b` (also tried: `llama3:latest` 8B, `qwen3:8b`) |
| Credential | placeholder `ollama` in `AI_FAST_API_KEY` only; Ollama ignores it |
| Cost | **0** — no paid service was contacted, no billing request was issued |
| Keys printed/committed | none |

**No credential was harvested.** Nothing was read from DeepSeek Harness, an OpenCode
session, a browser cookie or any other non-public source. `AI_FAST_*`, `AI_DEEP_*` and
`AI_REAL_MODEL_E2E*` were all **UNSET** in the environment; no `.env` exists in this
worktree. The only provider reachable without a credential is the local Ollama daemon
already installed on this machine (`/usr/local/bin/ollama`, listening on
`127.0.0.1:11434`). No paid provider was contacted, so the paid path remains
`BLOCKED_EXTERNAL_PROVIDER` (§7).

### 5.2 Effective mode confirmed LIVE before any call

`GET /api/v1/settings/model-profiles` is the runtime's own statement of routing. With the
profile pointing at the local endpoint and the key variable resolvable:

```
analysis.fast  effectiveMode=LIVE  provider=openai-compatible  model=gemma3:1b
analysis.deep  effectiveMode=MOCK  provider=mock               model=mock-v1
```

The check is not taken on trust: `RealModelEndToEndTest.configuredProfilesReportLiveModeWithoutExposingTheKey`
asserts `effectiveMode == LIVE`, that the routed profile carries the configured
provider/model, and that the settings payload does **not** contain the key value.

### 5.3 Executed result

```
AI_REAL_MODEL_E2E=true AI_FAST_PROVIDER=openai-compatible AI_FAST_MODEL=gemma3:1b \
AI_FAST_BASE_URL=http://127.0.0.1:11434/v1 AI_FAST_API_KEY=ollama \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
→ Tests run: 5, Failures: 0, Errors: 0, Skipped: 1   BUILD SUCCESS
```

| test | result | what it proves |
| --- | --- | --- |
| `configuredProfilesReportLiveModeWithoutExposingTheKey` | PASS | live mode, real provider/model, no key in the payload |
| `realProviderAnswersJsonAndReportsOnlyHonestUsage` | PASS | real transport call, structured JSON object, provider/model identity from the response, usage complete-and-consistent or entirely unknown, cost `null` |
| `unknownModelIsClassifiedAsARealProviderFailure` | PASS | a **real** provider rejection classified, non-retryable, credential-free, single-line detail |
| `timeoutIsEnforcedAtTheConfiguredBoundAndEachAttemptIsAudited` | PASS | bound enforced, exactly two audited attempts, latency measured, usage `null` |
| `analysisChainCompletesThroughApiAndIsVisibleToTheWebDetail` | skipped | the strict tier; see G4 |

One skipped test remains skipped and is reported as such, not as a pass.

A second, independent LIVE observation came from the browser run: a profile switched to the
real provider while another analysis job was in flight produced four audited attempts —
`TIMEOUT 45 642 ms`, `OUTPUT_INVALID 418 ms`, `PROVIDER_UNAVAILABLE 4 878 ms`,
`OUTPUT_INVALID 39 360 ms` — and the job then failed with
`模型输出未通过验证，请重试。`. The configured 45 s bound was honoured (45.6 s), every
attempt was audited separately, and the job reached a terminal state. That accidental
observation is recorded because it exercises the live path through the *whole* pipeline
rather than through a direct adapter call.

## 6. G4 — Full Real AnalysisResult: **FAIL**

```
AI_REAL_MODEL_E2E=true AI_REAL_MODEL_E2E_ANALYSIS=true AI_FAST_MODEL=gemma3:1b \
AI_FAST_BASE_URL=http://127.0.0.1:11434/v1 AI_FAST_API_KEY=ollama \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
→ Tests run: 5, Failures: 1, Skipped: 0   BUILD FAILURE
```

```
real model analysis did not complete: 模型输出未通过验证，请重试。
| audited attempts: [FAILED/OUTPUT_INVALID, FAILED/PROVIDER_UNAVAILABLE, FAILED/OUTPUT_INVALID]
```

The runtime behaved exactly as designed: the call was attempted, the output was validated
against the same schema and provenance rules as production, repaired once, classified, and
every attempt was audited with its own status, latency and error code. **No schema,
provenance or epistemic check was relaxed, and nothing silently degraded to Mock.**

### 6.1 Root cause

The failure is provider capability, not the runtime. The analysis prompt is ~14.5 k tokens,
of which ~13 k are the `AnalysisResult` JSON Schema, and the contract requires each fact's
`statement` to be a verbatim substring of the source with exact UTF-16 offsets. The three
models available locally cannot do that. Reproduced out of band against the same endpoint,
with `response_format: json_object` exactly as the adapter sends it:

| model | prompt framing | output | validation |
| --- | --- | --- | --- |
| gemma3:1b (1B) | as shipped | `{"$ref": …}` (14 completion tokens) | OUTPUT_INVALID |
| gemma3:1b | source first, contract last | `{"Analysis": {…}}` | OUTPUT_INVALID (wrapper) |
| gemma3:1b | + explicit rejection of wrapper keys | `{"AnalysisResult": {…}}` | OUTPUT_INVALID (wrapper) |
| llama3:latest (8B) | as shipped | `{"analysisResult": {…}}` | OUTPUT_INVALID (wrapper) |
| llama3:latest | source first, contract last | echoed a schema fragment | OUTPUT_INVALID |
| llama3:latest | + explicit rejection of wrapper keys | `{"summary": "…", "facts": [{"factId": "F1"}]}` | invalid: placeholder content |

This confirms and sharpens the pre-existing gap recorded in
[REAL_MODEL_E2E.md §5.1](../ai-runtime/REAL_MODEL_E2E.md): with the shipped framing the
output contract is stated ~13 k tokens *before* the output and the untrusted source is the
last thing the model reads, so models echo the source or wrap the result. The
re-framed variants move the contract last and reject the two observed wrapper keys, and
**the evidence is mixed, not an improvement**: gemma3:1b gets closer (it produces a real
`summary` and `facts`), llama3:8b gets worse.

### 6.2 Why no prompt change was shipped

The task permits a minimal prompt-framing fix for a clear output-format defect. Two reasons
this round deliberately did **not** make one:

1. The fix is not verifiable as an improvement here. Half the measurements got worse, and
   no available model gets past Gate A/C, so the change could not be validated end to end.
   Shipping an unvalidated prompt change during a *validation* round is the outcome this
   round's discipline exists to prevent.
2. The framing is not the binding constraint. Even with a perfect frame, these models would
   have to emit verbatim Chinese spans with exact UTF-16 offsets for every fact plus a fully
   valid `AnalysisResult`; a contract-capable provider is required either way.

The reproduction above is the evidence for the recommendation: harden the synthesis framing
(source before contract, output instruction last, wrapper keys explicitly rejected) in a
round where a contract-satisfying provider can verify it, bumping `synthesis-v1` →
`synthesis-v2` with an offline regression test on the frame. Until then
`prompts/synthesis/version.txt` stays `synthesis-v1` and the recorded prompt version stays
`v1`, so the audit trail never claims a prompt that was not shipped.

## 7. Blockers

| id | blocker | impact |
| --- | --- | --- |
| B1 | No authorized credential for a contract-capable provider | **G4 FAIL.** Local 1B–8B models cannot satisfy `AnalysisResult`. Needs a user-authorized provider (model id, endpoint, key) or a capable local model. |
| B2 | The "Huawei–Qualcomm patent agreement" input material was not provided | Not present in this worktree, in `docs/`, or in any sibling worktree/branch. The offline fixture is a purpose-written patent-licence report; the real-model attempts used `RealModelEndToEndTest.NEWS_TEXT` (energy regulator / data-centre capacity), which is the material actually in the repository. |
| B3 | Synthesis prompt framing (pre-existing, documented) | Contributes to `OUTPUT_INVALID` for small models; cannot be validated with the models available (§6.2). |

`B1` is the reason G4 is FAIL and the reason this round is `PARTIAL`. If the user provides
an authorized provider, the exact follow-up is:

```sh
AI_REAL_MODEL_E2E=true AI_REAL_MODEL_E2E_ANALYSIS=true \
AI_FAST_PROVIDER=openai-compatible AI_FAST_MODEL=<authorized-id> \
AI_FAST_BASE_URL=<authorized-root> AI_FAST_API_KEY=<secret> \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
```

## 8. G5 — Web / Mobile Real Result: PASS

`npm run smoke` with `API_BASE_URL` pointing at **this worktree's** API on an isolated port
(8090) and an isolated PostgreSQL 17.6 container on 55432 — never the main checkout's API
(8080) or database (5432), and no process belonging to another task was touched. The
Playwright config builds and starts this worktree's own production server on `WEB_PORT=3101`
with `reuseExistingServer: false`.

```
WEB_PORT=3101 API_BASE_URL=http://127.0.0.1:8090 npm run smoke
→ 24 passed (9.3s) — desktop-chromium, mobile-chromium (Pixel 7), mobile-webkit (iPhone 13)
```

New spec `apps/web/tests/wave3a.spec.ts` (6 of the 24) asserts, on all three projects:

| requirement | assertion |
| --- | --- |
| Summary | `Summary · 摘要` heading visible |
| Facts | `Facts · 来源事实` + FACT tag + reachable `原文依据` source spans |
| Key Variables | `Key Variables · 关键变量` |
| Mechanism | `Mechanism · 影响机制` |
| Hypotheses | `Hypotheses · 主要假设` + HYPOTHESIS tag |
| Alternative Explanations | `Alternative Explanations · 替代解释` |
| Falsification | `Falsification · 可证伪条件` |
| Verification Plan | `Verification Plan · 验证计划` (+ `Unknowns · 未知事项`, `Model Runs · 调用审计`) |
| desktop and phone readability | 15 section headings visible on all three devices |
| no horizontal overflow | `documentElement.scrollWidth` and `body.scrollWidth` ≤ viewport |
| no raw JSON | page text must not contain `"sourceRefs"`, `"confidenceAssessment"`, `"isProbability"`, `"factRefs"`, `"falsificationConditions"` |
| unknown not faked as verified | `Unknowns · 未知事项` rendered; UNKNOWN never typed FACT |
| LIVE / MOCK distinction | settings page shows `LIVE` and `MOCK` **simultaneously** for the two profiles; key **value** never present, variable name present |

Evidence: [wave3a-desktop.png](../evidence/wave3a-desktop.png) ·
[wave3a-mobile.png](../evidence/wave3a-mobile.png). The mobile capture shows the notice
`Mock 演示结果 · 未经独立核实。置信度是判断尺度，不是统计概率。` and
`Confidence · 判断尺度 37/100` with
`RUBRIC 0.1 | method=RUBRIC | score=37 band=LOW raw=37 capped=false | D1_SOURCE_QUALITY=0(0/20) | …`.

Two honest limitations:

- The rendered `AnalysisResult` came from the **offline Mock** provider (the default
  configuration), because G4 is FAIL. The rendering path is the same one a live snapshot
  takes — `AnalysisView` reads the persisted snapshot with no provider branch — but the data
  in the screenshot is mock data, and the page says so on its face.
- `AnalysisView` does not render `AnalysisResult.predictions`, and the offline fixture emits
  none (`WAVE2B_CONTRACT_FREEZE` §11.3). Predictions are verified through the API and the
  research loop (G2), not through the snapshot detail page.

## 9. G6 — Security / Audit: PASS

| check | result |
| --- | --- |
| No secret in tracked files | `git grep` for key-shaped strings and long `api_key=` values → no matches |
| `.env` ignored | `git check-ignore -v .env` → `.gitignore:1:.env` |
| No `.env` in this worktree | absent; all provider variables resolved from the process environment |
| No key value returned by the API | asserted by `configuredProfilesReportLiveModeWithoutExposingTheKey` and by the settings-page assertion |
| No key or prompt in the audit | `RealModelEndToEndTest` asserts neither the credential nor the source text appears in `/api/v1/model-runs`; `ModelAuditRedactionTest` (5 tests) and the `ProviderFailures` redaction path cover failures |
| No raw prompt/response persisted | `ModelRun` carries identifiers, prompt version, latency, usage, status and error code only (`ModelAnalysisService.audit`, `StageModelClient.audit`) |
| Boundary discipline | `ArchitectureTest` passes; domain/application import no provider; migrations are append-only and `V1__foundation.sql` is byte-identical to the baseline |
| Cost safety | no paid endpoint contacted, no automatic retry storm (bounded 2 attempts + 1 repair), no model substitution |

Security finding worth recording (non-blocking): the falsification section's CSS glyph
`⚑` is part of that heading's accessible name, so assistive technology announces
"⚑ Falsification · 可证伪条件". Cosmetic; no gate depends on it.

## 10. API cost and token usage

| item | value |
| --- | --- |
| Paid provider calls | **0** |
| External spend | **0** — local Ollama on loopback only |
| Cost per call | `UNKNOWN` — Ollama reports no price; `estimatedCost` stays `null` (never fabricated) |
| Mock usage | `0 / 0 / 0` tokens, cost `0.0` — audited for all 9 mock runs in the isolated DB |
| Live usage, failed attempts | `null` — a timed-out or invalid attempt records latency and error code, never invented tokens |
| Live usage, direct provider measurement | `prompt_tokens` 14 484–14 630, `completion_tokens` 14–4 096, `total_tokens` up to 18 690 (measured directly against Ollama with the exact production prompt, out of band) |
| Calls made in the live tiers | G3: 1 structured call + 1 unknown-model rejection + 2 timeout attempts. G4: 3 pipeline attempts. Browser observation: 4 attempts. All bounded, all audited. |
| Batch analysis | none — the full chain was attempted for exactly one case at a time |

## 11. Reproducing this gate

```sh
# G1
npm run contracts:check
./scripts/mvn.sh -B -f apps/api/pom.xml test          # 497 tests, 5 skipped by design
npm run lint && npm run typecheck && npm run test && npm run build

# G2 (real PostgreSQL Testcontainers; Docker required)
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest='Wave2BResearchLoopIntegrationTest,Wave3aResearchLoopIntegrationTest'

# G3 (local, free, OpenAI-compatible endpoint; no paid credential)
AI_REAL_MODEL_E2E=true AI_FAST_PROVIDER=openai-compatible AI_FAST_MODEL=gemma3:1b \
AI_FAST_BASE_URL=http://127.0.0.1:11434/v1 AI_FAST_API_KEY=ollama \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest

# G4 (expected to FAIL with the models available here; records the classification)
AI_REAL_MODEL_E2E=true AI_REAL_MODEL_E2E_ANALYSIS=true \
AI_FAST_PROVIDER=openai-compatible AI_FAST_MODEL=<contract-capable-model> \
AI_FAST_BASE_URL=<api-root> AI_FAST_API_KEY=<secret> \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest

# G5 (isolated database and port; never the main checkout's API or database)
docker run -d --name sf-wave3a-pg -e POSTGRES_DB=signalframe -e POSTGRES_USER=signalframe \
  -e POSTGRES_PASSWORD=wave3a-local-only -p 127.0.0.1:55432:5432 postgres:17.6-alpine
DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/signalframe DATABASE_USER=signalframe \
POSTGRES_PASSWORD=wave3a-local-only SERVER_PORT=8090 AI_FAST_API_KEY=ollama \
  .tools/jdk/Contents/Home/bin/java -jar apps/api/target/api-0.1.0.jar &
WEB_PORT=3101 API_BASE_URL=http://127.0.0.1:8090 npm run smoke
```

The new tests are deterministic and re-runnable: the offline provider is the default Mock,
the fixture is fixed, deadlines are simulated on the indexed column instead of by sleeping,
and no test mutates a profile that the analysis path routes to.

## 12. Known gaps carried forward (unchanged, not fixed here)

1. **Synthesis framing** — §6.2; the recommended fix and its cost.
2. **`AnalysisResult.predictions` never rendered and never emitted offline** — the API and
   the research loop carry predictions, the snapshot detail page does not show them.
3. **`Provenance.modelRunIds` is empty** — `model_runs` exists keyed by job but is not linked
   from the snapshot (`WAVE2B_CONTRACT_FREEZE` §11.2).
4. **No `verification` table** — outcomes live in the prediction payload plus
   `predictions.verified_at`; the hypothesis timeline is the audit trail (§11.7).
5. **`timeout` is capped at 120 s and `temperature` is always sent** — a reasoning model
   that rejects a non-default temperature cannot be configured today
   (`REAL_MODEL_E2E.md` §5.3–5.4).
6. **No provider pricing** — `estimatedCost` is always `null` on the live path (§5.5).
7. **D3 ∈ {0,1} in practice** — one source per analysis, so CAP-A binds and `MEDIUM`/`HIGH`
   are unreachable until multi-source ingestion exists. True statement about the MVP, not a
   defect.
8. **PWA iOS installation** is still unverified on a physical device; WebKit covers layout
   and runtime only.
