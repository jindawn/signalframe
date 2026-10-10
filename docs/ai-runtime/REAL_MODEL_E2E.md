# Real OpenAI-compatible model end-to-end

How to point SignalFrame at a real OpenAI-compatible provider, how to verify the
whole chain, and what the runtime does when a real provider misbehaves.

Scope: configuration, verification and failure classification only. The analysis
prompt, the `AnalysisResult` contract and the pipeline stages are unchanged.

## 1. Configuration

Everything real is configuration. No provider, model identifier, endpoint or
credential is compiled in, and no secret is ever written to a file that Git
tracks.

Copy the template and fill in the values your provider console shows:

```dotenv
AI_DEFAULT_PROFILE=analysis.fast
AI_FAST_PROVIDER=openai-compatible
AI_FAST_MODEL=<model-id-from-the-provider-console>
AI_FAST_BASE_URL=<api-root>
AI_FAST_API_KEY=<secret>
```

`analysis.deep` uses the same shape through `AI_DEEP_*`; `AI_DEFAULT_PROFILE`
picks which profile serves `SYNTHESIS` (both are routed to `analysis.fast` by
default in `apps/api/src/main/resources/application.yml`).

Keep the key in the local, ignored `.env` or in the process environment. Only
the **variable name** (`api-key-env`, e.g. `AI_FAST_API_KEY`) is stored in
configuration and returned by the settings API; the value is resolved by
`EnvironmentCredentialSource` at call time and never persisted, audited, logged
or echoed in an error message.

### Base URL

The provider must speak the OpenAI `POST /chat/completions` protocol. The runtime
appends `/chat/completions` to the configured base URL, so the base URL is the
API root that already contains the version segment where the provider uses one:

| Provider | `AI_FAST_BASE_URL` | Resulting request path |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` | `/v1/chat/completions` |
| OpenAI | `https://api.openai.com/v1` | `/v1/chat/completions` |
| Ollama (local) | `http://127.0.0.1:11434/v1` | `/v1/chat/completions` |
| vLLM / LM Studio (local) | `http://127.0.0.1:8000/v1` | `/v1/chat/completions` |

Endpoint rules enforced by `RoutingProfilePolicy` before a profile is accepted:

- HTTPS is required, except plain HTTP on a **literal loopback host**
  (`localhost`, `::1`, `127.x.x.x`) so a local server can be used without
  sending a key off the machine;
- no embedded credentials (`https://user:pass@host`) and no query string;
- the provider identifier must be one registered in the runtime
  (`mock`, `openai-compatible`).

A local server that ignores authentication still needs a **non-blank**
`AI_FAST_API_KEY` value (any placeholder, for example `ollama`), because a blank
value means "no key" and selects the offline adapter — see the next section.

### Client identity and session correlation

Two headers are added to every OpenAI-compatible request, both from `ai.client.*`
(environment overrides `AI_CLIENT_USER_AGENT`, `AI_SESSION_HEADER_NAME`,
`AI_SESSION_HEADER_ENABLED`):

| header | value | why |
| --- | --- | --- |
| `User-Agent` | `SignalFrame/0.1.0` by default | identifies **this** runtime truthfully. It is never OpenCode, Claude Code, curl or a browser, and the identity is deployment configuration rather than a compiled-in constant. |
| `x-opencode-session` *(name configurable)* | a UUID derived from the analysis job id | lets a gateway attribute the transport retry and the single bounded repair round of one analysis to one session. |

The session value is derived (`AnalysisJob` id → `UUID.nameUUIDFromBytes`), not
generated per call, so the first attempt, a transport retry, the repair round and
every stage of one analysis all carry the same opaque session, and two analyses
never share one. Set `AI_SESSION_HEADER_ENABLED=false`, or blank the header name,
for a provider that does not want the header. The behaviour is pinned against the
local stub server by `OpenAiClientCompatibilityTest`, which asserts the header
reaches the wire, is stable across retry and repair, differs per analysis, and
never impersonates another client.

### Live, Mock and Disabled

`GET /api/v1/settings/model-profiles` reports the effective mode per profile and
never returns a secret:

- `LIVE` — the profile reaches the configured remote provider;
- `MOCK` — no usable key was resolved, so the credential-free offline adapter
  answers instead and the pipeline keeps working;
- `DISABLED` — `enabled: false`; routing fails with `PROFILE_DISABLED`.

**A profile configured for a real provider silently reports `MOCK` when the key
is missing or blank.** That is deliberate offline behaviour, and it is the
easiest way to believe a real provider was exercised when it was not: check the
mode, and check that the `ModelRun` audit row names the real provider rather than
`mock` / `mock-v1`.

## 2. Running the opt-in verification

