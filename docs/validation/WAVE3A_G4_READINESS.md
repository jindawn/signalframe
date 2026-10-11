# Wave 3A — G4 真实验证就绪说明（G4 Readiness）

| 项 | 值 |
| --- | --- |
| **G4 当前状态** | **FAIL（未变）** |
| G4 定义 | Full Real AnalysisResult —— 真实 provider 跑通完整分析链并在 Web 可见 |
| 分支 / HEAD | `feat/wave3a-e2e` / `b374bc8` |
| 本文档性质 | **就绪说明与实施计划**——不构成验证、不构成授权 |
| 权威来源 | `docs/validation/WAVE3A_GATE.md` §6/§7（G4 与 blocker） |

> **禁止事项**：不得以任何离线测试结果替代 G4 真实验证；
> 未获授权不得发起真实付费 provider 请求（§8）。

---

## 1. 为什么仍是 FAIL

| id | blocker | 影响 |
| --- | --- | --- |
| **B1** | **没有可用的、具备契约能力的 provider 凭据** | **G4 FAIL 的直接原因**。本地 1B–8B 模型无法产出满足 `AnalysisResult` 的输出 |
| B2 | 任务书提及的"Huawei–Qualcomm 专利协议"输入材料不在仓库内 | 不阻塞机制验证；现有 fixture 是仓库内实际存在的材料 |
| B3 | synthesis prompt framing（既有、已记录） | 加剧小模型的 `OUTPUT_INVALID`；需有能力 provider 才能验证 |

**已实测的根因**（`WAVE3A_GATE.md` §6.1）：分析 prompt 规模巨大（§6.1 记为 ≈ **14.5 k tokens**，
其中 ≈13 k 为 `AnalysisResult` JSON Schema），且契约要求每条 fact 的 `statement` 是源文本的
**逐字子串 + 精确 UTF-16 offset**。

> **口径说明（避免与 §6.1 的数值看起来矛盾）**：两处数值的**统计口径不同**。
> 本仓库运行日志给出的一次实测装配为
> `instructions=1001 guidance=1931 schema=13184 source=325 contract=3872 total=20313`；
> 其中 `schema`（13,184）与 §6.1 的 "~13 k Schema" 一致，
> 差额来自是否把 `guidance` 与 `contract` 计入。**两者不矛盾，只是包含项不同。**
> 为避免误读，本计划在预算估算中采用**更保守的** 20,313 口径。

gemma3:1b 与 llama3:8b 均无法满足 —— **这是 provider 能力问题，不是运行时缺陷**。

`WAVE3A_GATE.md` §6 明确记录：运行时行为**完全符合设计** —— 调用被尝试、输出按与生产相同的
schema 与 provenance 规则校验、允许一次修复、分类并逐次审计；**没有任何校验被放松，也没有静默降级为 Mock**。

---

## 2. 需要用户提供的最小条件

| # | 需要提供 | 说明 |
| --- | --- | --- |
| N-1 | **provider 类型** | 取值之一：`openai-compatible`（当前适配器支持） |
| N-2 | **model id** | 必须是 provider 实际存在的 id；**不得由营销名称推测** |
| N-3 | **base URL** | OpenAI 兼容根地址（例如 `https://<host>/v1`） |
| N-4 | **API key** | 通过环境变量注入；**不得写入仓库**（`.env` 已在 `.gitignore`） |
| N-5 | **费用授权** | 明确允许本次真实调用及其产生的费用 |
| N-6 | （可选）输入材料 | 若要求使用任务书指定的新闻材料，需提供该文本 |

**能力要求**：该模型需能产出符合契约的 JSON（含逐字中文 span 与精确 UTF-16 offset）。
经验上 1B–8B 本地模型不满足；需要具备结构化输出能力的中大型模型。

---

## 3. 分层验证策略（**强烈建议先做 Tier 1**）

