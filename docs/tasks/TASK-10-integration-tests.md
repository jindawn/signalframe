# TASK-10: integration-tests

## Goal

Extend regression coverage for the integrated revision, focusing on behavior and stable contracts.

## Owned Paths

apps/api/src/test/java/com/signalframe/integration; apps/web/tests; scripts/smoke-api.mjs
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

all production code and configuration read-only; existing tests; Foundation Gate
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

Canonical OpenAPI, API job/SSE semantics, PWA behavior; report implementation bugs to owning agent.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Reproducible text→job→analysis→timeline browser flow; reconnect SSE; restart/queue overload; failed extraction; fixtures have no secrets.

## Required Tests

Real PostgreSQL/Flyway integration plus desktop/mobile Chromium and WebKit; no mock-only persistence assertions.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
npm run lint
npm run typecheck
npm run test
npm run build
npm run smoke
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
