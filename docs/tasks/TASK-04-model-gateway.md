# TASK-04: model-gateway

## Goal

Verify real OpenAI-compatible provider behavior and add one provider behind gateway if needed.

## Owned Paths

apps/api/src/main/java/com/signalframe/ai; apps/api/src/main/java/com/signalframe/infrastructure/ai/providers; apps/api/src/test/java/com/signalframe/ai
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

ModelRunRepository; AnalysisResult schema; prompts (read-only); migrations; domain
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

ModelGateway, ModelRequest/Response/Usage, purpose router, profile API. Configuration exposes env variable names only.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Two config-selected models work against stub server; no-key fallback; timeout, capability flags, repair, redacted failures and audit are tested.

## Required Tests

Provider stub HTTP tests, missing key fallback, malformed JSON/provenance repair exhaustion. Never require paid API keys in CI.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
