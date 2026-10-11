# Wave 3A — KG-1 风险接受记录

| 项 | 值 |
| --- | --- |
| 文档状态 | **已批准（APPROVED · A1）—— 2026-10-11 生效** |
| 接受对象 | KG-1：`StageResponseReader` 不做 item 级 fact↔span 交叉校验 |
| 分支 / HEAD | `feat/wave3a-e2e` / `b374bc8` |
| 日期 | 2026-10-11（批准日 = 起草日） |
| 本轮代码改动 | **无**（未修改 reader、协议、Schema、测试、验收标准） |

> **性质声明（必须遵守）**
> 1. 本文档**不是** KG-1 的技术修复记录，**不得**据此声称 KG-1 已修复或技术 PASS。
> 2. 本文档**不是** Review Gate 签署，也不改变 Review Gate 状态。
> 3. 本文档**不涵盖**第二轮 F5 变更的审查，**不涵盖** G4 真实验证。
> 4. KG-1 已由用户批准（A1）转为「已批准的已知边界」；该批准**仅**覆盖第 1 节定义的接受范围。

---

## 0. 决策记录（A1）

| 项 | 值 |
| --- | --- |
| 决策编号 | **A1** |
| 决策 | 接受 KG-1 作为当前 Wave 3A 契约允许范围内的**已知归属精确性限制** |
| 决策人 | 用户 |
| 决策日期 | 2026-10-11 |
| 决策依据 | 本文档 §4 证据链（Gate C 书面文本 + PR/EP 规则 + 契约字段结构：全库检索无任何强绑定要求） |
| 影响范围 | 见 §2（保留保证）与 §3（未实现能力）；受限、有界 |
| 残余风险 | 见 §8（R-1…R-5） |
| 后续整改 | R-6（独立立项）、R-7（本轮已完成，见 §9.1） |

**A1 明确不构成的批准**（决策人原话边界）：

1. 不构成对 KG-1 的技术修复；
2. 不构成降低验收标准；
3. 不构成修改 SourceRef 语义；
4. 不构成绕过 Review Gate；
5. 不构成 G4 真实验证的替代。

**批准后状态**：KG-1 由「未处理 P1」转为「**已批准的已知边界**」。
**注意**：该改判是用户的风险接受行为，**不是**技术修复结论；Review Gate 本身**未被自动改为 PASS**（见 §11）。

---

## 1. 接受范围的精确定义

**被接受的**：`StageResponseReader` 在 item 级不校验「该 item 的 `sourceRefs` 中的 span 是否属于其 `factRefs` 所引用的 fact」。

**未被接受的（不在本记录范围）**：

- 不放松任何 SourceRef 自身的校验（sourceId / 区间 / quote 逐字）；
- 不放松任何 ref 解析校验（PR-08 / PR-09）；
- 不放松任何 fact 层自洽校验（STG-03 / EP-05）；
- 不改动 `contracts/openapi.yaml`、`contracts/analysis-result.schema.json`、任何协议规则、任何验收门禁。

---

## 2. 已实现能力（本次接受不削弱这些保证）

| # | 保证 | 实现位置 | 状态 |
| --- | --- | --- | --- |
| C-1 | 每个 `SourceRef.sourceId` 必须等于被分析来源（PR-01） | `validateSpan` `:1241` | **强制** |
| C-2 | offset 必须 `0 ≤ start < end ≤ len`（PR-07） | `validateSpan` `:1244-1250` | **强制** |
| C-3 | `quote` 必须逐字等于 `substring(start,end)`（PR-07/PR-10） | `validateSpan` `:1251-1253` | **强制** |
| C-4 | `factRefs` / `supportingFactRefs` / `hypothesisRef` 必须在快照内解析（PR-08/PR-09） | `requireRefs` `:1215-1225`、`requireHypothesisRef` `:1193` | **强制** |
| C-5 | `facts[].sourceRefs` 逐个做 span 校验 | `:761` | **强制** |
| C-6 | fact 的 statement 必须等于其自身某个引用 span（STG-03） | `facts()` | **强制** |
| C-7 | 每个 FACT 至少一个 sourceRef（EP-05）；重复 span 折叠为一个 fact | `:757`、`:784` | **强制** |
| C-8 | FACT/INFERENCE/HYPOTHESIS/PREDICTION/UNKNOWN 分离与 provenance 保留 | Gate B / EP 规则 | **强制** |

**结论**：伪造 span、跨来源 span、篡改 offset/quote **全部仍被阻断**。

---

## 3. 未实现能力（被接受的缺口）

**U-1**：item 的 `sourceRefs` 与其 `factRefs` 之间没有归属校验。
因此「引用 F1、却展示 F2 的合法 span」会通过（`Stage2OutputOptimizationTest:645-660` 已将其固定为特性）。

