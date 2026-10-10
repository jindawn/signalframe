# SignalFrame

把新闻转换为可验证、可持续修正的世界模型。此仓库是可运行的架构基础；Mock 结果用于验证数据与流程，不构成真实新闻研究判断。

## Foundation architecture

Next.js 16.4 / TypeScript / App Router / Radix Progress / responsive PWA；Java 25 LTS / Spring Boot 4.1.1 / Spring AI 2.0.1；PostgreSQL 17 / Flyway / JDBC。

模块化单体：news、analysis、jobs、ai、research。模块通过 domain/application ports 交互。OpenAPI 是唯一契约来源，同步生成 Java records、TypeScript API 类型与分析 JSON Schema。所有结果结构化保存，事实有 sourceId、精确引用和 UTF-16 offset。置信度是 0–100 判断尺度，不是概率。

文本新闻 → NewsItem/Source → 持久化 AnalysisJob → 固定 14-step Pipeline → MockModelGateway → schema/provenance validation → Analysis/Hypothesis/Indicator/Timeline → Web。每次模型尝试单独记录 ModelRun。真实 Provider 适配器采用 Spring AI，未配置 Key 时自动 Mock。大部分推理步骤仍是替换点。

[Architecture](docs/architecture/FOUNDATION.md) · [ADR](docs/adr) · [Contract](contracts/openapi.yaml) · [Parallel development](docs/PARALLEL_DEVELOPMENT.md) · [Agent rules](AGENTS.md) · [Gate evidence](docs/FOUNDATION_GATE.md)

## macOS quick start

需要 Node 22+、Python 3、已安装并运行的 Docker Desktop。以下脚本只在项目 `.tools` 下载 Java/Maven，不修改任何全局配置。Apple Silicon 和 Intel macOS 均有引导路径；其他系统可使用已安装的 Java 25/Maven 3.9。

```sh
cd /Users/wumj/workspace/signalframe
cp .env.example .env
./scripts/bootstrap-tools.sh
npm ci --cache .tools/npm-cache
npm run contracts:check
docker compose up -d --wait
./scripts/mvn.sh -B -f apps/api/pom.xml package
```

终端 A：

```sh
./scripts/start-api.sh
```

终端 B（开发模式）：

```sh
./scripts/start-web.sh dev
```

打开 http://localhost:3000/inbox，粘贴新闻正文并点击 Analyze。API health：http://localhost:8080/actuator/health。

生产构建本地演示：

```sh
npm run build
./scripts/start-web.sh start
```

停止应用使用 Ctrl-C。`docker compose stop` 保留数据库；`docker compose down` 删除容器但保留卷。不要在需要保留研究数据时删除卷。

## Verification

```sh
npm run contracts:check
./scripts/mvn.sh -B -f apps/api/pom.xml test
npm run lint
npm run typecheck
npm run test
npm run build
```

后端测试自动启动隔离的 PostgreSQL Testcontainers，实际验证 Flyway、API、Pipeline、持久化、SSE、错误与重启恢复。Docker Desktop 必须运行。没有 Docker 的失败不应被当成通过。

浏览器测试需先运行本地 API 和生产 Web：

```sh
PLAYWRIGHT_BROWSERS_PATH="$PWD/.tools/playwright" ./node_modules/.bin/playwright install chromium webkit
npm run smoke
```

Smoke 在桌面 Chromium、Pixel 7 Chromium、iPhone 13 WebKit 验证输入→保存→时间线、URL 失败后粘贴继续、手机无水平溢出、基础 PWA 缓存。生成截图到 docs/evidence，只有测试文本。

## Contracts and modules

先编辑 contracts/openapi.yaml，再运行 `npm run contracts`。不要修改生成文件。文件使用 JSON 语法，是合法 YAML；无需引入第二份 schema 定义。`scripts/generate-java.py` 是这个项目支持的有限 OpenAPI schema 子集生成器；新 schema 结构需要扩展生成器与测试，不应静默降级为 Object。

新增 DB 结构需追加 Flyway migration。分析快照存 JSONB，研究对象具有独立 UUID/FK、假设事件时间线和预测到期索引。禁止 Hibernate 自动更新 schema。所有完成状态与研究对象写入同一事务。

## Model switching