`apps/api/src/test/java/com/signalframe/ai/RealModelEndToEndTest.java` is the only
suite that contacts a real provider. It is skipped unless it is explicitly
enabled, so a keyless machine and CI never spend money and never fail because of
it.

```sh
export AI_REAL_MODEL_E2E=true
export AI_FAST_PROVIDER=openai-compatible
export AI_FAST_MODEL=<model-id>
export AI_FAST_BASE_URL=<api-root>
export AI_FAST_API_KEY=<secret>

./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
```

Local OpenAI-compatible server, no paid key:

```sh
AI_REAL_MODEL_E2E=true \
AI_FAST_PROVIDER=openai-compatible \
AI_FAST_MODEL=gemma3:1b \
AI_FAST_BASE_URL=http://127.0.0.1:11434/v1 \
AI_FAST_API_KEY=ollama \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
```

What the enabled suite verifies:

| Test | Verifies |
| --- | --- |
| `configuredProfilesReportLiveModeWithoutExposingTheKey` | the routed profile is the configured real provider, mode is `LIVE`, no key in the payload |
| `realProviderAnswersJsonAndReportsOnlyHonestUsage` | real transport call, structured JSON output, provider/model identity, usage is either complete-and-consistent or entirely unknown, cost unknown |
| `unknownModelIsClassifiedAsARealProviderFailure` | a real provider rejection is classified, non-retryable and credential-free |
| `timeoutIsEnforcedAtTheConfiguredBoundAndEachAttemptIsAudited` | the configured timeout aborts the call, the retry loop runs exactly twice, each attempt is audited with latency and null usage |
| `analysisChainCompletesThroughApiAndIsVisibleToTheWebDetail` | full chain, opt-in only |

Optional knobs for the opt-in run:

| Variable | Default | Meaning |
| --- | --- | --- |
| `AI_REAL_MODEL_E2E_TIMEOUT_SECONDS` | profile YAML (`45`) | per-call timeout override, 1–120 |
| `AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS` | `600` | how long the strict tier waits for the job |

Without `AI_REAL_MODEL_E2E` the suite is skipped. With the flag but without all
four provider variables it skips itself and says why. With the flag set the suite
starts an isolated PostgreSQL Testcontainers database, so Docker must run.

### The strict chain tier

`analysisChainCompletesThroughApiAndIsVisibleToTheWebDetail` additionally
requires a provider that actually follows the analysis output contract:

```sh
export AI_REAL_MODEL_E2E_ANALYSIS=true
```

It runs the real chain — paste news → analyze → worker → `ModelGateway` →
structured output → schema and provenance validation → analysis persisted →
`ModelRun` audited → the payload the web analysis detail page reads — and asserts
`facts` carry exact source spans, `demo` is false, the audit row names the real
provider and model with a measured latency, and that neither the source text nor
the credential reaches the audit trail.

It is opt-in separately because it depends on model capability, not on the
runtime. When it fails it prints the job error and the audited per-attempt
outcome, for example:

```
real model analysis did not complete: 模型输出未通过验证，请重试。
| audited attempts: [FAILED/OUTPUT_INVALID, FAILED/OUTPUT_INVALID]
```

## 3. Real call failure classification

Provider SDK exceptions are translated into one vendor-neutral taxonomy by
`ProviderFailures`; the code is what `ModelRun.errorType` and the HTTP error
payload carry. `retryable` decides whether the caller's bounded retry repeats the
call at all.

| Condition | Code | Retryable | HTTP |
| --- | --- | --- | --- |
| provider did not answer inside `timeout` | `TIMEOUT` | yes | 503 |
| HTTP 429 or equivalent throttling | `RATE_LIMITED` | yes | 503 |
| HTTP 5xx | `PROVIDER_UNAVAILABLE` | yes | 503 |
| connection refused, DNS or TLS failure | `TRANSPORT` | yes | 503 |
| HTTP 401 | `AUTHENTICATION` | no | 502 |
| HTTP 402, insufficient balance/quota | `QUOTA_EXCEEDED` | no | 502 |
| HTTP 403 | `PERMISSION_DENIED` | no | 502 |
| HTTP 400 / 413 / 422 | `INVALID_REQUEST` | no | 502 |
| HTTP 404, unknown model id | `MODEL_NOT_FOUND` | no | 502 |
| body could not be decoded | `PROVIDER_RESPONSE_INVALID` | no | 502 |
| response arrived but failed schema/provenance validation | `OUTPUT_INVALID` | no | 503, after repair |
| profile requires a capability the adapter lacks | `CAPABILITY` | no | 502 |
| no adapter for the configured provider | `CONFIGURATION` | no | 502 |

Rules the runtime keeps:

- every attempt, retry and repair is audited separately;
- only `retryable` failures are repeated, at most `ai.retry.max-attempts` times
  (default 2) with `ai.retry.backoff-ms` between attempts;