**U-2（结构性）**：该绑定**在现有契约中无法表达**：

- `SourceRef` 的字段只有 `sourceId` / `quote` / `startOffset` / `endOffset`，且 `additionalProperties: false`
  → span 自身不携带「属于哪个 fact」的信息；
- 契约中 `sourceRefs` 是 **required**（Statement / Variable / CausalLink / StakeholderImpact / Hypothesis / CorroboratingSignal / Indicator / Fact 均必填），
  而 `factRefs` **从来不是 required**（仅为可选字段）。
  → 绑定是「必填字段 vs 可选字段」之间的 item 级规则，属 Schema 语义扩展，**不在本轮授权范围**。

**U-3**：即使实现，也只能覆盖同时声明两个字段的数组；`hypotheses`（无 `factRefs`）、
`predictions`（无 `sourceRefs`）、`corroboratingSignals` / `verificationIndicators`（无 `factRefs`）
等路径需另一套设计。

---

## 4. 为何这不构成契约违规（证据链）

| 依据 | 内容 |
| --- | --- |
| Gate C 书面文本 | 只要求「每个 SourceRef 逐字解析」+「每个 factRefs/hypothesisRef 在快照内解析」，**未要求 span 归属**（`ANALYSIS_PROTOCOL_V0_1.md` §7） |
| PR-07/08/09/10/11 | 均为「逐字 / 唯一 / 可解析 / 不得修复 / 无 provenance 只能是 UNKNOWN」，**无归属规则**（§5） |
| EP-04/EP-05 | 只要求「引用存在」（`EPISTEMIC_TYPES.md:113-114`） |
| 全文检索 | 在 `contracts/`、协议、Schema 影响评估、契约冻结文档中检索「span 归属 / 强绑定 / 交叉校验」，**零命中** |
| 协议 §9 一致性清单 | 第 5 条只要求「refs 在快照内解析」 |

→ 当前 reader **符合**书面契约；U-1 是**契约未规定**的边界，而非违反。

---

## 5. 对 SignalFrame 事实可信度的实际影响

| 维度 | 影响 | 说明 |
| --- | --- | --- |
| 可验证性 | **无影响** | 每个展示的 quote 仍是源文本逐字子串，读者可自行核对 |
| 反伪造 | **无影响** | 无法凭空造出源文本中不存在的证据 |
| 跨来源污染 | **无影响** | PR-01 仍强制 sourceId 一致 |
| **归属精确性** | **降低** | 读者不能假定「该 item 展示的 span 就是它所引用 fact 的证据」 |
| 影响上界 | **有界** | 仅限「同一快照内 ≥2 个真实 fact、生成方错配」的情形；只能**误归属真证据**，不能制造证据 |
| 既有缓解 | 部分 | Stage 2 prompt 已明令禁止挂用他 fact 的 span；两阶段路径向 Stage 2 提供 stage-1 的 fact id 映射 |

**要点**：这是**归属精度**缺陷，不是**证据真实性**缺陷。事实链「claim →（真实）fact →（逐字）源文本」两端仍然真实，未验证的是中间那条边的**配对**。

---

## 6. 是否使 Gate C / SourceRef / provenance 失去实质意义

**否。** 三点理由：

1. **Gate C 的两条保证都在强制执行**，且是本次接受**未触碰**的部分（见 §2 C-1…C-4）。
2. 被放宽的**不是** Gate C 的任何一条，而是 Gate C **从未声明**的一条附加一致性；
   接受本记录**不需要修改任何验收标准**。
3. 语义上，provenance 的核心承诺是「任何主张都指向真实来源的逐字文本、且引用可解析」——
   该承诺完整保留。U-1 只影响「指向的是不是**那一条** fact」，属精度而非真伪。

**但必须同时承认（不得淡化）**：Gate C 的实际保证范围**比直觉更窄**。
因此本边界必须被**显式记录**——这正是本文档存在的意义；若只改报告措辞而不记录，
才是真正意义上的"用措辞解决门禁"。

**先例**：项目已有「显式记录被削弱执行」的实践——`SCHEMA_IMPACT_ASSESSMENT.md` §7 SCH-03
明确记录「EP-05 不可执行，'no unsupported inference' 退化为 honour system」。

---

## 7. 与现有强制验收要求是否冲突

| 检查 | 结论 |
| --- | --- |
| 与协议 / Schema / PR / EP / Gate A–E 强制条款冲突？ | **无冲突**（§4 证据链） |
| 需要修改任何验收标准？ | **不需要** |
| 需要降低任何门禁？ | **不需要** |
| 是否存在过程性依赖？ | **存在 1 项**：见下 |

