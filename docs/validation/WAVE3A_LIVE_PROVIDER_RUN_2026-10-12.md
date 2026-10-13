# Wave 3A — 真实 provider 实跑记录（OpenCode Go · deepseek-v4.1-flash）

| 项 | 值 |
| --- | --- |
| 日期 | 2026-10-12（Asia/Shanghai） |
| 分支 | `feat/wave3a-v5-prompt` |
| 基线 | `5eba77b`（`feat/wave3a-e2e` 尖端） |
| 本文档性质 | **新增验证记录**。不修改任何既有文档，不改变任何门禁状态，**不构成人工审查或签署** |
| 执行者 | DeepSeek Harness（AI Agent）。**不是**人工审查者，**无权**签署 F5 或 Review Gate |
| 真实调用 | 14 次 strict-tier 实跑 + 3 次手工端点探针，全部走用户已授权的 OpenCode Go 套餐 |
| G4 状态 | **先 FAIL（120 s 上限下），上限放宽并修复两处引用顺序后 PASS** —— 见 §9 |

---

## 0. 结论

真实 provider 通路**本身可用**：鉴权、结构化输出、模型身份、延迟、错误分类、审计全部正确。

在 120 秒单次上限下，`deepseek-v4.1-flash` **无法满足** `AnalysisResult` 严格契约（阻塞理由从"没有可用凭据"改为两条可复现的硬约束，§4）。**该上限经用户批准放宽到 600 秒，并修复了两处引用顺序缺陷之后，strict tier 全绿 —— G4 PASS**（§9）。

本轮**发现并修复了两处真实缺陷**（§3、§9.2）：两阶段路径下，机制端点与二阶影响派生都用错了校验状态，导致任何合法的同回复引用都被拒绝。这不是模型问题，是代码问题。

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
| `516ab84` | `feat/wave3a-v5-prompt` | 契约上限 120 s → 600 s（含镜像常量与生成物） |
| `b1ff29f` | `feat/wave3a-v5-prompt` | 二阶影响派生顺序修复 + 两处测试随动 |

改动文件：`TwoStageSynthesisService.java`、`prompts/two-stage-reasoning/{prompt.txt,version.txt}`、`TwoStageSynthesisTest.java`、`TwoStagePostgresIntegrationTest.java`、`Stage1ProbeRehearsalTest.java`、`RealModelEndToEndTest.java`、`contracts/openapi.yaml` 及其生成物。

---

## 9. 上限放宽后的通过结果（同日追加）

### 9.1 通过配置

```sh
export ANALYSIS_PIPELINE_MODE=two-stage
export AI_FAST_MAX_TOKENS=32000 AI_FAST_TIMEOUT=600
export AI_TWO_STAGE_FACTS_MAX_TOKENS=32000    AI_TWO_STAGE_FACTS_TIMEOUT=600
export AI_TWO_STAGE_REASONING_MAX_TOKENS=32000 AI_TWO_STAGE_REASONING_TIMEOUT=600
export AI_TWO_STAGE_FACTS_MAX_ATTEMPTS=2 AI_TWO_STAGE_FACTS_MAX_REPAIR_ATTEMPTS=2
export AI_TWO_STAGE_REASONING_MAX_ATTEMPTS=2 AI_TWO_STAGE_REASONING_MAX_REPAIR_ATTEMPTS=2
export AI_TWO_STAGE_MAX_PROVIDER_CALLS=8
export AI_REAL_MODEL_E2E=true AI_REAL_MODEL_E2E_ANALYSIS=true
export AI_REAL_MODEL_E2E_TIMEOUT_SECONDS=600 AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS=1800
export SPRING_APPLICATION_JSON='{"ai":{"client":{"reasoning-effort":{"FACT_EXTRACTION":"high","SYNTHESIS":"medium"}}}}'
```

推理档是必要项：`FACT_EXTRACTION=high` 是 stage 1 达到逐字引文与精确 offset 的前提（low/medium 下多次失败，§5 第 4、10 行）；`SYNTHESIS=medium` 是 stage 2 在引用规则上保持严谨的前提。

### 9.2 第二处同类缺陷（STG-08）

上限放宽后 stage 2 走到更深处，暴露同一类顺序问题：**二阶影响 `derivedFromRefs` 必须指向本回复声明的一阶影响**，而校验用的仍是回复前的状态，因此必然解析失败。

