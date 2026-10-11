# Wave 3A — 遗留 P1 决策请求（reader fact↔span 交叉校验）

日期：2026-10-11 · 分支 `feat/wave3a-e2e` · HEAD `b374bc8`
**状态：已答复（CLOSED）—— 用户已批准方案 A1（2026-10-11）**
本轮性质：**只读调查 + 决策请求**。未修改任何业务源码、测试、契约或已有文档。

> **本文档是历史决策请求，已被答复，请勿据此判断当前状态。**
> 当前权威状态见：
> - KG-1 风险接受（A1 已生效）→ `WAVE3A_KG1_RISK_ACCEPTANCE.md`
> - Review Gate 与 F5 审查状态 → `WAVE3A_REVIEW_GATE_STATUS.md`
> - 契约侧边界说明 → `contracts/PROVENANCE_BOUNDARY.md`
>
> 本文档 §6 的方案 A 之所以标注"需用户签署"，是**提出时**的状态；该签署已完成。

> 本文档代替对 `WAVE3A_STAGE2_OPTIMIZATION.md` 的改写。上一轮报告是历史验证记录，
> 按 PR-17 的精神保持原样，不追改。

---

## 1. 一句话结论

遗留 P1（`StageResponseReader` 不做 fact↔span 交叉校验）**不是普通代码缺陷**：
按 `ANALYSIS_PROTOCOL_V0_1.md` §7 Gate C 的书面文本，当前 reader **符合**协议。
要修它等于**扩张协议语义与三条拓扑共用的校验严格度**，属于 L3 变更，
必须由用户决定是否做、以及用哪条规则做。本轮**未擅自修改**。

---

## 2. 事实基础（可复核）

| # | 事实 | 位置 |
| --- | --- | --- |
| F-1 | `requireRefs` 只校验 fact id 能在快照内解析 | `StageResponseReader.java:1215-1225` |
| F-2 | `requireSourceRefs` → `validateSpan` 只校验 sourceId 匹配（PR-01）、区间与顺序（PR-07）、quote 逐字（PR-07/PR-10） | `StageResponseReader.java:1227-1254` |
| F-3 | 两个校验成对出现但彼此独立，共 6 处数组 | 行 `816/817`（variables）、`832/833`（mechanisms）、`171/172`（stakeholders）、`900/901`（effects）、`285/286`（alternatives）、`346/347`（counterArguments） |
| F-4 | 协议 Gate C 的书面要求 = 每个 SourceRef 逐字解析 + 每个 factRefs/hypothesisRef 在快照内解析；**未要求 span 属于被引用的 fact** | `ANALYSIS_PROTOCOL_V0_1.md` §7 Gate C |
| F-5 | 协议 §5 的 PR-07/08/09/10/11 均无"span 必须绑定 fact"的规则 | `ANALYSIS_PROTOCOL_V0_1.md` §5 |
| F-6 | `EPISTEMIC_TYPES.md` EP-04/EP-05 只要求"引用存在"，不要求"span 归属" | `EPISTEMIC_TYPES.md:113-114` |
| F-7 | 该限制已被特性化用例固定 | `Stage2OutputOptimizationTest.java:645-660` |
| F-8 | reader 被 ≥11 个 staged 阶段类与 `TwoStageSynthesisService` 共用 | `AbstractProtocolStage`、各 `*Stage.java`、`TwoStageSynthesisService` |

**推论**：现象真实存在（引用 F1 却挂 F2 的合法 span 会通过），但**当前实现并未违反书面契约**。
它是「provenance 一致性」的加固缺口，不是 conformance 缺陷。

---

## 3. 影响面（修改 reader 会波及什么）

| 维度 | 影响 |
| --- | --- |
| 拓扑 | 三条：Stage 1 facts、staged 单阶段拓扑、monolith（`DRAFT`）与 `TWO_STAGE` |
| 数组 | 6 个同时声明 `factRefs` + `sourceRefs` 的数组（见 F-3） |
| 契约类型 | `Variable`、`CausalLink`、`StakeholderImpact`、`Statement`（`contracts/analysis-result.schema.json`） |
| 测试 | `StageResponseReaderTest`、`TwoStageSynthesisTest`、`Stage2ContractConsistencyTest`、`Stage2OutputOptimizationTest`，以及依赖 reader 的 staged 阶段用例 |
| 协议 | 若要让规则有规范依据，需先扩写 Gate C 或新增 PR 规则 |

---

## 4. 结构性限制（重要）

**绑定规则在 12 类产物中最多只能覆盖 7 类，无法完全关闭该缺口。**

| 数组 | 契约类型 | factRefs | sourceRefs | 绑定可表达？ |
| --- | --- | --- | --- | --- |
| variables | Variable | ✓ | ✓ | 可以 |
| mechanisms | CausalLink | ✓ | ✓ | 可以 |
| stakeholders | StakeholderImpact | ✓ | ✓ | 可以 |
| firstOrderEffects / secondOrderEffects | Statement | ✓ | ✓ | 可以 |
| alternativeExplanations | Statement | ✓ | ✓ | 可以 |
| counterArguments | Statement | ✓ | ✓ | 可以 |
| hypotheses | Hypothesis | —（用 supportingFactRefs） | ✓ | **不可**（无 factRefs 可绑定） |
| predictions | Prediction | —（用 basisFactRefs） | — | **不可** |
| corroboratingSignals | CorroboratingSignal | — | ✓ | **不可** |
| verificationIndicators | Indicator | — | ✓ | **不可** |
| falsificationConditions / unknowns / upcomingObservations | Statement | — | ✓ | **不可** |

