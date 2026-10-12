# Provenance 归属边界说明（非规范性文档）

| 项 | 值 |
| --- | --- |
| 文档性质 | **非规范性说明（NON-NORMATIVE）**——不是契约本体 |
| 状态 | KG-1 为**已批准的已知边界**（决策 A1，2026-10-11） |
| 契约工件改动 | `contracts/openapi.yaml`、`contracts/analysis-result.schema.json`、生成的 Java / TS：**逐字未改** |
| 生效范围 | Wave 3A 当前阶段（协议 v0.1 / `two-stage-reasoning-v4`） |

> **性质声明**
> 1. 本文档是 `contracts/` 目录下的**说明性文档**，**不参与**代码生成，**不参与** `contracts:check` 漂移校验。
> 2. 本文档**不新增、不删除、不放松**任何校验规则，**不改变** Reader 行为，**不改变**任何 Schema 字段。
> 3. 所描述的限制是**已知边界**，**不是**已被修复的技术缺陷；**R-6 未完成**。
> 4. 若本文档与规范性来源冲突，**以规范性来源为准**。

## 1. 规范性来源（本文档不覆盖它们）

| 工件 | 角色 |
| --- | --- |
| `contracts/openapi.yaml` | 契约**唯一规范性来源**（OpenAPI 文档） |
| `contracts/analysis-result.schema.json` | 由上述文件**生成**的 JSON Schema（请勿手工编辑） |
| `apps/api/src/main/java/com/signalframe/contract/*.java` | 由上述文件**生成**的 DTO（请勿手工编辑） |
| `apps/web/src/lib/api.generated.ts` | 由上述文件**生成**的 TS 客户端（请勿手工编辑） |
| `docs/analysis/ANALYSIS_PROTOCOL_V0_1.md` | 协议与验收门禁（Gate A–E、PR/STG 规则） |
| `docs/analysis/EPISTEMIC_TYPES.md` | 认知/证据规则（EP-01…EP-12） |

边界与证据的**权威记录**见 `docs/validation/WAVE3A_KG1_RISK_ACCEPTANCE.md`；
本文档是其**契约侧摘要**，不复制其证据表，以避免两处表述分叉。

## 2. 已经具备的证据真实性校验（**继续强制生效**）

| # | 保证 | 规则来源 |
| --- | --- | --- |
| A-1 | `SourceRef.sourceId` 必须等于被分析来源 | PR-01 |
| A-2 | offset 必须 `0 ≤ start < end ≤ 源文本长度` | PR-07 |
| A-3 | `quote` 必须逐字等于 `substring(start,end)` | PR-07 / PR-10 |
| A-4 | `factRefs` / `supportingFactRefs` / `hypothesisRef` 必须在快照内解析 | PR-08 / PR-09 |
| A-5 | `facts[].sourceRefs` 逐个做 span 校验；每个 FACT ≥1 sourceRef | EP-05 |
| A-6 | fact 的 `statement` 必须等于其自身某个引用 span；重复 span 折叠为一个 fact | STG-03 |
| A-7 | FACT / INFERENCE / HYPOTHESIS / PREDICTION / UNKNOWN 分离与 provenance 保留 | Gate B / EP 规则 |

**结论**：伪造 span、跨来源 span、篡改 offset 或 quote **均被阻断**；证据**真实性**不因本边界而削弱。

**契约侧结构事实**：`sourceRefs` 在 8 个契约类型上为 **required**；
`SourceRef` 仅含 `sourceId` / `quote` / `startOffset` / `endOffset` 且 `additionalProperties: false`。

## 3. 当前缺少的能力：item 级 fact↔span 归属绑定

**缺失内容**：产出物在 item 级**不做**「该 item 的 `sourceRefs` 中的 span 是否属于其 `factRefs` 所引用的 fact」的交叉校验。

**后果示例**：某条目引用 `F1`，却挂上 `F2` 的合法 span —— 两项各自均合法，因此**通过**全部校验。

**为何契约无法表达该绑定**（结构性，非实现疏漏）：

1. `SourceRef` 不携带「属于哪个 fact」的字段，且 `additionalProperties: false`
   → 归属信息在 span 层**无处存放**；
2. 契约中 `sourceRefs` 为 required，而 `factRefs` **在任何类型上都不是 required**（仅 6 个类型可选）
   → 该绑定是「必填字段 vs 可选字段」之间的 item 级规则，属 **Schema 语义扩展**。

**规范侧事实**：`ANALYSIS_PROTOCOL_V0_1.md` §7 Gate C 只要求
「每个 SourceRef 逐字解析」+「每个 factRefs/hypothesisRef 在快照内解析」，
**未要求 span 归属**；PR-07…PR-11 与 EP-04/EP-05 亦无此规则。
因此当前实现**符合**书面契约，本边界是**契约未规定的范围**。

