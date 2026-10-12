# Wave 3A — 真实 provider 实跑记录（OpenCode Go · deepseek-v4.1-flash）

| 项 | 值 |
| --- | --- |
| 日期 | 2026-10-12（Asia/Shanghai） |
| 分支 | `feat/wave3a-v5-prompt` |
| 基线 | `5eba77b`（`feat/wave3a-e2e` 尖端） |
| 本文档性质 | **新增验证记录**。不修改任何既有文档，不改变任何门禁状态，**不构成人工审查或签署** |
| 执行者 | DeepSeek Harness（AI Agent）。**不是**人工审查者，**无权**签署 F5 或 Review Gate |
| 真实调用 | 11 次 strict-tier 实跑 + 3 次手工端点探针，全部走用户已授权的 OpenCode Go 套餐 |
| G4 状态 | **仍为 FAIL（未变）** |

---

## 0. 结论

真实 provider 通路**本身可用**：鉴权、结构化输出、模型身份、延迟、错误分类、审计全部正确（strict-tier 的另外 4 个用例通过）。

但 `deepseek-v4.1-flash` 在项目强制的 **120 秒单次调用上限**内**无法满足** `AnalysisResult` 严格契约。因此 G4 维持 FAIL，只是阻塞理由从"没有可用凭据"改为两条**可复现**的硬约束（§4）。

本轮**发现并修复了一个真实缺陷**（§3）：两阶段路径下，机制端点解析用错了状态，导致任何"以变量命名因果端点"的合法回复都被拒绝。这不是模型问题，是代码问题。

---

## 1. 前置缺陷：会话头缺失（已在 `main` 修复）

OpenCode Go 端点要求请求携带 `x-opencode-session`，否则一律：

```
HTTP 400 {"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session ..."}}
```

`main` 的 `OpenAiCompatibleGateway` 不发这个头，因此**主线完全无法使用该 provider**；`feat/wave3a-e2e` 已实现（`DEFAULT_SESSION_HEADER`、`ai.client.session-header-name`、`OpenAiClientCompatibilityTest`）。

处置：把 `2c9d68f` cherry-pick 到 `main` → `ca36921`。离线验证 35 个用例通过（含新增的 12 个兼容性用例）。文档冲突按 `main` 现状解决，仅补入"客户端身份与会话头"一节。

---

## 2. 可复现配置

```sh
export AI_REAL_MODEL_E2E=true AI_REAL_MODEL_E2E_ANALYSIS=true
export AI_REAL_MODEL_E2E_TIMEOUT_SECONDS=120 AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS=900
export ANALYSIS_PIPELINE_MODE=two-stage
export AI_FAST_PROVIDER=openai-compatible
export AI_FAST_MODEL=deepseek-v4.1-flash
export AI_FAST_BASE_URL=https://opencode.ai/zen/go/v1
export AI_FAST_API_KEY=<环境变量注入；不落仓库>
export AI_FAST_MAX_TOKENS=32000 AI_FAST_TIMEOUT=120
export AI_TWO_STAGE_FACTS_MAX_TOKENS=32000    AI_TWO_STAGE_FACTS_TIMEOUT=120
export AI_TWO_STAGE_REASONING_MAX_TOKENS=32000 AI_TWO_STAGE_REASONING_TIMEOUT=120
export AI_TWO_STAGE_MAX_PROVIDER_CALLS=8
export SPRING_APPLICATION_JSON='{"ai":{"client":{"reasoning-effort":{"FACT_EXTRACTION":"low","SYNTHESIS":"medium"}}}}'

./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest \
  -Dai.client.reasoning-effort.FACT_EXTRACTION=low \
  -Dai.client.reasoning-effort.SYNTHESIS=medium
```

模型 id 由端点实测得出（`GET /v1/models` 返回 38 个 id，`deepseek-v4.1-flash` 在列），不是从营销名推测。

