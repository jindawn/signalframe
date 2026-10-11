# Wave 3A — 第二轮 F5 独立审查最终审查包

| 项 | 值 |
| --- | --- |
| 用途 | 供**人工**独立审查者完成第二轮之后的 F5 变更审查，并为 Review Gate 签署提供证据基础 |
| 分支 | `feat/wave3a-e2e` |
| 生成日期 | 2026-10-11 |
| 本包是否构成审查结论 | **不是**。本包只准备证据，**不自动签署** F5 或 Review Gate |
| 谁能签署 | **仅人工**。Codex 不承担正式独立审查员职责，DeepSeek 亦无权签署 |
| 配套文档 | `docs/validation/WAVE3A_REVIEW_GATE_STATUS.md`（状态、阻塞分解与签署页） |

---

## 0. 审查对象快照（先锁定修订）

**审查对象 = 以下 4 个本地提交所构成的修订**（基线 `b374bc8`）：

| # | Commit | Subject |
| --- | --- | --- |
| 1 | **`0467d34`** | `fix(analysis): state the analyzed source id and scope item reference fields in the stage 2 prompt` |
| 2 | **`41ea6f6`** | `test(analysis): add the stage 2 optimization and provenance boundary suite` |
| 3 | **`3c7a56e`** | `docs(validation): record the stage 2 round, the KG-1 decision and its verification` |
| 4 | **`685c9fd`** | `docs(reviews): add the stage 2 review pack and the provenance boundary note` |

**代码审查范围**：提交 1（实现 + 版本断言）与提交 2（新增 15 用例）。
提交 3、4 为文档，**不改变行为**。

```
$ git log --oneline -5
685c9fd docs(reviews): add the stage 2 review pack and the provenance boundary note
3c7a56e docs(validation): record the stage 2 round, the KG-1 decision and its verification
41ea6f6 test(analysis): add the stage 2 optimization and provenance boundary suite
0467d34 fix(analysis): state the analyzed source id and scope item reference fields in the stage 2 prompt
b374bc8 fix(analysis): align the stage 2 probe fixture with the analyzed source
```

**查看代码 diff**：`git diff b374bc8..41ea6f6`

**快照之外（未提交、**不在**审查对象内）**：
`AGENTS.local.md`（本地叠加规则，刻意不提交）；
`docs/reviews/WAVE3A_F5_REVIEW_HANDOFF.md`、`docs/validation/WAVE3A_REVIEW_GATE_STATUS.md`、
`docs/validation/WAVE3A_G4_READINESS.md`（本包及其配套文档，属审查元数据）。

**未 push**：本分支无 upstream，全部提交仅存在于本地。

---

## 1. 审查目标

对**尚未被任何审查轮次覆盖**的改动（第二轮之后的 F5 组）做独立判断，回答：

1. F5-1 的**按 item 类型点名引用字段**是否完整、正确、无遗漏；
2. F5-2 的**去冗余**是否删掉了任何硬约束；
3. F5-3 的**规模闸门**是否会被误读为对任意输入的保证；
4. 该组改动是否**放松**了 Schema、校验语义或验收门禁；
5. 是否引入新的 P0/P1。

**范围外**（请勿在本包内裁决）：
G4 真实验证（需真实 provider，独立授权）；R-6 的契约/Schema 语义变更（需另行批准）。

---

## 2. 审查对象

**整份未提交 delta 为审查对象**，其中带 ⚠️ 的三项是本包重点。

| 文件 | 变更 | 来源轮次 |
| --- | --- | --- |
| `prompts/two-stage-reasoning/prompt.txt` | 39 行改动 | 第 1–2 轮 + ⚠️ F5-1 / F5-2 |
| `.../steps/TwoStageSynthesisService.java` | 35 行改动（`reasoningPromptText`） | 第 1–2 轮 + ⚠️ F5-2 |
| `prompts/two-stage-reasoning/version.txt` | v3 → **v4** | 随 prompt 改动 |
| `Stage1ProbeRehearsalTest.java` | 1 行（版本断言） | 随 prompt 版本 |
| `TwoStagePostgresIntegrationTest.java` | 2 行（版本断言） | 随 prompt 版本 |
| `TwoStageSynthesisTest.java` | 3 行（版本断言） | 随 prompt 版本 |
| `Stage2OutputOptimizationTest.java` | **新增** 15 用例 | 第 1–2 轮 + ⚠️ F5-3 |
| `docs/validation/*`、`contracts/PROVENANCE_BOUNDARY.md` | 本轮文档 | 本轮（文档，非审查重点） |

**契约工件**：`contracts/openapi.yaml`、`contracts/analysis-result.schema.json`、
生成的 Java/TS —— **零改动**（见 §4）。

---

