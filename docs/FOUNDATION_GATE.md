# Foundation Gate

Date: 2026-10-07 (Asia/Shanghai). **24 / 24 PASS. Stop feature expansion.**

Results are based on executed commands, not source inspection alone. Backend tests used real isolated PostgreSQL Testcontainers; production browser tests used project Docker PostgreSQL and the final running API. The initial browser run failed after a jar was rebuilt while running; launch now copies to a separate runtime file, API was restarted and the final 6-test run passed. No real paid model was called.

| #   | Requirement                     | Result | Evidence                                                                                          |
| --- | ------------------------------- | ------ | ------------------------------------------------------------------------------------------------- |
| 1   | 项目可以启动                    | PASS   | API runtime log confirms final application start; Web production server ready.                    |
| 2   | PostgreSQL 可以启动             | PASS   | docker compose up -d --wait: healthy PostgreSQL 17.6.                                             |
| 3   | Flyway migration 成功           | PASS   | V1__foundation.sql applied on project DB; isolated Testcontainers migration passes.               |
| 4   | API health 正常                 | PASS   | FoundationIntegrationTest asserts GET /actuator/health = 200.                                     |
| 5   | Web 可以启动                    | PASS   | next start production server ready at 127.0.0.1:3000.                                             |
| 6   | Desktop 页面基本可用            | PASS   | desktop-chromium browser happy path passes; desktop.png.                                          |
| 7   | 手机 viewport 正常              | PASS   | Pixel 7 Chromium and iPhone 13 WebKit pass; no horizontal overflow; mobile.png.                   |
| 8   | 可以输入一段新闻文本            | PASS   | All three browser engines submit pasted source text.                                              |
| 9   | 可以创建 NewsItem               | PASS   | Integration API asserts HTTP 201, GET persists source ID.                                         |
| 10  | 可以触发 AnalysisJob            | PASS   | Integration asserts HTTP 202 and job UUID.                                                        |
| 11  | Pipeline 可以运行               | PASS   | 14 named stages and 16 durable events; COMPLETED.                                                 |
| 12  | MockModelGateway 返回结构化结果 | PASS   | Typed AnalysisResult with FACT/INFERENCE/HYPOTHESIS labels, source quotes and demo=true.          |
| 13  | Analysis 可以持久化             | PASS   | PostgreSQL snapshot, hypotheses, indicators and CREATED timeline saved transactionally.           |
| 14  | Web 可以展示 Analysis           | PASS   | Browser AnalysisResult region, model audit, source provenance, linked hypothesis and reload pass. |
| 15  | OpenAPI Contract 存在           | PASS   | OpenAPI validation and Java/TS/JSON Schema regeneration drift check pass.                         |
| 16  | Domain 没有模型硬编码           | PASS   | ArchitectureTest validates domain/application have no provider imports or DeepSeek dependency.    |
| 17  | Model Profiles 可以配置         | PASS   | ConfigurationProperties + GET/PUT integration test; default no-key mock mode.                     |
| 18  | ModelRun 数据结构存在           | PASS   | model_runs table and typed records; retry attempts audited; actual provider/model preserved.      |
| 19  | AGENTS.md 完成                  | PASS   | Root agent ownership, contracts, migration, prompts, testing and safety rules.                    |
| 20  | ADR 完成                        | PASS   | ADR-001 through ADR-008 accepted.                                                                 |
| 21  | Parallel Development 文档完成   | PASS   | docs/PARALLEL_DEVELOPMENT.md phase ordering and integrator ownership.                             |
| 22  | 后续 TASK 文件完成              | PASS   | TASK-01 through TASK-10, each has all requested sections.                                         |
| 23  | 基础测试通过                    | PASS   | Backend 7/7; frontend Vitest 1/1; browser smoke 6/6; lint/typecheck and builds pass.              |
| 24  | README 完整启动步骤             | PASS   | macOS bootstrap, env, compose, API, Web, builds, tests and model switching documented.            |

## Executed verification

- `docker compose up -d --wait`: PASS; PostgreSQL healthy.
- `./scripts/mvn.sh -o -B -f apps/api/pom.xml package`: BUILD SUCCESS, 7 tests, 0 failures/errors/skips. Includes backend build and isolated PostgreSQL/Flyway integration.
- `npm run contracts:check`: OpenAPI valid, generated artifacts current.
- `npm run lint`: PASS, no lint errors.
- `npm run typecheck`: PASS.
- `npm run test`: 1 frontend unit test PASS.
- `npm run build`: optimized Next.js build PASS; 10 app routes and framework not-found route.
- `npm run smoke`: 6 tests PASS in 4.3s, across desktop Chromium, mobile Chromium and mobile WebKit. Validates real backend, persisted reload, provenance, timeline, fallback and static-only PWA caching.

Detailed local logs: `.tools/backend-tests.log`, `.tools/api-runtime.log`, `.tools/browser-smoke.log` (ignored). Browser visual artifacts: [desktop](evidence/desktop.png), [mobile](evidence/mobile.png). These contain only test input.

## Remaining scope

True multi-stage model reasoning/prompts, grade-driven depth routing, longitudinal evidence/confidence/prediction updates, automatic topic association and Today variable aggregation are intentionally deferred. The live Spring AI adapter compiles but needs provider-specific stub/paid-service verification before relying on actual news research output. Profile PUT is process-local. PWA iOS installation is not tested on a physical phone; mobile WebKit checks layout/runtime only. URL extraction is best-effort and DNS rebinding hardening remains TASK-02. Foundation has no authentication or distributed queue and runs one API instance locally.

All foundation gates are PASS; these remaining product features are not presented as completed. No commit or push was performed. Project-local Git repository was initialized. No global configuration was changed.

Final consistency checks: all shell scripts pass `bash -n`; macOS bootstrap re-run is idempotent; provider-specific activation/capability checks reside in infrastructure; final API restarted from a separate runtime copy and all 6 browser tests passed again.