| 层 | 测试 | 触发条件 | provider 调用 | 目的 |
| --- | --- | --- | --- | --- |
| **Tier 0（¥0）** | `Stage2LiveProbeTest` + 本地 `qwen3:8b` | `AI_STAGE2_LIVE_PROBE=true`，`AI_FAST_BASE_URL=http://127.0.0.1:11434/v1`、`AI_FAST_API_KEY=ollama` | **恰好 1 次**（本地） | **零成本**判定本机模型是否具备契约能力；**从未试过 `qwen3:8b`** |
| **Tier 1（便宜）** | `Stage2LiveProbeTest` + 付费模型 | `AI_STAGE2_LIVE_PROBE=true` | **恰好 1 次**（`max-provider-calls=1`、0 重试、0 修复） | 只验 Stage 2 契约能否被真实模型满足；输入是固定的 `StageOneSnapshot`，**每次对比同一输入** |
| **Tier 2（完整 G4）** | `RealModelEndToEndTest` | `AI_REAL_MODEL_E2E=true`（+ 严格层 `AI_REAL_MODEL_E2E_ANALYSIS=true`） | 受预算约束（见 §4） | 完整 news → analysis 链 + Web 可见；**这才是 G4** |

**建议顺序**：先用 Tier 1 花 1 次调用判断该模型是否具备契约能力；通过后再做 Tier 2。
这样可避免在一次不合适的模型上浪费完整 pipeline 的调用。

---

## 4. 精确命令与调用/token 上界

### Tier 1 —— Stage 2 单阶段真实探针

```sh
AI_STAGE2_LIVE_PROBE=true \
AI_FAST_PROVIDER=openai-compatible AI_FAST_MODEL=<authorized-id> \
AI_FAST_BASE_URL=<authorized-root> AI_FAST_API_KEY=<secret> \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=Stage2LiveProbeTest
```

**上界**：provider 调用 **1 次**；`max_tokens=16384`、`reasoning_effort=high`、`timeout=120s`（测试内固定）。

### Tier 2 —— 完整 G4

```sh
AI_REAL_MODEL_E2E=true AI_REAL_MODEL_E2E_ANALYSIS=true \
AI_FAST_PROVIDER=openai-compatible AI_FAST_MODEL=<authorized-id> \
AI_FAST_BASE_URL=<authorized-root> AI_FAST_API_KEY=<secret> \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=RealModelEndToEndTest
```

可选超时覆盖：`AI_REAL_MODEL_E2E_TIMEOUT_SECONDS`（默认 120）、
`AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS`（默认 900）。

### 调用与 token 上界（**可预算**）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 每次分析的重试上界 | `max-attempts=2`（transport）+ `max-repair-attempts=1`（schema/provenance 修复） | `application.yml` `ai.retry` |
| 两阶段硬上限 | `ai.two-stage.max-provider-calls=4`（覆盖两阶段全部尝试，含重试与修复） | `application.yml` |
| 单次 monolith synthesis prompt | ≈ **20,313 tokens**（实测） | 运行日志 |
| Stage 2 prompt | ≈ **3,978 tokens（ESTIMATED**，按 15,915 字符 / 4） | `Stage2OutputOptimizationTest` 输出 |
| Tier 1 总 token | **≤ ~20k**（1 次调用：≤4k in + ≤16,384 out） | 上述配置 |
| Tier 2 总 token | 有界：5 个用例，连通性/未知模型/超时/严格链各受预算约束；**建议按 ≤10 次调用、≤200k input + ≤32k output 预留** | 保守上界，非实测 |

**费用：见 §4.2（已按公开定价给出人民币估算与预算上限）。**
在用户提供 N-1…N-4 之前，**不得**发起真实调用，也不得据历史正文反推输出规模。

---

## 4A. Provider 可用性预检（2026-10-11，只读）

### 4A.1 本地可用（**¥0 成本**）