默认配置是 analysis.fast → mock / mock-v1。未假定“DeepSeek v4.1 Flash”有任何特定公开 API 模型 ID；应从实际服务控制台确认 ID。

在 .env 填入服务商提供的实际值：

```dotenv
AI_FAST_PROVIDER=openai-compatible
AI_FAST_MODEL=replace-with-provider-model-id
AI_FAST_BASE_URL=https://api.deepseek.com
AI_FAST_API_KEY=
AI_DEFAULT_PROFILE=analysis.fast
```

Key 仅填到本地被忽略的 .env 或环境变量。**不要复制真实 Key 到代码/提交/日志。** 重启 API 后生效。换模型只改 provider、model、baseUrl 和环境 Key；业务代码只选择 ModelPurpose。analysis.deep 使用 AI_DEEP_*。Purpose→Profile 路由及 temperature/maxTokens/timeout/structuredOutput/toolCalling/enabled 在 application.yml，映射到 ConfigurationProperties。

GET settings/model-profiles 返回配置和有效 MOCK/LIVE/DISABLED 状态，不返回密钥。PUT settings/model-profiles/{profile} 可替换已有 Profile，**仅当前进程有效**；持久配置仍用 YAML/env。关闭所有已路由 Profile 时分析会失败，启用至少一个。工具调用尚未实现，true 配置会被明确拒绝。结构化输出开启时适配器请求 JSON object，并始终做本地类型/schema/provenance 校验。解析/校验失败重试一次，每次尝试独立审计。Mock 不需要 Key。Token 未知和成本未估算采用 null，绝不伪造为 0。

真实 Provider 链路已用真实 OpenAI-compatible 服务端到端验证（本地 Ollama，无需付费 Key）：路由、结构化输出、超时、重试、失败分类与 ModelRun 审计均可复现。配置步骤、失败分类表、ModelRun 字段与已知限制见 [真实模型 E2E 运行手册](docs/ai-runtime/REAL_MODEL_E2E.md)。付费 Provider 的完整分析链路仍需具备 schema 能力的模型，未在此机器上验证。

opt-in 真实验证（不设 `AI_REAL_MODEL_E2E` 时整套 SKIP，CI 不失败）：

```sh
AI_REAL_MODEL_E2E=true AI_FAST_PROVIDER=openai-compatible \
AI_FAST_MODEL=<model-id> AI_FAST_BASE_URL=<api-root> AI_FAST_API_KEY=<secret> \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
```

注意：Profile 配置了真实 Provider 但 Key 缺失或为空时会静默回退到 Mock；先用 settings/model-profiles 确认 `LIVE`，并核对 ModelRun 记录的是真实 provider/model 而不是 `mock`/`mock-v1`。

## Local operating boundaries

本轮没有鉴权；默认只绑定 loopback，供本地单用户开发。队列容量 32、并发 2。仅支持单 API 实例。重启后未完成任务明确 FAILED，可重新 analyze；不会自动重放付费调用。SSE 使用持久事件序列，支持 Last-Event-ID；前端轮询兜底，并保留活动 jobId 以恢复任务。

URL 抽取有超时/体积上限、禁止重定向、拒绝初始解析的私有地址；动态渲染/付费墙等返回 NEEDS_TEXT。DNS 重绑定进一步加固留给 TASK-02。URL+补充文字合并为一个可追踪 Source，单独区分多个来源留给后续摄取任务。

## Follow-up tasks

完整任务在 docs/tasks。优先可交给快速 Coding Agent：TASK-01 frontend shell、TASK-02 news ingestion、TASK-05 domain strategies、TASK-08 mobile PWA、TASK-09 model observability。TASK-03 pipeline、TASK-04 gateway、TASK-06 hypothesis engine、TASK-07 evidence/prediction 和 TASK-10 integration tests 也已给出接口及验收标准。契约、迁移与 root build 由一个 integrator 负责。

## Intentional gaps

真实多阶段 Prompt、价值分级路由、完整领域策略、长期证据/预测/置信度更新、主题自动关联、语义检索、Today 跨分析变量聚合均未实现。任务表/SSE/结构化 mock 完整可运行；不把 scaffold 声称为真实研究引擎。PWA 只缓存静态安装资源，不支持离线研究/推送；Safari Add to Home Screen 需真实设备手动验收。
