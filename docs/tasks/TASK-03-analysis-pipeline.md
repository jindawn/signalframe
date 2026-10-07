# TASK-03: analysis-pipeline

## Goal

Implement useful stages behind fixed AnalysisStep boundaries, preserving deterministic order.

## Owned Paths

apps/api/src/main/java/com/signalframe/analysis/application/steps; apps/api/src/main/java/com/signalframe/analysis/application/AnalysisPipeline.java; apps/api/src/main/java/com/signalframe/analysis/application/AnalysisJobService.java; apps/api/src/test/java/com/signalframe/analysis; apps/api/src/main/resources/prompts (reserve each prompt)
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

AnalysisStep; ModelAnalysisService; JobRepository; AnalysisRepository; contract; strategies
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

AnalysisStep<PipelineContext,PipelineContext>, AnalysisResult. Request audit/purpose port changes through integrator.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Each implemented stage is independently testable, emits durable status, never promotes inference to fact; retries bounded; no agent framework.

## Required Tests

Step tests, failed stage and retry tests, full PostgreSQL vertical slice.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
npm run smoke
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