- a response that fails validation is repaired once by re-prompting, never
  accepted silently; exhausting repair fails the job with `MODEL_OUTPUT_FAILED`;
- the provider SDK's own retry is disabled (`maxRetries(0)`), so a single call
  produces exactly one HTTP request and every attempt is attributable;
- failure details are redacted before they can reach an audit row or a response:
  the resolved credential is replaced, key-shaped tokens are masked, and the
  detail is single-line and truncated;
- unknown usage stays `null`. A provider that reports no usage yields null token
  counts and null cost; nothing is fabricated into zeros.

## 4. What a `ModelRun` records

One row per attempt, with provider and model taken from the actual response (not
from the profile fallback):

`id`, `jobId`, `provider`, `model`, `purpose`, `promptVersion`, `startedAt`,
`completedAt`, `latencyMs`, `inputTokens`, `outputTokens`, `totalTokens`,
`estimatedCost`, `status` (`SUCCEEDED` / `FAILED`), `errorType`, `correlationId`.

Read them from `GET /api/v1/model-runs?jobId=<id>`, or from the analysis detail
page, which renders provider/model, purpose, prompt version, status, latency,
`totalTokens` (unknown is shown as 未知) and `estimatedCost` (unknown is shown as
未估算).

Never recorded: the prompt text, the request body, the source text, or the
credential value.

## 5. Known gaps

Found while validating the real path; each is outside the runtime boundary of
this task and none of them was fixed here.

1. **Output framing — fixed in `synthesis-v3`.** The synthesis prompt used to
   state the output contract in its first sentence, then the schema, then the
   untrusted source last. Small and mid-size models echoed the source object or
   wrapped the result in `{"analysisResult": {...}}`, because the only text
   adjacent to the reply was the source. Worse, a provider whose context window is
   smaller than the prompt truncates from the middle and therefore discarded the
   contract while keeping the source. `AnalysisPromptFrame` now orders the prompt
   instructions → guidance → schema → fenced source → **output contract last**, and
   `AnalysisOutputSchema` projects the schema down to the definitions reachable
   from `AnalysisResult` (19 of 48, compact instead of pretty-printed), cutting the
   assembled prompt by ~63% for the reference fixture. The contract also states the
   values the validator and the strict tier require rather than leaving them as
   "some boolean": `demo` must be false (it marks offline demo output) and
   `modifiesExistingHypotheses` must be false. See §7 for the measured sizes and
   the budget knob. Small models still fail, but they now fail on content, not on
   shape.

2. **`usage.total_tokens` is mandatory for the client.** The OpenAI Java client
   refuses to decode a `usage` object that omits `total_tokens`, so a provider
   that returns only prompt and completion counts fails the whole call as
   `PROVIDER_RESPONSE_INVALID` even though the completion text was fine. Pinned
   by `providerOmittingTotalTokensIsRejectedAsAnUndecodableBody`.

3. **`timeout` is capped at 120 s** by the `ModelProfile` contract. A local 8B
   model needs more than that for the synthesis prompt, so such a model times out
   at the configured bound (`TIMEOUT`, retried, audited).

4. **`temperature` is always sent.** Models that reject a non-default
   temperature (for example some reasoning models) cannot be configured today,
   because `ModelProfile.temperature` is required and always forwarded.

5. **Provider-side pricing is never reported**, so `estimatedCost` is always
   `null` in the live path. Cost accounting for real providers is not
   implemented.

6. **`prompt_tokens + max_tokens` must fit the provider context, and nothing
   enforces it.** The prompt is now small enough to fit an 8 k context in
   practice, but the runtime still cannot know a provider's window: `ModelProfile`
   carries `maxTokens` and `timeout`, not a context size. A provider that does not
   fit the prompt truncates silently and reports a smaller `prompt_tokens` than the
   prompt actually was, so compare the audited `inputTokens` against the expected
   size when an otherwise incomprehensible `OUTPUT_INVALID` appears.

## 6. Verification evidence

Commands and outcomes recorded when this runbook was written; see the commit
message and task report for the same numbers.

```sh
npm run contracts:check
./scripts/mvn.sh -B -f apps/api/pom.xml test      # full backend suite, real provider skipped
```

Full backend suite with the real-model suite disabled: 122 tests, 0 failures, 5
skipped, build success. The skipped class starts no container and makes no
request.

Real provider tiers, exercised against a local OpenAI-compatible server
(Ollama, `http://127.0.0.1:11434/v1`, model `gemma3:1b`):

- live mode reported for the routed profile, settings payload free of the key;
- a real call answered a JSON object with usage `19/6/25` and `estimatedCost`
  `null`;
- an unknown model id was rejected by the provider and classified
  `MODEL_NOT_FOUND`, non-retryable;
- the timeout tier passed with two audited `FAILED/TIMEOUT` attempts.

