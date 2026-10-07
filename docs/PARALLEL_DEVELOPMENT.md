# Parallel development

One task per coding agent, preferably one worktree per branch. Start from the same reviewed foundation revision. Give the agent AGENTS.md plus its TASK file. The integrator owns OpenAPI, generation scripts, root configs and migration allocation. Task agents treat these as read-only and propose changes through the integrator.

Phase 1: TASK-01 frontend shell, TASK-02 ingestion, TASK-04 gateway and TASK-05 strategies can run in parallel with disjoint package/component ownership. TASK-08 owns only pwa assets and responsive CSS; coordinate CSS access with TASK-01. TASK-09 owns metrics/query observability and consumes AI audit ports. Phase 2: TASK-03 composes those stable ports; TASK-06 and TASK-07 need coordinated research interfaces and new migrations. TASK-10 consumes the integrated revision.

Best first tasks for DeepSeek/other fast coding models: TASK-01, TASK-02, TASK-05, TASK-08, TASK-09. Gateway schema failure handling and research state transitions deserve additional review. Provider/model availability must be verified against the actual service; never invent a model ID from a marketing name.

Before assignment, reserve files and migration numbers in the task message. Shared changes need a small separate integration patch. Each agent must run its Required Tests and report evidence, not simply claim completion. Integrator regenerates contracts, executes all baseline checks and browser smoke before merging. Conflicts are resolved against canonical contracts, never by accepting both duplicate DTO definitions.