**唯一的过程性依赖（必须如实报告）**：
上一轮 Review Gate 的判定条件「无未处理 P0 **且** 无未处理 P1」来自该轮**任务书**，
而任务书**不在仓库内**（仅被 `WAVE3A_STAGE2_OPTIMIZATION.md:396` 引用）。
因此：

- 协议层面：**不冲突**，接受该边界无需改动任何规范；
- 过程层面：把 KG-1 从「未处理 P1」改判为「已批准边界」属于**用户的风险接受权**，
  **不能由我自行改判**，属于本记录请求批准的事项。

---

## 8. 剩余风险清单

| # | 风险 | 级别 | 说明 |
| --- | --- | --- | --- |
| R-1 | item 级 fact↔span 误归属不被拦截 | **已接受（待批）** | 仅限同快照内真实 fact 之间 |
| R-2 | 契约无法表达该绑定（SourceRef 无 fact 字段；factRefs 可选） | 结构性 | 修复需 Schema 语义扩展 → 需另行批准 |
| R-3 | 即使实现也只能覆盖 7/12 类产物 | 结构性 | 完整一致性需独立设计轮次 |
| R-4 | 下游消费者若假定「span 必属所引 fact」会误读 | 中 | 需在契约文档侧显式声明边界 |
| R-5 | 该边界目前仅由一份测试固定，未写入对外文档 | 中 | 建议纳入 R-6 整改任务 |

---

## 9. 后续整改任务与 R-7 验证

### 9.1 R-7 风险验证（**本轮已完成**）

**目的**：用可复现证据验证本记录的**双重主张** ——
(a) §2 的保留保证（C-1…C-4）**确实生效**；(b) §3 的缺口（U-1/U-2）**确实如所述**。

**(a) 边界行为验证 —— 具名测试，6/6 PASS**

```
./scripts/mvn.sh -B -f apps/api/pom.xml test \
  -Dtest='Stage2OutputOptimizationTest#aFabricatedSourceRefOnAVariableIsRejectedAsPR01
         +aTamperedSpanOnAStageTwoArtifactIsRejected
         +theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem
         +theCorrectSourceIdentityIsAccepted,
         TwoStageSynthesisTest#aSourceThatIsNotTheSnapshotSourceIsRejected
         +aFactReferenceThatStageOneNeverProducedIsRejected'
→ Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS
```

| 测试 | 验证的主张 | 结果 |
| --- | --- | --- |
| `aFabricatedSourceRefOnAVariableIsRejectedAsPR01` | C-1 伪造 sourceId 被拒 | **PASS** |
| `aTamperedSpanOnAStageTwoArtifactIsRejected` | C-2/C-3 篡改 offset/quote 被拒 | **PASS** |
| `aSourceThatIsNotTheSnapshotSourceIsRejected` | C-1 跨来源 span 被拒 | **PASS** |
| `aFactReferenceThatStageOneNeverProducedIsRejected` | C-4 不可解析 factRef 被拒 | **PASS** |
| `theCorrectSourceIdentityIsAccepted` | 正向对照（未误拒合法来源） | **PASS** |
| `theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem` | **U-1 缺口确实存在**（引用 F1 + 挂 F2 合法 span 通过） | **PASS（缺口按描述复现）** |

**(b) 契约层机械验证 —— 可复现**

```
SourceRef fields        : ['endOffset', 'quote', 'sourceId', 'startOffset']
SourceRef additionalProperties : False
SourceRef 是否含 fact 归属字段 : False          ← U-2：span 层无法表达归属
sourceRefs 为 required 的类型  : 8 个（Statement, Fact, Variable, CausalLink,
                                     StakeholderImpact, Hypothesis, Indicator, CorroboratingSignal）
factRefs  为 required 的类型   : 无             ← 绑定是「必填 vs 可选」规则
factRefs  存在但可选           : 6 个（Statement, Variable, CausalLink,
                                     StakeholderImpact, Evidence, EvidenceCreateRequest）
```

**R-7 结论**：保留保证 (a) 与已接受缺口 (b) **双向均被证据确认**；
接受该边界的判断建立在**已验证的事实**上，而非推断。

**R-7 未执行的部分（须说明）**：把 U-1 边界写入 `contracts/` 侧对外说明**未执行** ——
该动作触及契约表面，不属本轮授权（§11 边界 + 用户"不修改 Schema/Stage Contract"）。
已在 §12 记录为待授权事项。

### 9.2 R-6（独立后续整改事项，**本轮不执行**）

**「provenance 归属一致性」独立轮次** —— 状态：**已列项，待单独授权**。