修复与 §3 同构：`state.withFirstOrderEffects(firstOrder)`。

### 9.3 结果

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 离线全套 | `./scripts/mvn.sh -f apps/api/pom.xml test` | **662 tests, 0 failures, 8 skipped**（合并进 `main` 后） |
| 真实 strict tier | `-Dtest=RealModelEndToEndTest`（配置见 §9.1） | **5 tests, 0 failures, 0 skipped** |

strict tier 断言的事实（通过时全部成立）：facts 带精确原文 span（`NEWS_TEXT.substring(start,end).equals(quote)`）、`demo=false`、审计记录真实 provider 与 model 且有实测延迟、`estimatedCost` 保持未知、凭据与原文不进入审计、Web 详情页可读。

### 9.3.1 稳定性：通过是概率性的

同一配置、同一样本重复运行 4 次，**2 次通过、2 次失败**，失败点各不相同：

| 次 | 结果 | 失败规则 |
| --- | --- | --- |
| 1 | **PASS** | — |
| 2 | FAIL | `effects[]: derivedFromRefs does not resolve to a first-order effect (STG-08)` |
| 3 | **PASS**（prompt 补入 `derivedFromRefs` 规则后） | — |
| 4 | FAIL | `falsificationConditions[]: the decision boundary must be observable and decidable by a third party (STG-12)` |

结论：**代码缺陷已消除（§3、§9.2），剩余失败是模型对严格协议规则的遵从度问题**，且不同次失败在不同规则上，因此不是某一条规则没写好。

### 9.3.2 诊断：偏移算错是主因（§10 已修）

把一次被拒的 stage-1 回复原始截获后逐条比对（609 字中文原文，19 条 fact）：

| 项 | 结果 |
| --- | --- |
| 引文是原文逐字子串 | **19 / 19 正确** |
| `startOffset`/`endOffset` 正确 | **0 / 19** —— 偏差 0～2 个字符，与换行数、累计长度均无对应关系 |

即：模型能把原文片段一字不差地抄出来，**但算不准它在原文中的绝对位置**。校验器要求 `text.substring(start,end) == quote`，因此每一次分析都死在偏移算术上，而不是死在证据质量上。这解释了此前的全部现象：为什么偶尔能过、为什么 `high` 档烧掉 2.6 万输出 token 也没用（它在反复重算数不出来的偏移）。

### 9.4 口径与限制

1. **通过的是两阶段拓扑**。默认的单体（draft）拓扑在 600 秒下不再超时，但输出未通过校验（`OUTPUT_INVALID`），因此 G4 的通过结论**只对两阶段模式**成立。
2. 单一样本；稳定性已量化：**修复前 2/4，实施 §10 后 3/4**（§10.4）。G4 的"通过"应表述为**可复现但尚未持续稳定**。
3. 实际使用含义：真实分析**多数成功、偶尔以 `OUTPUT_INVALID` 失败**（失败会被分类、逐次审计，并可在界面上重试）；失败不会被降级为 Mock，也不会放宽校验。
4. **本文档不构成审查或签署**。`WAVE3A_REVIEW_GATE_STATUS.md` 的 Review Gate 状态**不由本文档改变**；B-b（人工审查）与 B-c（签署）仍开放。
5. 按 `WAVE3A_GATE.md` 自身口径，`READY FOR MERGE` 要求 G1–G6 全 PASS；本记录给出 G4 的通过证据（含其不稳定性），其余五项沿用它原有结论。合并与否由人工决定；本轮已按用户指示将分支并入 `main`（本地，未 push）。

---

## 10. 服务端 span 推导（方案 C，经用户批准）

### 10.1 授权

`WAVE3A_G4_READINESS.md` 与本文档 §7 都把"由服务端定位逐字子串并回填 offset"列为**设计变更、需人工批准**。用户于本轮明确批准实施。该变更触及核心 provenance 规则（PR-07/PR-10）的语义，故在此单独记录。

### 10.2 规则（窄化实现）