| 项 | 结果 |
| --- | --- |
| Ollama 端点 | `http://127.0.0.1:11434` **可达** |
| 本地模型 | `qwen3:8b`、`llama3:latest`、`gemma3:1b` |
| 已实测过的 | `gemma3:1b`、`llama3:latest` —— 均 **OUTPUT_INVALID**（`WAVE3A_GATE.md` §6.1） |
| **从未实测过的** | **`qwen3:8b`** —— 8.2B、支持 reasoning，**是本轮新发现的零成本候选** |

> **建议**：Tier 1 应**优先用 `qwen3:8b` 本地跑一次**（¥0）。
> 若它通过，则 G4 无需任何付费 provider；若失败，再考虑付费路线。
> 这是本轮预检最重要的结论——此前所有失败都发生在**更弱**的模型上。

### 4A.2 付费路线（.env.example 默认指向 DeepSeek）

`AI_FAST_BASE_URL` 默认 `https://api.deepseek.com`，`AI_FAST_PROVIDER` 需设为 `openai-compatible`。
该端点当前**未配置可用密钥**（`AI_FAST_API_KEY` 为空），故付费路线**未就绪**。

**认证 / 速率 / 调用风险**：

| 风险 | 说明 |
| --- | --- |
| 认证 | 需有效 API key；**不得**写入仓库（`.env` 已忽略） |
| 速率限制 | DeepSeek 并发上限高（见 §4.2 引用）；本地 Ollama 无速率限制但受显存约束 |
| 费用 | 见 §4.2；需用户设定上限 |
| 数据 | 分析文本为**测试 fixture**（非真实新闻），不涉及敏感数据 |
| 超时 | Tier 2 有 `AI_REAL_MODEL_E2E_JOB_TIMEOUT_SECONDS=900` 上限，不会无限挂起 |
| 失败面 | 失败只会使 G4 保持 FAIL，**不影响**任何已有 PASS |

---

## 4B. 成本估算（人民币）

### 4B.1 定价来源