**为什么必须调预算与推理档**：这是推理模型，`completion_tokens` 同时计入 reasoning 与可见 JSON。runtime 自己给出的诊断即为证据：

```
PROVIDER_RESPONSE_INVALID: Provider returned an empty completion truncated at the completion
ceiling (finish_reason=LENGTH, content=empty): raise max-tokens, or reduce the reasoning ...
```

---

## 3. 发现并修复的缺陷：机制端点解析顺序（STG-05.1）

### 现象

两阶段路径的 stage 2 **稳定**失败，无论 prompt 版本、推理档位与预算如何：

```
mechanisms[].from: a causal link endpoint must resolve to a variable or fact already in the
snapshot (STG-05.1)
```

### 根因

`StageResponseReader.mechanismsEnvelope` 用传入的 `PipelineState` 校验 `from`/`to`（`requireResolvableConcept` → `resolvableConcept` 遍历 `state.variables()` 与 `state.facts()`）。

而 `TwoStageSynthesisService.validateNarrowReasoning` 中，**同一次回复**先解析 `variables`、再解析 `mechanisms`，但两者都使用**原始 `state`**——本回复声明的变量从未进入校验状态。同处对 hypotheses 已正确按依赖顺序增长状态（`withHypotheses`），唯独 variables → mechanisms 漏做。

后果：只要机制端点写的是**变量名**（这正是规则要求它做的事），就必然解析失败。模型无法通过任何写法绕过——这是**代码缺陷**，不是模型能力问题。

### 修复

```java
var withVariables = state.withVariables(variables);
var mechanisms = deps.reader()
  .mechanisms(envelope("mechanisms", parsed.mechanisms(), List.of()), withVariables)
  .value();
```

并在 `two-stage-reasoning` prompt（版本升 `v6`）写明解析规则：`from`/`to` 必须是已存在的变量名、变量 id、事实 id，或事实陈述中的逐字文本；不得是新概念或转述。

### 验证

| 项 | 结果 |
| --- | --- |
| 离线全套 | `652 tests, 0 failures, 8 skipped`，BUILD SUCCESS |
| 修复前真实调用 | 每次均 STG-05.1（`g4c`/`g4e`/`g4v6b`） |
| 修复后真实调用 | STG-05.1 **不再出现**（`g4fix`/`g4high`） |

---

## 4. 剩余阻塞（与代码无关）

| id | 阻塞 | 证据 |
| --- | --- | --- |
| B-1 | **120 秒单次上限**（`ModelProfile` 契约：timeout 1–120）不足以容纳推理模型的完整调用 | 单体路径两次均 120 秒打满（`g4` 105.6 s、`sh-live` 253.6 s）；两阶段 stage 2 在 medium 档多次 TIMEOUT（`g4v4`、`g4high`） |
| B-2 | **span 保真**：必须给出原文逐字子串 + 精确 UTF-16 offset（`validateSpan` 要求 `text.substring(start,end).equals(quote)`） | stage 1 多次以 `facts[].sourceRefs: the quote is not a verbatim substring of the source text (PR-07/PR-10)` 失败（`g4d`、`g4fix`） |

两条都不是运行时缺陷：调用被尝试、输出按生产同款 schema 与 provenance 规则校验、允许一次修复、逐次审计，**没有放宽校验，也没有静默降级为 Mock**。

---

## 5. 逐次实跑记录