即：所谓"修复 P1"最多只能把绑定规则施加到 6 个数组；
`hypotheses`/`predictions`/`signals`/`indicators` 等路径需要**另一套** provenance 一致性设计。
若用户期望"彻底消除该类缺口"，本轮修 reader 不足以达成，应另开独立一轮做完整设计。

---

## 5. 误拒风险（用真实 fixture 量化）

`StageOneSnapshot.java` 的真实 span（Stage 1 Live Probe 原始数据）：

```
F1 [12,49)   F2 [50,62)   F3 [70,84)   F4 [85,95)   F5 [63,96)
```

`F5` **包含** `F3` 与 `F4` —— 即真实数据里存在**嵌套/重叠的合法 fact**。

| 候选规则 | 对"引用 F3、quote 用 F5 的 span" | 风险 |
| --- | --- | --- |
| R1 精确相等（span 必须等于某个被引用 fact 的 span） | **拒绝** | **高**：会误拒真实 fixture 下的合法输出 |
| R2 包含（span 必须落在某个被引用 fact 的 span 内） | 拒绝 | 中：方向敏感，需明确"聚合 fact 引用窄证据"是否合法 |
| R3 重叠（span 至少与一个被引用 fact 的 span 相交） | 拒绝 | 低：仍能拦住本 P1 的 F1/F2 洗白（两者不相交） |

**R1 已被本轮证据排除**；R2 与 R3 是可讨论的候选。

---

## 6. 候选方案（供选择）

| 方案 | 动作 | 代码/协议改动 | 风险 | 是否需批准 |
| --- | --- | --- | --- | --- |
| **A. ACCEPT** | 正式把 KG-1 从"未处理 P1"降级为"已批准的已知边界" | 无 | 缺口保留 | **需用户签署** |
| **B. TIGHTEN** | 先扩写协议 Gate C / 新增 PR 规则，再改 `StageResponseReader`，补测试，跑全量回归 | 协议 + reader + 测试 | 跨 3 拓扑；需选 R2/R3；仍有误拒可能；需重跑全量与 LIVE | **需用户批准**（L3） |
| **C. DEFER** | 本轮不动，随人工 Review Gate 一并处理，另开独立一轮做 provenance 一致性 | 无 | 与现状一致 | 无需（默认） |
| **D. SCOPED** | 只在 `TwoStageSynthesisService.validateReasoning` 内做交叉校验，不动共享 reader | 仅 two-stage 路径 | 较小，但同一回复在 `DRAFT` 通过、在 `TWO_STAGE` 被拒 → 两拓扑校验语义不一致，须显式记录并接受 | **需用户批准** |

**建议**：若目标是"尽快合规地解除 Gate 阻塞"→ 选 **A**；
若目标是"真正关闭缺口"→ 选 **B（并用 R3）** 并接受另开一轮；
若目标是"不动共享组件、先小范围收紧"→ 选 **D**，但必须同时接受两拓扑语义不一致。

---

## 7. 需要用户批准的具体事项

1. 是否把 KG-1 作为**已批准限制**接受（方案 A），或批准修改校验语义（方案 B/D）。
2. 若批准修改：采用 R2（包含）还是 R3（重叠）。
3. 若批准修改：是否同时修改 `ANALYSIS_PROTOCOL_V0_1.md` 的 Gate C / 新增 PR 规则
   （否则新规则缺少规范依据）。
4. 第 2 轮 Codex 之后新增的 F5 修复与去冗余改动（未经任何 Codex 轮次审查）如何处置：
   接受为最终状态，还是纳入人工审查范围。
5. 正式 Review Gate 的最终签署（**只能由人完成**，Codex 意见不可替代）。

---

## 8. 本轮已执行的验证（只读 / 安全本地）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 契约漂移 | `npm run contracts:check` | **PASS**（OpenAPI valid；生成 Java/TS 均最新） |
| 定点回归 | `./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=Stage2OutputOptimizationTest,TwoStageSynthesisTest,StageResponseReaderTest,Stage2ContractConsistencyTest` | **PASS — 65 tests, 0 failures, 0 errors, 0 skipped** |
| 工作区完整性 | `git status --short` | 与 Preflight 完全一致（未新增/未修改任何被跟踪文件） |

未执行（并按本轮要求不执行）：真实 provider 请求、全量套件重跑、服务的启动式 smoke。

---

## 9. 明确未做的事

- 未修改 `StageResponseReader` 或任何业务源码、测试、契约、协议文档。
- 未修改 `AGENTS.md`、`WAVE3A_STAGE2_OPTIMIZATION.md`、审查包或任何历史记录。
- 未提交、推送、合并、部署。
- 未调用 Codex（理由见本轮最终报告第 8 项）。