## 3. 上一轮问题 ↔ 整改对应关系

| # | 轮次 | 严重度 | 上轮问题 | 整改 | **当前状态** |
| --- | --- | --- | --- | --- | --- |
| F1 | 1 | P1 | 空数组用例声称"协议有效"，但唯一 UNKNOWN 未解释数组为何为空（与 STG-09 rule 1 不符） | 用例改名 `everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts`；UNKNOWN 点名覆盖数组；新增 3 条断言 | ✅ RESOLVED（已被第 2 轮确认） |
| F2 | 1 | P1 | reader 不做 fact↔span 交叉校验 | prompt 层收紧 + 特性化用例固定边界；**reader 未改** | ✅ **已由决策 A1 正式风险接受**（见 §7） |
| F3 | 1 | P2 | "Each item carries a confidence field" 与多类型冲突 | prompt 与读最后一段改为按类型限定；新增 `thePromptScopesItemConfidenceToTheTypesThatDeclareIt`、`onlyTheItemTypesWhoseSchemaDeclaresConfidenceMayCarryIt` | ✅ RESOLVED |
| F4 | 1 | P2 | 测试名/注释声称"schema 逐字未动"，实际只断言根对象子集 | 改名 `theOptimizationDidNotRelaxTheReplySchemaRoot` + 注明证据来源 | ✅ RESOLVED |
| **F5** | **2** | P2 | `prompt.txt:4` 的"每个基于事实的产物都要给 factRefs 和 sourceRefs"对 Hypothesis/Prediction/FalsificationCondition/Signal/PlanItem/Unknown 是错的 | prompt 改为按 item 类型点名引用字段 | ⚠️ **已修但未经任何审查** |
| — | 2 | — | 正文与读最后一段重复陈述"数组必须存在/空数组语义" | 删除重复，读最后一段独家承载 | ⚠️ **未经审查** |
| — | 2 | — | 规模闸门单断言 | 改为同时断言 `promptText < 7000` 与 `chars < 18000` + javadoc 限定 | ⚠️ **未经审查** |

**来源**：`docs/validation/WAVE3A_STAGE2_OPTIMIZATION.md` §9（发现）与 §10.1/§10.2（修复）。
该文档本身已声明"§10.2 的改动发生在最后一次 Codex 轮次之后，属于未经 Codex 审查的新增内容"。

---

## 4. 已有证据（可复核）

| # | 证据 | 结果 |
| --- | --- | --- |
| E-1 | `npm run contracts:check` | **PASS**（OpenAPI valid；生成 Java/TS 最新，无漂移） |
| E-2 | 定向回归（4 个类） | **PASS — 65/65**（TwoStage 20 · ContractConsistency 7 · Optimization 15 · Reader 23） |
| E-3 | R-7 边界行为（6 个具名测试） | **PASS — 6/6**（见 `WAVE3A_KG1_RISK_ACCEPTANCE.md` §9.1） |
| E-4 | `git diff --check` | **OK（exit 0）**，无空白错误 |
| E-5 | 契约工件不变性 | `git diff --stat -- contracts/ 生成代码 = 空`；工件哈希：`openapi.yaml` `b0c397e4…`、`analysis-result.schema.json` `388351f6…` |
| E-6 | 契约层结构事实 | `SourceRef` 4 字段 + `additionalProperties: false`；`sourceRefs` 在 8 类型 required；`factRefs` 在 **0** 类型 required |
| E-7 | prompt 版本如实推进 | `version.txt` = `two-stage-reasoning-v4`，3 个测试的版本断言同步 |
| E-8 | 敏感信息扫描 | 全部拟提交文件：**0 命中** |
| E-9 | 决策记录 | A1 已批准（`WAVE3A_KG1_RISK_ACCEPTANCE.md` §0） |
| **E-10** | **全量后端回归** | **PASS — 649 tests, 0 failures, 0 errors, 8 skipped · BUILD SUCCESS**（`./scripts/mvn.sh -B -f apps/api/pom.xml test`，2026-10-11） |
| **E-11** | 8 个 skipped 的性质 | **全部是 opt-in 真实 provider 测试**（RealModelEndToEndTest 5 · Stage1LiveProbeTest 1 · Stage2LiveProbeTest 1 · Stage2MinimalOutputDiagnosticTest 1）——**不是 PASS**，未授权执行 |
| **E-12** | **浏览器 Smoke（G5 层）** | **PASS — 24 passed / 0 failed**（隔离环境；含桌面 Chromium、移动 Chromium、移动 WebKit 三套） |

---

## 5. 尚未验证的事实（UNKNOWN —— 请勿当作已证）