建议顺序（任一步骤若触及 Schema/协议语义，须单独批准）：

1. 在 `ANALYSIS_PROTOCOL_V0_1.md` Gate C 或新增 PR 规则中**先**定义归属语义；
2. 明确规则形式：候选为「包含」或「重叠」——**「精确相等」已被 fixture 证据排除**
   （`StageOneSnapshot` 中 `F5[63,96)` 包含 `F3[70,84)` 与 `F4[85,95)`）；
3. 评估是否需要让 `factRefs` 在相关类型上成为必填（Schema 变更，需批准）；
4. 修改共享 reader，覆盖 3 条拓扑；
5. 补负向用例 + 全量回归；
6. 记录与 `StageOneSnapshot` 嵌套 fact 的兼容性结论。

**R-6 的关闭条件**：上述归属语义被写入协议、reader 实现、并有两向测试证据。

---

## 10. 重新评估触发条件（任一命中即须重评本接受）

1. 出现 SourceRef **伪造 / 跨来源 / 篡改**的成功案例（说明 C-1…C-3 失效）；
2. 下游消费者或研究侧（TASK-06/07）依赖「span 必属所引 fact」；
3. G4 真实验证通过后，LIVE 输出中出现**归属错配**的实证；
4. 协议新增 provenance 一致性规则；
5. 任一 Schema 变更使 `factRefs` 成为必填；
6. 第二轮 F5 变更的人工审查结论要求一并处理 U-1。

---

## 11. 批准效力与边界

**本记录已于 2026-10-11 依决策 A1 生效。** 其效力**仅限于**：
KG-1 由「未处理 P1」转为「已批准的已知边界」，且 §9.2 的 R-6 进入待办、§9.1 的 R-7 验证完成。

**明确不因本记录而改变的事项**：

- KG-1 的**技术状态**：仍未实现 fact↔span 归属校验（**不是**技术修复 PASS）；
- 第二轮 F5 变更的审查状态（**仍为独立人工门禁，尚未完成**）；
- 正式 Review Gate 的签署（**仍须人工**，**Codex 意见不可替代**，状态仍 **BLOCKED**）；
- **G4 仍为 FAIL**，不得用任何离线测试结果替代真实验证；
- 任何 Schema / SourceRef 语义 / Stage Contract / 验收标准。

### 11.1 尚待授权/审批的事项

| # | 事项 | 性质 |
| --- | --- | --- |
| P-1 | 把 U-1 边界写入 `contracts/` 侧对外说明（R-7 未执行部分，缓解 R-4/R-5） | 触及契约表面，需授权 |
| P-2 | R-6 立项与实施（触及协议/Schema 语义） | L3 |
| P-3 | 第二轮 F5 独立审查完成 | 人工门禁 |
| P-4 | 正式 Review Gate 签署 | 人工门禁 |
| P-5 | G4 真实 Provider 验证（需可用凭据） | 人工授权 + 真实费用 |

---

## 12. 证据索引

| 证据 | 位置 |
| --- | --- |
| reader 两个独立校验 | `StageResponseReader.java:1215-1225`、`:1227-1254` |
| 6 处成对调用 | `:816/817`、`:832/833`、`:171/172`、`:900/901`、`:285/286`、`:346/347` |
| 限制特性化用例 | `Stage2OutputOptimizationTest.java:645-660` |
| Gate C 文本 | `ANALYSIS_PROTOCOL_V0_1.md` §7 |
| PR-07…PR-11 | `ANALYSIS_PROTOCOL_V0_1.md` §5 |
| EP-04 / EP-05 | `EPISTEMIC_TYPES.md:113-114` |
| SourceRef 字段（无 fact 字段） | `contracts/openapi.yaml:1633`、`contracts/analysis-result.schema.json` §SourceRef |
| required 字段差异 | `contracts/analysis-result.schema.json`（`sourceRefs` required；`factRefs` 可选） |
| fixture 嵌套 span | `apps/api/src/test/java/com/signalframe/analysis/StageOneSnapshot.java` |
| 削弱执行的既有先例 | `SCHEMA_IMPACT_ASSESSMENT.md` §7 SCH-03 |
| 本轮验证 | `contracts:check` PASS；定点回归 65/65 PASS |
| **R-7 边界行为证据** | 具名测试 6/6 PASS（见 §9.1 命令与结果表） |
| **R-7 契约层证据** | `contracts/analysis-result.schema.json` 机械提取（见 §9.1 (b) 输出） |
| **正式决策记录** | 本文档 §0（决策 A1，2026-10-11） |
| Review Gate / F5 证据状态 | `docs/validation/WAVE3A_REVIEW_GATE_STATUS.md` |
