# TASK-05: domain-strategies

## Goal

Add Business/Finance/Policy guidance without editing pipeline orchestration.

## Owned Paths

apps/api/src/main/java/com/signalframe/analysis/application/strategies; apps/api/src/test/java/com/signalframe/analysis/strategies
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

DomainAnalysisStrategy; DomainType; AnalysisPipeline; shared contracts
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

supports(DomainType), guidance(). Coordinate a deterministic selection contract if overlapping strategies are introduced.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

At most these three strategies; specificity/fallback documented; no model/provider names or financial claims without sources.

## Required Tests

Strategy matching/fallback and representative domain guidance tests.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