| # | 未验证事实 | 原因 |
| --- | --- | --- |
| U-1 | **可见输出缩减的实际幅度** | 未发真实请求；**不得**由历史未保留正文反推 |
| U-2 | 新 prompt 在**真实 provider** 下的表现（是否仍 TIMEOUT / OUTPUT_INVALID） | 需真实请求，本轮未授权 |
| U-3 | 规模闸门对**任意输入**的有效性 | 闸门只对固定 fixture 有效（已在 javadoc 声明） |
| U-4 | ~~全量套件通过情况~~ | ✅ **已验证（2026-10-11）**：`./scripts/mvn.sh -B -f apps/api/pom.xml test` → **649 tests, 0 failures, 0 errors, 8 skipped · BUILD SUCCESS**，与上一轮记录一致，无回归 |
| U-5 | ~~`npm run smoke`（浏览器层）~~ | ✅ **已验证（2026-10-11）**：**24 passed / 0 failed**（隔离 Postgres 55432 + 隔离 API 8090 + WEB_PORT 3101）。首次运行 3 个失败，根因为启动 API 时**遗漏 `AI_FAST_API_KEY` 前置**（凭据无法解析 → profile 报 MOCK 而非 LIVE），补齐后全绿；**非代码回归**。详见 `WAVE3A_REVIEW_GATE_STATUS.md` §5.3 |
| U-6 | F5-1 类型点名是否**穷尽** 12 类产物 | **这正是需要审查者判断的问题**（§6） |

---

## 6. 审查人员需要判断的关键问题

**Q1（对应 F5-1）**：`prompt.txt:5-6,10` 的类型点名是否与 `AnalysisResult` 契约**逐类一致**？
请特别核对：`Hypothesis.supportingFactRefs`、`Prediction.basisFactRefs`、
`FalsificationCondition`/`Signal`（两者皆无）、`PlanItem`/`Unknown`（只有 span 无 fact 引用）。
可用 `Stage2OutputOptimizationTest.thePromptNamesTheReferenceFieldsEachItemTypeActuallyDeclares`
（对 `$defs` 逐一断言 `refFields(...)`）作为起点，但**请独立核对**而非仅采信该断言。

**Q2（对应 F5-2）**：从 `prompt.txt` 正文与读最后一段删除的重复陈述，
是否**带走**了任何硬约束？请对照 `theShortenedContractKeepsEveryHardConstraint` 的断言范围，
确认"硬约束清单"本身是否完整。

**Q3（对应 F5-3）**：`promptText < 7000` / `chars < 18000` 两道闸门的作用域是否被清楚限定？
若 prompt 随输入增长，闸门是否会给出**虚假的安全感**？

**Q4**：prompt 语料改动是否构成对**验收标准**的实质放松？（预期：否——校验发生在 reader/validator，
prompt 只影响模型行为）

**Q5**：`TwoStageSynthesisService` 新增的注释与措辞改动是否**只影响 prompt 文本**、
未触及校验或状态流转？

**Q6（对应 F2/R-6）**：A1 的风险接受是否可接受？R-6 是否应当立项，规则取「包含」还是「重叠」？

### 6.1 明确的 PASS / FAIL 标准

审查者请对每一问给出 **PASS / FAIL / 需补充证据**，并**引用具体行号或命令输出**作为依据。
不允许只给结论。

| # | PASS 的判定标准 | FAIL 的判定标准 |
| --- | --- | --- |
| Q1 | 逐类比对后，prompt 对 12 类产物所声明的引用字段**与 `AnalysisResult` 契约一致**；且所有类型均被点到（无遗漏、无错误归属） | 存在任一类型被错误描述（例如把 `sourceRefs` 说成 hypothesis 的 fact 引用字段），或缺漏导致照字面写会被整条拒绝 |
| Q2 | 被删除的重复陈述中**不含**任何硬约束；`theShortenedContractKeepsEveryHardConstraint` 的断言范围**覆盖**读最后一段承载的全部硬约束 | 任一硬约束（数组必存在、空数组语义、引用字段、`expectedBy`、`verificationCriteria`、无 wrapper key、无 markdown 等）只存在于被删文本中 |
| Q3 | 两道闸门的 javadoc/断言**明确限定作用域**为固定 fixture；且没有其它文档据此宣称"prompt 不会增长" | 闸门被表述为对任意输入的保证，或该限定缺失 |
| Q4 | prompt 语料改动**不触及** reader / validator / Schema；校验语义可由 `contracts:check` 与不变性证据独立佐证 | 任一校验被放松、删除或改为警告 |
| Q5 | `TwoStageSynthesisService` 的改动**仅**影响 prompt 文本拼装；无状态流转或校验分支变化 | 出现非 prompt 文本的行为变化（分支、校验、持久化） |
| Q6 | 依据充分，可给出「接受 / 立项 / 另开一轮」的明确结论 | 证据不足以支撑结论，或结论与 `WAVE3A_KG1_RISK_ACCEPTANCE.md` 的证据链冲突 |

