# TASK-06: hypothesis-engine

## Goal

Implement evidence-driven confidence changes and durable timeline using explicit transition rules.

## Owned Paths

apps/api/src/main/java/com/signalframe/research/application/hypotheses; apps/api/src/main/java/com/signalframe/research/domain/hypotheses; apps/api/src/test/java/com/signalframe/research/hypotheses; apps/web/src/components/research/HypothesisPage.tsx
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

ResearchRepository; Hypothesis/HypothesisEvent; JdbcResearchRepository; evidence ports; DB migrations
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

Confidence 0–100 judgment; immutable confidence events. New routes/repository methods/migrations require integrator allocation.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Reason required for each confidence change; no history rewrite; transactionally append timeline; previous confidence traceable; optimistic concurrency.

## Required Tests

Transition, conflict, invalid range and PostgreSQL atomicity tests; timeline browser smoke.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
npm run typecheck
npm run smoke
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