The strict chain tier was not reachable with the models available on this
machine. The `synthesis-v1` framing produced these results, and is the recorded
evidence for gap 1:

```
gemma3:1b  -> FAILED/OUTPUT_INVALID      model wrapped the result in {"AnalysisResult":{...}}
llama3:8b  -> FAILED/OUTPUT_INVALID      model echoed the source object instead of the result
qwen3:8b   -> FAILED/TIMEOUT, FAILED/TIMEOUT   >120 s for the ~7.6k-token prompt
```

Under `synthesis-v2` the same models were re-probed once each, and the failure
moved from shape to content:

```
gemma3:1b     -> FAILED/OUTPUT_INVALID   replied with a 71-character fragment
                                          {"claimType":"FACT","sourceRef":"…"} at a 4 818-token
                                          prompt that fits its 32 k context: not capable of the task
llama3:latest -> FAILED/OUTPUT_INVALID   replied with a complete, correctly shaped top-level
                                          AnalysisResult (all 19 top-level keys, no wrapper) and was
                                          rejected by bean validation for one missing nested field,
                                          facts[0].id, at a 4 655-token prompt
```

Both probes were bounded and stopped after failure; neither is a pass, and the
strict tier still requires a provider that satisfies the `AnalysisResult`
contract. In every case the runtime behaved as designed: the call was attempted,
the output was validated, repair ran once, the failure was classified, and every
attempt was audited with its own status, latency and error code.

## 7. Prompt size and the context budget

`prompt_tokens + max_tokens` has to fit the provider's context window, and when
it does not the provider truncates **silently** — Ollama keeps a few leading
tokens plus the tail and drops the middle. Two consequences are built into the
runtime:

- The output contract is the **last** section of the assembled prompt, so it is
  the first material that survives truncation (`AnalysisPromptFrame`).
- The prompt is a **projection** of the generated schema, not the whole API
  vocabulary: `AnalysisOutputSchema` keeps only the definitions reachable from
  `AnalysisResult` and serializes them compactly.

Measured on the reference fixture (a 461-character Chinese news item, domain
ENERGY, 1 798 characters of strategy guidance):

| section | `synthesis-v1` | `synthesis-v3` |
| --- | --- | --- |
| instructions | 1 093 | 1 002 |
| guidance | 1 798 | 1 798 |
| schema | 53 804 (48 schemas, pretty-printed) | 13 184 (19 schemas, compact) |
| source | 461 | 461 |
| output contract | — | 3 873 |
| **total** | **57 094** | **20 875 (−63%)** |

`ai.prompt.warn-chars` (default `60000`) is the operator's statement of what this
deployment expects its provider to accept. Every assembled prompt logs its section
breakdown at INFO, and crossing the threshold logs a WARN naming the provider and
model: it changes no behaviour, because the request is still valid and the provider
may well accept it, but it makes a silent truncation visible before it is
misdiagnosed as a model defect.

### Reasoning tokens share the completion ceiling

A reasoning model returns `reasoning_content` next to `content` and reports
`completion_tokens_details.reasoning_tokens`. What that means for the runtime:

- **Only `content` is the completion.** `reasoning_content` is a separate field and
  is never parsed as the answer. A reply that carries reasoning and no content is
  reported as `PROVIDER_RESPONSE_INVALID`, not as an empty snapshot: the model did
  not answer.
- **Reasoning is already inside `completion_tokens`**, so `input + output == total`
  still holds and no token is double counted. The reasoning count itself is not
  persisted: `ModelRun` has no field for it and the audit keeps its frozen shape.
- **Reasoning spends the `max-tokens` ceiling before any JSON is emitted.** A
  ceiling sized for the JSON alone truncates the reply mid-object, and truncated
  JSON fails the schema gate — which looks like a model defect. `analysis.fast`
  therefore ships `max-tokens: 8192`; the reference snapshot is ~1.2 k estimated
  tokens, so the reply, a verbose real snapshot and its reasoning all fit.
  `SynthesisBudgetTest` pins both the configured ceiling and that headroom.
- `finish_reason` is not interpreted by the runtime: a `length`-truncated body is
  returned to the validator as-is, so truncation surfaces as a schema failure
  rather than as a silently accepted partial snapshot.

Sizing guidance, in the order that avoids waste:

1. keep the prompt small rather than raising the output ceiling;
2. size `max-tokens` to what the reply actually needs — the context has to hold
   `prompt_tokens + max_tokens`, so an oversized ceiling shrinks the prompt budget
   and can itself cause truncation;
3. if a deployment needs a larger context, configure it on the provider
   (Ollama: `num_ctx`, or `OLLAMA_CONTEXT_LENGTH`) — it is not part of the
   OpenAI-compatible request that this runtime sends, and no per-model special
   casing belongs in the adapter.