**F5 组整体 PASS 条件**：Q1–Q5 全部 PASS，且无新增 P0/P1。
**F5 组 FAIL 条件**：Q1–Q5 任一 FAIL，或发现新增 P0/P1。
Q6 属**决策**，不参与 F5 的 PASS/FAIL 判定，但影响 R-6 立项。

### 6.2 审查时必须保持有效的约束（不得被"顺带修掉"）

审查过程中请确认以下约束**未被改动**（本包 §4 E-5/E-6 提供机械证据）：

| 约束 | 内容 |
| --- | --- |
| `SourceRef.sourceId` | 必须等于被分析来源（PR-01） |
| `SourceRef` offset | `0 ≤ start < end ≤ 源文本长度`，且为 **UTF-16 code unit**（PR-07） |
| `SourceRef.quote` | 必须**逐字**等于 `substring(start,end)`（PR-07/PR-10） |
| 引用解析 | `factRefs` / `supportingFactRefs` / `hypothesisRef` 必须在快照内解析（PR-08/PR-09） |
| fact 自洽 | 每个 FACT ≥1 `sourceRef`（EP-05）；`statement` 必须等于其自身某个引用 span（STG-03） |
| 分层 | FACT / INFERENCE / HYPOTHESIS / PREDICTION / UNKNOWN 分离与 provenance 保留 |
| Gate C | 上述全部**继续有效**；KG-1 描述的是 Gate C **从未声明**的一条附加一致性，**不是**对 Gate C 的放松 |

**已知边界（**不**在本次审查范围内，已按 A1 接受）**：item 级 fact↔span 归属不做交叉校验；
详见 `WAVE3A_KG1_RISK_ACCEPTANCE.md` 与 `contracts/PROVENANCE_BOUNDARY.md`。

---

## 7. 是否仍存在 P0/P1

| 级别 | 数量 | 说明 |
| --- | --- | --- |
| **P0** | **0** | 第 1、2 轮均为 0；本包未发现新增 P0 |
| **P1（未处理）** | **0** | 原 F2 已由决策 **A1** 转为「已批准的已知边界」；无其它 P1 |
| P2 | 2 | KG-2（prompt 增大，ESTIMATED）、KG-3（可见输出缩减未测量） |
| P3 | 4 | KG-4…KG-7（规模闸门作用域、domain guidance 2000 字符、reasoning tokens 未持久化、历史 LIVE 正文未保留） |
| 过程性缺口 | **1** | **F5 组改动未经任何审查轮次** —— 需人工审查补上（§8-c1） |

> **注意**：P1 计数为 0 **以 A1 风险接受生效为前提**；
> 该接受**不是**技术修复，**不得**据此声称 KG-1 已修复。

---

## 8. Review Gate 签署前必须满足的条件

- [ ] **c1**：F5 组（F5-1 / F5-2 / F5-3）完成独立人工审查，**无未处理 P0/P1**。
- [ ] **c2**：确认 KG-1 的 **A1** 风险接受可接受（记录已生效）。
- [ ] **c3**：就 **R-6** 给出结论（是否立项 / 何时 / 规则取「包含」还是「重叠」）。
- [ ] **c4**：复核契约不变性证据（E-1 + E-5/E-6）。
- [ ] **c5**：确认提交范围（见提交计划）并授权 Git 写操作。
- [ ] **c6**：人工签署 **Review Gate** 本身。

**明确不在本 Gate 范围内**：
- **G4**（Full Real AnalysisResult）需真实可用 provider 凭据与真实请求，属独立授权；
  **不得**用本 Gate 通过或任何离线结果替代。
- **R-6** 的实施（需先批准协议/Schema 语义变更）。

---

## 9. 索引

| 内容 | 位置 |
| --- | --- |
| 状态与阻塞分解 | `docs/validation/WAVE3A_REVIEW_GATE_STATUS.md`（含 §7 人工签署页模板） |
| G4 就绪说明与实施计划 | `docs/validation/WAVE3A_G4_READINESS.md` |
| 风险接受决策 A1 与证据链 | `docs/validation/WAVE3A_KG1_RISK_ACCEPTANCE.md` §0、§4、§9.1 |
| 契约侧边界说明 | `contracts/PROVENANCE_BOUNDARY.md` |
| 上一轮发现与修复 | `docs/validation/WAVE3A_STAGE2_OPTIMIZATION.md` §9、§10 |
| 上一轮完整审查包 | `docs/reviews/WAVE3A_STAGE2_CHATGPT_REVIEW_PACK.md` |
| 边界特性化用例 | `Stage2OutputOptimizationTest.theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem` |
