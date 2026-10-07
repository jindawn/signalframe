# TASK-08: mobile-pwa

## Goal

Improve mobile usability and installation while caching only static assets.

## Owned Paths

apps/web/public/sw.js; apps/web/public/manifest.webmanifest; apps/web/public/icon-*.png; apps/web/src/app/globals.css; apps/web/tests/pwa.spec.ts
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

Shell; layout metadata; API contract; Inbox
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

Standalone manifest, service worker cache boundaries. Coordinate CSS ownership with TASK-01.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

320px–430px phone layouts; keyboard access; safe-area nav; valid PNG icons; no API/private research cache; no push/offline-first.

## Required Tests

Mobile Playwright flow and service worker cache assertions; document manual iOS install check.

## Validation Commands

```sh
npm run lint
npm run typecheck
npm run build
npm run smoke
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
