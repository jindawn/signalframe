# TASK-07: evidence-prediction

## Goal

Link sourced evidence and verifiable dated predictions to hypotheses.

## Owned Paths

apps/api/src/main/java/com/signalframe/research/application/evidence; apps/api/src/main/java/com/signalframe/research/domain/evidence; apps/api/src/test/java/com/signalframe/research/evidence
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

Evidence/Prediction/Indicator; sources/hypotheses tables; hypothesis engine ports; shared HTTP controllers
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

SUPPORTS/CONTRADICTS/NEUTRAL; CONFIRMED/REJECTED/PARTIAL/UNRESOLVED; expectedBy and verificationCriteria. New endpoints/migrations coordinated.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Source FK verified; strength range; due unresolved predictions query; verification reason/timeline; no automatic claim of truth.

## Required Tests

Stance transitions, due-date boundary, idempotent verification, FK and rollback tests.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