DeepSeek 官方定价页（**外部数据，仅供估算**，价格可能变动）：
[Models & Pricing — DeepSeek API Docs](https://api-docs.deepseek.com/quick_start/pricing)（2026-10-11 获取）

| 模型 | 输入（cache miss） | 输入（cache hit） | 输出 |
| --- | --- | --- | --- |
| `deepseek-flash` | $0.15 / M（off-peak）· $0.30 / M（peak） | $0.003 / M · $0.006 / M | $0.60 / M · $1.20 / M |
| `deepseek-v4-pro` | $0.66 / M · $1.32 / M | $0.022 / M · $0.044 / M | $1.98 / M · $3.96 / M |

> Peak = UTC 周一至周五 01:00–04:00 与 06:00–10:00；其余时段（含周末与中国法定假日）为 off-peak。
> 汇率按 **1 USD ≈ 7.1 CNY** 折算（**假设值**，非实时汇率）。

### 4B.2 估算（按 `deepseek-flash`，保守取 **peak + 全部 cache miss**）

| 层 | 调用 | 输入 | 输出 | USD（最坏） | **CNY（最坏）** | CNY（off-peak） |
| --- | --- | --- | --- | --- | --- | --- |
| **Tier 1** | 恰好 1 次 | ≈4 k | ≤16.4 k | ≈ $0.021 | **≈ ¥0.15** | ≈ ¥0.07 |
| **Tier 2** | ≤10 次 | ≤200 k | ≤32 k | ≈ $0.098 | **≈ ¥0.70** | ≈ ¥0.35 |
| **Tier 1 + Tier 2** | — | — | — | ≈ $0.12 | **≈ ¥0.85** | ≈ ¥0.42 |
| **本地 `qwen3:8b`** | 1 次 | ≈4 k | ≤16.4 k | **$0** | **¥0** | **¥0** |

**注意**：prompt 中 ≈13 k 是**稳定的 schema**，属理想 cache-hit 场景；
若命中，输入成本降至约 1/50，总费用进一步趋近于零。

### 4B.3 建议预算上限

| 项 | 建议值 | 依据 |
| --- | --- | --- |
| 单次 Tier 1 上限 | **¥1** | 最坏 ¥0.15 的 ~6 倍冗余 |
| G4 总预算上限 | **¥50**（≈$7） | 最坏 ¥0.85 的 ~58 倍，足以覆盖多轮重试、换模型复测与超时层 |
| 硬性拒绝线 | 任何单次超过 **¥10** | 超出即停止并回报 |

**结论**：G4 的费用风险**极低**；真正的瓶颈是**模型能力**而非成本。
因此**推荐先跑 ¥0 的本地 `qwen3:8b`**，仅在失败后再申请付费授权。

---

## 5. 预声明判据（**先定判据，避免事后解释**）

| 观测 | 判读 |
| --- | --- |
| `PROBE2 status=SUCCEEDED` 且 `errorType=null`（Tier 1） | provider 与 schema 均通 |
| 无 `PR-01` / `PR-07` / `PR-10` | 来源身份链成立 |
| 无 `required artifact ... is absent` | 13 个数组全部出现 |
| `outputTokens` 明显低于历史 14,928（Tier 1） | 预算生效（**ESTIMATED 预期，非承诺**） |
| 仍为 `TIMEOUT` 且 reasoning 主导 | 瓶颈在 reasoning 而非可见输出 → 转 reasoning-effort / 预算治理 |
| Tier 2 用例 `analysisChainCompletesThroughApiAndIsVisibleToTheWebDetail` PASS | **G4 PASS** |
| 任何失败 | **G4 仍为 FAIL**，记录实际 errorType 与审计行，不解释为部分通过 |

---

## 6. 实施步骤

1. **用户提供** N-1…N-5（建议同时给出费用上限与单次调用上限）。
2. 将凭据写入本地 `.env`（**不入库**），或按 §4 以环境变量注入。
3. 运行 **Tier 1**，保存完整输出（含 `ModelRun` 审计行与 `PROBE2` 结论）。
4. 按 §5 判读；若契约能力不足 → 停止，回报该模型 id 与失败分类（**不追加调用**）。
5. 若 Tier 1 通过 → 运行 **Tier 2**，保存 5 个用例结果与 Job/审计记录。
6. 复核 **无** schema / provenance / epistemic 校验被放松，且**无静默降级为 Mock**。
7. 记录结果并更新 `WAVE3A_GATE.md` 的 G4 行（**PASS 或 FAIL，据实**）。
8. 若 G4 通过进入评估：`WAVE 3A READY FOR MERGE` 仍要求 **G1–G6 全部 PASS**。

---

## 7. 与 KG-1 接受的关系

**无关系。** KG-1（fact↔span 归属精确性，已按 A1 接受）**不**影响 G4：
G4 验证的是真实 provider 能否产出**通过现有全部校验**的输出；
KG-1 描述的是现有校验**未覆盖**的一条附加一致性。

若 G4 通过后 LIVE 输出中出现**归属错配的实证**，
将触发 `WAVE3A_KG1_RISK_ACCEPTANCE.md` §10 的第 3 条重新评估条件。

---

## 8. 禁止事项

- ❌ 不得用离线测试、Mock 或既有历史结果替代 G4 真实验证；
- ❌ 未获授权不得发起真实付费 provider 请求；
- ❌ 不得为通过 G4 而放松任何 schema / provenance / epistemic 校验；
- ❌ 不得静默降级为 Mock；
- ❌ 不得由营销名称推测 model id；
- ❌ 不得把 API key 写入仓库或审计。

---

## 9. 重新评估触发条件

1. 用户提供 N-1…N-5 → 本计划进入可执行状态；
2. provider 能力或定价变化导致上界失效；
3. Tier 1 结果改变契约能力判断（例如某本地模型被证明可用）；
4. 协议或 prompt framing（B3）发生变更。
