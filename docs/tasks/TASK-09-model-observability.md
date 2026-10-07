# TASK-09: model-observability

## Goal

Expose useful audit aggregates without collecting prompts or secrets.

## Owned Paths

apps/api/src/main/java/com/signalframe/ai/application/observability; apps/api/src/main/java/com/signalframe/ai/domain/observability; apps/api/src/test/java/com/signalframe/ai/observability; docs/architecture/OBSERVABILITY.md
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

ModelRunRepository; ModelRun; ModelController; datasource; integrator-owned migration
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

Aggregate by analysis/job, provider/model, purpose, prompt version. New API contracts coordinated before implementation.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Null unknown costs remain unknown; separate actual mock/live model identifiers; token totals and success/error rates correct; request/job IDs correlate.

## Required Tests

Aggregate tests with null usage, retries, zero-cost mock and failures; secret redaction checks.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
