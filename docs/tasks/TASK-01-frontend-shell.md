# TASK-01: frontend-shell

## Goal

Improve reading hierarchy and Today summaries using existing structured analysis.

## Owned Paths

apps/web/src/components/Shell.tsx; apps/web/src/components/research/Today.tsx; apps/web/src/components/AnalysisView.tsx
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

apps/web/src/lib/api.ts; generated TS; contracts; Inbox; PWA CSS
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

NewsItem, Analysis, Hypothesis. Consume GET news/{id}/analyses. Keep all claim labels visible.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Desktop navigation, true key-variable summaries, honest empty/error/loading states, no fake confidence changes.

## Required Tests

Component/browser tests with real structured fixtures; keyboard navigation; no console errors.

## Validation Commands

```sh
npm run lint
npm run typecheck
npm run test
npm run build
npm run smoke
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