| 情形 | 处理 |
| --- | --- |
| 引文在原文中逐字出现 | 由出现位置推导 `startOffset`/`endOffset`，忽略模型给的数字 |
| 引文出现多次 | 取"离模型声称位置最近"的那一次，规则确定、且尊重模型意图 |
| 引文**不是**原文逐字子串 | **原样保留**，仍由协议校验拒绝 —— 本规则不会把编造的引文变成可接受 |
| 回复无法解析 | 原样返回，由调用方解析器报告格式问题 |

实现位置：`shared/SourceSpanResolver`；在 `StageModelClient`（覆盖所有阶段与拓扑）与单体路径各接入一处，因此**没有逐 artifact 的散落改动**。

### 10.3 随之更新的既有断言

`TwoStageSynthesisTest` 中"offset 错即拒绝"的用例已按新语义改写为两条：逐字引文 → 推导而非拒绝；**原文中不存在的引文 → 仍然拒绝**。后者是本变更不可退让的边界。

### 10.4 验证

| 项 | 结果 |
| --- | --- |
| 离线全套 | **669 tests, 0 failures, 8 skipped**（含新增 6 个推导用例） |
| 真实 strict tier（修复前） | 4 次中 2 次通过 |
| 真实 strict tier（实施后，同配置同输入） | 4 次中 **3 次通过**；`facts[].sourceRefs` **不再是任何一次的失败原因** |
| 剩余失败 | `falsificationConditions[]` 的判定边界（STG-12）：模型偶尔仍写成不含比较的散文 |

### 10.5 仍未解决

`STG-12` 与"原文未确立阈值时不得编造数字"之间的张力仍然存在，本轮**未改动校验器**。已按校验器认可的形式收紧 prompt（必须写成显式比较：数字、`>`/`<`/`=`、或 超过/低于/之前/之后），把通过率从 2/4 提到 3/4，但模型仍偶有不遵从。彻底解决需要在"放宽该校验"与"接受偶发失败"之间做人工取舍。

---

## 11. 第三处缺口：模型不知道当前时间（STG-14.1）

### 11.1 现象

用户在本地以两阶段模式提交一条真实新闻（任务 `4350ff7f`，总耗时 9 分 22 秒）：

| 调用 | 结果 | 耗时 | 输出 token |
| --- | --- | --- | --- |
| stage 1 抽事实 | **SUCCEEDED** | 107.8 s | 12,575 |
| stage 2 第 1 次 | FAILED / TRANSPORT | 103.3 s | — |
| stage 2 第 2 次 | FAILED / OUTPUT_INVALID | 107.3 s | 16,147 |
| stage 2 第 3 次 | FAILED / OUTPUT_INVALID | 136.2 s | 21,671 |
| stage 2 第 4 次 | FAILED / OUTPUT_INVALID | 106.3 s | 16,426 |

最终错误：`predictions[]: expectedBy must be in the future relative to snapshot creation (STG-14.1)`。

两点值得记录：**stage 1 首次成功**（§10 的 span 推导生效，此前它必然死在 span 上）；而 stage 2 的失败换成了日期规则。

### 11.2 根因

校验器要求 `predictions[].expectedBy` 晚于 `state.snapshotCreatedAt()`，但装配 stage-2 prompt 时**从未把该时刻（或任何"当前时间"）告知模型**。模型只能凭自身训练窗口推断日期，必然落在快照时间之前——**规则要求未来，却不告诉它现在**。

### 11.3 修复

在 `reasoningPromptText` 中随 prompt 一并给出参考时刻，与既有"告知被分析来源 id"的做法同构：

```
The snapshot was created at <instant> (UTC). Any predictions[].expectedBy must be
strictly after that instant: choose a date in the future relative to it, not one
from the source's own era.
```

未改动校验器，也未放宽任何规则。

### 11.4 验证

| 项 | 结果 |
| --- | --- |
| 离线全套 | **669 tests, 0 failures, 8 skipped** |
| 真实 strict tier | **2 次运行均 5 tests, 0 failures** |

### 11.5 口径

至此已消除的失败原因：span 偏移算术（§10）、`mechanisms[].from` 与 `effects[].derivedFromRefs` 的校验状态顺序（§3、§9.2）、`expectedBy` 缺少参考时刻（§11）。**仍开放**的是 `STG-12`（判定边界的比较形式），其彻底解决需人工在"放宽校验"与"接受偶发失败"之间取舍（§10.5）。本文档仍不构成审查或签署。
