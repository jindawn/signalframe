# TASK-02: news-ingestion

## Goal

Harden best-effort extraction and pasted-text continuation without building a crawler.

## Owned Paths

apps/api/src/main/java/com/signalframe/news; apps/api/src/main/java/com/signalframe/infrastructure/news; apps/api/src/test/java/com/signalframe/news
Create missing subdirectories as needed. Do not edit files assigned to other tasks.

## Read-only Dependencies

news ports; http/NewsController; contract; persistence
Read AGENTS.md, README and docs/architecture/FOUNDATION.md first.

## Interfaces / Contracts

ContentExtractor.Extraction; NewsRepository; NewsInput/Source. Propose extractor status additions through integrator.

## Do Not Change

Canonical contracts, generated code, root build config, other modules, global configuration, secrets. Integrator owns all migration edits; request a new reserved version and give the integrator the exact SQL patch. Do not implement next-phase features outside this goal.

## Acceptance Criteria

Bounded fetches; private destinations including DNS rebinding prevented; URL+supplement provenance retained; failures are NEEDS_TEXT.

## Required Tests

Local HTTP fixture tests for redirect, oversized body, blocked IP, paywall and combined text.

## Validation Commands

```sh
./scripts/mvn.sh -f apps/api/pom.xml test
npm run contracts:check
```

Report commands, outcomes, changed files and any remaining limitations. Do not merely claim tests passed.