**覆盖上界**：即使实现，也只能覆盖同时声明两个字段的产物；
`hypotheses`（无 `factRefs`）、`predictions`（无 `sourceRefs`）、
`corroboratingSignals` / `verificationIndicators`（无 `factRefs`）等路径需另行设计。

## 4. 对事实归属精确性的影响

| 维度 | 影响 |
| --- | --- |
| 证据真实性 / 反伪造 / 跨来源污染 | **无影响**（见 §2 A-1…A-3） |
| 引用可解析性 | **无影响**（见 §2 A-4） |
| **归属精确性** | **降低**：不应假定「某条目展示的 span 就是它所引用 fact 的证据」 |
| 影响上界 | **有界**：仅限同一快照内 ≥2 个真实 fact 且生成方错配；只能**误归属真证据**，不能制造证据 |
| 既有缓解 | Stage 2 prompt 已明令禁止挂用他 fact 的 span；两阶段路径向 Stage 2 提供 stage-1 的 fact id 映射 |

**定性**：这是**归属精度**问题，**不是证据真实性**问题；
链「claim →（真实）fact →（逐字）源文本」两端仍真实，未验证的是中间那条边的配对。

## 5. 为什么当前阶段允许接受这一限制

1. **不违反任何规范性要求**（§3：Gate C 与 PR/EP 均未要求该绑定）；
2. **不削弱任何事实真实性保证**（§2 全部继续强制）；
3. **影响有界且可检测**：误归属只发生在同快照内的真实 fact 之间，且已由测试固定为可复现边界；
4. **修复需先动 Schema/协议语义**（§3 结构性原因），属 L3 变更，
   在 Wave 3A 当前阶段不具备授权前提；
5. 风险已由用户按决策 **A1** 明确接受，并配套整改项 **R-6**。

## 6. 继续有效的验收标准（**未被本边界修改或降低**）

- Gate A — 结构化校验；Gate B — 认知规则（EP-01…EP-12）；
- **Gate C — provenance**：SourceRef 逐字解析 + refs 在快照内解析（**全文继续有效**）；
- Gate D — 置信度由 rubric 复算；Gate E — 完整性（无静默空数组）；
- PR-01、PR-05、PR-07…PR-11、PR-13、PR-16 等协议规则；
- `contracts:check` 契约漂移门禁；`Stage2ContractConsistencyTest` 同源性锁定；
- AGENTS.md 的架构、契约、迁移、prompt 版本与安全约束。

> **注意**：Gate C 的**保证范围比直觉更窄**——它从未承诺 span 归属。
> 该差异在此显式记录，而非通过措辞处理。

## 7. R-6 整改边界与触发条件

**R-6 状态：已列项，未启动，未完成。**

**整改边界（实施时必须遵守）**：

1. **先在规范性来源定义语义**：`ANALYSIS_PROTOCOL_V0_1.md` 的 Gate C 或新增 PR 规则；
2. 规则形式候选：「包含」或「重叠」——**「精确相等」已被 fixture 证据排除**
   （`StageOneSnapshot` 中 `F5[63,96)` 包含 `F3[70,84)` 与 `F4[85,95)`）；
3. 若需让 `factRefs` 成为 required，属 **Schema 变更**，须单独批准后再改 `openapi.yaml` 并重新生成；
4. 修改共享 Reader 会波及 Stage 1 / staged 拓扑 / monolith **三条路径**，须一并回归；
5. 不得借此放松任何既有校验（§6）。

**触发条件（任一命中即须重评本边界）**：

1. 出现 SourceRef 伪造 / 跨来源 / 篡改的成功案例；
2. 下游消费者或研究侧（TASK-06/07）依赖「span 必属所引 fact」；
3. G4 真实验证通过后，LIVE 输出中出现归属错配的实证；
4. 协议新增 provenance 一致性规则；
5. 任一 Schema 变更使 `factRefs` 成为 required。

## 8. 交叉引用

| 内容 | 位置 |
| --- | --- |
| 风险接受决策与证据链 | `docs/validation/WAVE3A_KG1_RISK_ACCEPTANCE.md` §0、§4 |
| R-7 边界行为与契约层验证证据 | `docs/validation/WAVE3A_KG1_RISK_ACCEPTANCE.md` §9.1 |
| 已接受缺口与结构性原因 | `docs/validation/WAVE3A_KG1_RISK_ACCEPTANCE.md` §3 |
| Review Gate 与 F5 审查状态 | `docs/validation/WAVE3A_REVIEW_GATE_STATUS.md` |
| 边界特性化用例 | `Stage2OutputOptimizationTest.theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem` |
