# Model observability

Status: implemented for the model run audit (TASK-09), with the provider runtime
from TASK-04. HTTP exposure is intentionally not part of this task; see
[HTTP surface](#http-surface) below.

## Scope

The audit answers four questions without collecting research content:

1. which provider and model identifier actually answered;
2. which purpose and prompt version caused the call;
3. how many attempts, retries and failures occurred, and how long they took;
4. what token usage and cost were reported.

Aggregation is a read-only projection of the append-only `model_runs` table. The
table is written by the AI application service and never mutated by
observability.

## What is never collected

- Prompt text, request bodies, provider response bodies and raw completions.
- Credential values. A profile stores only the **name** of the environment
  variable (`apiKeyEnv`); the resolved secret lives in memory for the duration of
  one call, is passed to the adapter as `ModelCredentials`, and is never written
  to the audit record or returned by the settings API.
- Ingested news text. The audit table has no column for it, and no aggregate
  field can carry it.

`ModelCredentials.toString()` is redacted and `ModelInvocationException` redacts
both the known credential and key-shaped tokens before the message is built, so
an accidental provider echo cannot reach the audit record or the HTTP error
payload.

## Recorded fields

Per attempt, matching `ModelRun` in `contracts/openapi.yaml`:
`provider`, `model`, `purpose`, `promptVersion`, `startedAt`, `completedAt`,
`latencyMs`, `inputTokens`, `outputTokens`, `totalTokens`, `estimatedCost`,
`status` (`SUCCEEDED` / `FAILED`), `errorType`, `correlationId`.

`errorType` is the stable code from the vendor-neutral failure taxonomy
(`TIMEOUT`, `RATE_LIMITED`, `PROVIDER_UNAVAILABLE`, `TRANSPORT`, `AUTHENTICATION`,
`PERMISSION_DENIED`, `INVALID_REQUEST`, `MODEL_NOT_FOUND`, `CAPABILITY`,
`PROVIDER_RESPONSE_INVALID`, `OUTPUT_INVALID`, `CONFIGURATION`, `UNKNOWN`). It is
null for a successful attempt.

## Null semantics

Unknown values stay null; they are never reported as fabricated zeros.

- `inputTokens`, `outputTokens`, `totalTokens`: the sum of the runs that reported
  the field, or **null when no run did**.
- `estimatedCost`: the same rule. Offline runs report an explicit `0.0`, which is
  a known zero and is therefore reported as `0.0`, not null.
- `unknownUsageRuns` and `unknownCostRuns` count the runs that could not
  contribute. A partial sum is therefore always identifiable as partial instead
  of being mistaken for a complete total.

## Grouping and filtering

`ModelRunGrouping`: `PROVIDER_MODEL`, `PURPOSE`, `PROMPT_VERSION`, `JOB`.

Aggregation always groups by the **recorded** provider and model of each run, so
an offline run and a remote run can never be merged under one identifier even
when the surrounding analysis is the same. For groupings other than
`PROVIDER_MODEL`, `provider` and `model` are still populated when every run in
the group shares one value, and are null when the group spans several
identifiers.

`ModelRunFilter`: time window (`from` inclusive, `to` exclusive), `jobId`,
`provider`, `model`, `purpose`. Everything is applied in SQL.

## Retry accounting

A **retry** is an attempt that is not the first one for the same `jobId` +
`purpose` + `promptVersion` + recorded `provider`/`model` chain. A call that
switched to a different model is a different call configuration and is not
counted as a retry. Each transport retry and each schema repair is persisted as
its own `ModelRun`, so a repaired call is visible as `FAILED` followed by
`SUCCEEDED` rather than as one opaque success.

`successRate` and `errorRate` are attempt-level, not job-level: a job that needed
one retry has two attempts and therefore a 50% attempt success rate. Group
retries are counted inside the group; the summary counts them across the whole
selection.

## Bounds

A report fetches at most `ai.observability.max-rows` rows (default 10000,
overridable by property or `AI_OBSERVABILITY_MAX_ROWS`). Rows are read oldest
first. `truncated` is set when the returned row count reaches the limit; it is
deliberately conservative, so an exactly-complete selection of `rowLimit` rows
also reports `truncated: true` rather than risking a false negative.

`summary(filter)` and `aggregates(filter, grouping)` read the same bounded
window but do not expose `truncated`. Use `report(filter, grouping)` whenever the
caller must know whether the window was complete.

## Provider runtime behaviour recorded by the audit

The fields above are produced by the AI runtime, so its bounds matter for
interpreting a report:

- **Configuration.** `provider`, `model`, `baseUrl` and the credential variable
  name all come from the model profile (`AI_FAST_*` / `AI_DEEP_*`); the credential
  value is read from the environment on demand. Adding a provider means adding a
  `ModelProviderAdapter` bean and naming it in a profile.
- **Endpoint policy.** A credential-bearing profile must use HTTPS. Plain HTTP is
  accepted only for a literal loopback host (`127.0.0.0/8`, `localhost`, `::1`),
  which is how a local OpenAI-compatible server is used without sending the key
  off the machine. The loopback test is a literal check with no DNS resolution.
- **Timeout.** `profile.timeout()` seconds bounds each transport attempt and is
  classified as `TIMEOUT`, which is retryable.
- **Retry.** `maxAttempts` is the total number of transport attempts for one
  request (1 means no retry); only `retryable()` failures are repeated. The
  provider SDK's own retry is disabled, so one audit row means one real call.
- **Repair.** `maxRepairAttempts` re-prompts after a response failed local
  validation, recorded as `OUTPUT_INVALID` on the failed attempt. When repair is
  exhausted the analysis fails with `MODEL_OUTPUT_FAILED`.
- **Credential lifetime.** The provider client is built per call and never
  cached, so the credential it carries lives only for the duration of that call.

## HTTP surface

`ModelObservabilityService` is an application bean with no HTTP endpoint in this
task. Adding `GET /api/v1/settings/model-observability` requires an OpenAPI
change, which is integrator-owned: the contract must be edited in
`contracts/openapi.yaml`, regenerated with `npm run contracts`, and only then
wired into a controller. Until that happens the service is consumed by tests and
by future callers inside the process.

## Verification

Unit tests: `ModelObservabilityServiceTest` (null usage, zero-cost offline runs,
partial totals, retries, identifier separation, grouping keys, job correlation,
latency, truncation, surface whitelist).

Datasource test: `ModelObservabilityJdbcTest` (real PostgreSQL through
Testcontainers; Flyway schema, SQL projection, filters, row limit, and that no
ingested source text appears in a serialized report).

End-to-end test: `LiveProviderAuditIntegrationTest` (Spring-wired profile routed
to the OpenAI-compatible adapter against a loopback stub endpoint; asserts the
request that left the process, the persisted audit row with the actual provider,
model, prompt version, token usage and correlation id, and that neither the
credential nor the source text is audited).

Redaction tests: `ModelAuditRedactionTest` (credential never rendered, redacted
single-line failure detail, audit record carries every required field and no
prompt or secret field).

```sh
./scripts/mvn.sh -B -f apps/api/pom.xml test
npm run contracts:check
```

## Ownership note

`JdbcModelRunObservabilityQuery` performs the read-only SQL and currently lives in
`ai/application/observability` because this task owns only the AI observability
packages and treats the datasource as a read-only dependency. It imports no
`infrastructure` type and issues `SELECT` statements only. When the integrator
grants `infrastructure/persistence`, the adapter should move there unchanged.

## Known gaps

- No HTTP endpoint and no OpenAPI schema yet (integrator-owned contract change).
- Aggregation is in-process over a bounded row window; a SQL-side rollup would be
  needed for a long history.
- Cost is reported only when a provider returns it; no price table exists, so
  `estimatedCost` is null for paid providers unless a later task adds pricing.
- Aggregates are computed per request; there is no cached rollup table.
- The AI runtime has no dedicated logger: an unclassified provider failure is
  audited as `UNKNOWN` and surfaced as `MODEL_ERROR`, but no stack trace is
  recorded. Adding structured logging is a follow-up.
- `ai.retry.*` and `ai.observability.max-rows` are not listed in
  `application.yml` (integrator-owned); the built-in defaults apply until the
  integrator adds them.