| # | 日志 | 配置要点 | 结果 | 审计尝试 |
| --- | --- | --- | --- | --- |
| 1 | `g4` | wave3a 默认（单体）· 未显式调预算 · 未设推理档 | TIMEOUT | TIMEOUT ×2 |
| 2 | `g4b` | 两阶段 · 全 low 推理档 · 预算继承 profile | stage 2 截断（end-of-input） | OUTPUT_INVALID ×2 + SUCCEEDED + OUTPUT_INVALID |
| 3 | `g4c` | 两阶段 · 全 low · stage2 预算提到 32000 | STG-05.1 | OUTPUT_INVALID ×2 + SUCCEEDED |
| 4 | `g4d` | 两阶段 · facts low / synthesis medium | stage 1 引文非逐字 | OUTPUT_INVALID ×2 |
| 5 | `sh-live` | `main`+会话头 · 单体 · 预算 32000 / 超时 120 | TIMEOUT | TIMEOUT ×2 |
| 6 | `g4e` | 两阶段 · 全 medium（facts 8192 / reasoning 32000） | STG-05.1 | 6 次，末为 OUTPUT_INVALID |
| 7 | `g4v4` | 同上，但 **v4** prompt | stage 2 TIMEOUT + 校验失败 | TIMEOUT ×3 + OUTPUT_INVALID + SUCCEEDED |
| 8 | `g4v6` | v6 规则 · facts medium · stage1 预算 8192 | stage 1 空回复（LENGTH） | PROVIDER_RESPONSE_INVALID ×1 |
| 9 | `g4v6b` | v6 · facts low / synthesis medium | STG-05.1（修复前） | OUTPUT_INVALID ×3 + SUCCEEDED + OUTPUT_INVALID |
| 10 | `g4fix` | **含顺序修复** · facts low / synthesis medium | stage 1 引文非逐字 | OUTPUT_INVALID ×3 |
| 11 | `g4high` | **含顺序修复** · facts high / synthesis medium | stage 1 **通过**；stage 2 TIMEOUT | TIMEOUT ×2 + OUTPUT_INVALID ×2 + SUCCEEDED |

每行"审计尝试"按 `ModelRun` 落库顺序列出，`SUCCEEDED/null` 为 stage 1 成功的那次调用。逐次原始日志未纳入仓库（在会话工作区）；本表只保留**决定性差异**（模式、prompt 版本、是否含修复、推理档），不复述每次的全部预算数值。

另有 3 次手工端点探针（模型清单、最小 chat、`reasoning_effort` 接受性），均 HTTP 200。

---

## 6. 未验证与限制

1. 单一模型、单一输入样本（`RealModelEndToEndTest.NEWS_TEXT`），未做重复试验统计，因此"稳定/间歇"的判断基于有限样本。
2. 未测量 provider 侧真实 token 与费用；`ModelRun` 不持久化 reasoning token（既有冻结契约）。
3. 未改动任何契约、门禁、验收标准或迁移；**G4 仍 FAIL**。
4. 本文档不构成审查结论，也不改变 `WAVE3A_REVIEW_GATE_STATUS.md` 的 BLOCKED 状态。
5. 未 push；相关提交仅在本地。

---

## 7. 建议的下一步（需人工决定）

| 选项 | 说明 | 类别 |
| --- | --- | --- |
| A | 放宽 `ModelProfile.timeout` 上限（当前 1–120）。B-1 是目前唯一能直接打通该模型的办法 | 契约变更，需人工批准 |
| B | 换一个 span 保真更好的模型（同端点 38 个 id 可选） | 配置，无契约变更 |
| C | 重新设计 span 策略（例如由服务端定位最近逐字子串并回填 offset） | 设计变更，需人工批准；当前实现刻意不做 |

不建议的做法：为了通过 G4 放宽 provenance 校验或接受非逐字引文——那会使 FACT 的证据链失效。

---

## 8. 相关提交与文件

| 提交 | 分支 | 内容 |
| --- | --- | --- |
| `ca36921` | `main` | cherry-pick `2c9d68f`：客户端身份与会话头 |
| `cca8519` | `feat/wave3a-v5-prompt` | 保存已验证的 v5 双阶段 prompt 改动 |
| `ffa7152` | `feat/wave3a-v5-prompt` | 机制端点解析顺序修复 + prompt v6 规则 |

改动文件：`TwoStageSynthesisService.java`、`prompts/two-stage-reasoning/{prompt.txt,version.txt}`、`TwoStageSynthesisTest.java`、`TwoStagePostgresIntegrationTest.java`、`Stage1ProbeRehearsalTest.java`。
