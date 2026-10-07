# SignalFrame coding agent rules

Read README, docs/architecture/FOUNDATION.md, contracts/openapi.yaml and your task before editing.
This repo is a Java 25 / Spring Boot modular monolith and Next.js workspace. Keep news, analysis, jobs, ai and research boundaries. No microservices, Kafka, K8s or agent frameworks.

## Ownership and contracts

Change only your task's Owned Paths. Treat Read-only Dependencies as interfaces. Do not change another module's contract privately. Propose contract changes first, update OpenAPI, regenerate `npm run contracts`, and coordinate dependent agents. Shared root configs, generated DTOs and migrations have one integrator owner. Generated code is never edited by hand. Backend `contract` records are generated stable shared vocabulary; business code is handwritten in domain/application packages.

## Persistence and AI

Use new immutable Flyway migrations, never edit applied ones. Reserve migration numbers with the integrator. Never use Hibernate schema auto-update. Multi-record completion must be transactional. Domain/application logic chooses purposes, never checks provider/model names. Provider logic belongs in infrastructure/ai/providers. Prompts live in resources/prompts with version files; audits preserve prompt version. Retain provenance and FACT/INFERENCE/HYPOTHESIS/PREDICTION separation. Confidence is 0–100 judgment.

## Safety and scope

Never modify global Codex/Claude/OpenCode/Git/system configuration. Never print, commit or hardcode real secrets. .env is ignored. Do not persist raw model prompts or API keys in audit. This foundation is local/single-user; no unrequested product expansion.

## Development and handoff

Use one worktree/branch per task when Git is initialized. Do not reset other agents' changes. Keep commits scoped; do not automatically merge or push. Report changed paths, checks and remaining limitations. Each task states its concrete validation commands. Baseline commands:
`npm run contracts:check`; `./scripts/mvn.sh -f apps/api/pom.xml test`; `npm run lint`; `npm run typecheck`; `npm run test`; `npm run build`; `npm run smoke` (API, database, production web running).
The foundation must stop at its gate. Read docs/PARALLEL_DEVELOPMENT.md before parallel edits.
