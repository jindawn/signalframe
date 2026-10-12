# Wave 3A — Stage 2 输出优化与 Codex 审查闸门

日期：2026-10-11 · 分支 `feat/wave3a-e2e` · 基线 `b374bc8`
范围：离线开发、离线自测与 Codex 独立审查。**本轮没有发起任何真实模型请求。**

开发结论：**PASS（离线）**
Codex 审查结论：**BLOCKED —— 0 个 P0，1 个未处理 P1（跨路径、本轮刻意不改），其余全部已修复**
Review Gate：**BLOCKED**，等待人工审查。

> 本轮<b>不</b>宣称 G4 通过，也<b>不</b>宣称降低了模型的内部 reasoning tokens。
> 所有未实测的数字一律标注 ESTIMATED 或 UNKNOWN。

---

## 1. 当前 Git 基线

| 项 | 值 |
| --- | --- |
| worktree | `/Users/wumj/workspace/signalframe-worktrees/wave3a-e2e` |
| 分支 | `feat/wave3a-e2e` |
| 基线 commit | `b374bc8`（`fix(analysis): align the stage 2 probe fixture with the analyzed source`） |
| 修改前工作区 | 干净（`git status` 无输出） |
| 本轮是否提交 | **未提交**（Review Gate 非 PASS，按任务要求不创建 commit） |
| 是否 push / merge / tag | 否 |

改动清单（6 改 1 增）：

| 文件 | 性质 |
| --- | --- |
| `apps/api/src/main/java/com/signalframe/analysis/application/steps/TwoStageSynthesisService.java` | 仅改 `reasoningPromptText(...)` |
| `apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt` | v3 → v4 |
| `apps/api/src/main/resources/prompts/two-stage-reasoning/version.txt` | v3 → v4 |
| `apps/api/src/test/.../TwoStageSynthesisTest.java` | 固定 prompt 版本的审计断言 v3 → v4 |
| `apps/api/src/test/.../TwoStagePostgresIntegrationTest.java` | 同上 |
| `apps/api/src/test/.../Stage1ProbeRehearsalTest.java` | 同上 |
| `apps/api/src/test/.../Stage2OutputOptimizationTest.java` | **新增**，15 个离线用例 |

`contracts/openapi.yaml`、生成代码、Flyway 迁移、`AnalysisResultValidator`、`StageResponseReader`、
`StageReplySchema`、`ConfidenceRubric`、monolith 路径：**零改动**。

---

## 2. 优化前的已知证据

| 证据 | 值 | 性质 |
| --- | --- | --- |
| 最近一次 Stage 2 LIVE 响应 output tokens | 14928 / 16384 | 实测（历史） |
| 该响应的正文 | 未保留 | —— |
| 该响应的 reasoning / 可见 JSON 拆分 | **UNKNOWN** | 无法拆分；本轮<b>不</b>据已丢失的正文推测任何字段长度 |
| 上次 Stage 2 LIVE 失败点 | `variables[].sourceRefs` 以 PR-01 失败 | 已由 `b374bc8` 定位为 fixture 缺陷：`StageOneSnapshot` 硬编码 Stage 1 当时的 sourceId，而探针每次新建 news |
| 后端测试（本轮前） | 634 tests，0 failures，8 skipped | 实测 |
| contracts:check（本轮前） | PASS | 实测 |
| G4 | FAIL / BLOCKED | 未变 |

### 2.1 阅读到的源码与文档

`AGENTS.md`、`docs/validation/WAVE3A_TWO_STAGE_POC.md`、`WAVE3A_GATE.md`、
`Stage2ContractConsistencyTest`、`Stage2LiveProbeTest`、`Stage2MinimalOutputDiagnosticTest`、
`Stage2ProbeRehearsalTest`、`StageOneSnapshot`、`TwoStageSynthesisService`、`TwoStageModel`、
`StageReplySchema`、`AnalysisOutputSchema`、`StageResponseReader`、`SnapshotEnvelope`、
`AnalysisResultValidator`、`ProtocolModel`、`contracts/openapi.yaml`、
`docs/analysis/ANALYSIS_PROTOCOL_V0_1.md`、`docs/analysis/EPISTEMIC_TYPES.md`。

> 任务书提到的 "最近的 Stage 2 SourceRef Repair 报告" 在本 worktree 中<b>不存在独立文档</b>；
> 该修复的内容由 commit `b374bc8`（消息正文）与 `StageOneSnapshot.requireSameSource` 承载。

---

## 3. 字段与契约矩阵

Stage 2 的窄契约是 `TwoStageModel.Reasoning`（14 个根字段：`summary` + 13 个数组）。
窄路径下 13 个数组由 `TwoStageSynthesisService.validateNarrowReasoning`（`TSS:675-687`）逐个
`require(...)`——**`null` 是硬失败，`[]` 合法**。每个数组随后被包成 reader 已认得的 envelope，
交给同一个 `StageResponseReader`。

图例：`必填` = reader 在 envelope 路径强制执行；`可空` = `[]` 被接受。

| 数组 | reader 位置 | 必填字段（enforced） | 可空 | 非空字符串 | 交叉引用 | 可确定性生成？ |
| --- | --- | --- | --- | --- | --- | --- |
| `variables` | `SRR:793-821` | name/statement/reasoning/whyItMatters；`whyItMatters != name`；direction≠UNKNOWN ⇒ currentState 与 factRefs | 是 | name, statement, reasoning, whyItMatters | factRefs→facts（PR-09）；sourceRefs→source（PR-01/07/10） | 否（仅 id/direction 默认值可确定） |
| `mechanisms` | `SRR:823-863` | explanation/from/to；from/to 必须解析到 variable 或 fact；SUPPORTED 需 ≥2 factRefs；PLAUSIBLE 需 factRefs+assumptions；SPECULATIVE 需 corroboratingSignal | 是 | explanation, from, to | factRefs→facts；from/to→variables/facts | 否 |
| `stakeholders` | `SRR:164-184` | statement/reasoning/stakeholder；direction≠UNKNOWN ⇒ factRefs | 是 | 三者 | factRefs→facts | 否 |
| `firstOrderEffects` | `SRR:886-914` | statement/reasoning；factRefs 非空（EP-05） | 是 | statement, reasoning | factRefs→facts | 否 |
| `secondOrderEffects` | `SRR:886-914` | 同一阶，且 derivedFromRefs 非空且解析到一阶（STG-08） | 是 | statement, reasoning | derivedFromRefs→firstOrderEffects | 否 |
| `hypotheses` | `SRR:221-246` | title/statement/reasoning；supportingFactRefs 非空（EP-05）；contradicting 空 ⇒ assumptions 非空（STG-09.3） | 是 | title, statement, reasoning | supportingFactRefs→facts | 部分（sourceRefs 可派生） |
| `alternativeExplanations` | `SRR:275-303` | statement/reasoning；rivalsHypothesisRef 解析（PR-09）；factRefs 非空（STG-10）；不得复述 hypothesis；statement 不得重复 | 是 | statement, reasoning | rivalsHypothesisRef→hypotheses | 否 |
| `counterArguments` | `SRR:340-354` | statement/reasoning；targetHypothesisRef 解析 | 是 | statement, reasoning | targetHypothesisRef→hypotheses | 否 |
| `falsificationConditions` | `SRR:383-399` | hypothesisRef 解析；observable/comparison/decisionBoundary 非空；`decidable()`（STG-12） | 是 | observable, comparison, decisionBoundary | hypothesisRef→hypotheses | 否（`decidable` 是启发式） |
| `corroboratingSignals` | `SRR:433-446` | signal/where/window；hypothesisRef 解析（STG-13） | 是 | signal, where, window | hypothesisRef→hypotheses | 否 |
| `verificationIndicators` | `SRR:518-552` | whatToCheck/whereToCheck/supportingResult/contradictingResult；两者可区分；deadline；venue 不得泛化（STG-15.3）；hypothesisRef 或 predictionRef 至少其一 | 是 | 四个 | hypothesisRef/predictionRef 解析 | 否 |
| `predictions` | `SRR:462-486` | statement/observable/verificationCriteria/whereToCheck；hypothesisRef 解析；expectedBy 在未来；criteria ≠ statement | 是 | 四个 | hypothesisRef→hypotheses | 否 |
| `summary` | `TSS:675` + 汇编器 `STG-16` | 非 null；blank 在汇编器即失败 | 否 | summary | —— | 否 |
| `unknowns` | `TSS:689-691` | 无字段校验 | 是 | 无 | 不校验其 sourceRefs | 否 |

### 3.1 关键补充（决定本轮优化边界）

- `StageReplySchema.render` 把**每个 record 组件都列为 `required`**，且不生成 `maxLength`/`minItems`。
  它是"给模型看的形状"，<b>不是</b>校验闸门；真正的闸门是 Jackson + reader。
- **可确定性生成的字段已经都有 DTO 默认值**：`id`、`status`、`direction`、`supportLevel`、
  `priority`、`previousState/magnitude` 等。没有为了凑 schema 而要求的占位输出。
- **长度上限不在 reader，而在 Contract**：`contracts/openapi.yaml` 中 Stage 2 相关字符串字段
  一律 `maxLength: 20000`（共 44 处），`minItems` **0 处**（任何数组都允许为空）。
  `SnapshotProjection.bounded(...)` 对 summary 等少数字段静默截断到 20000。
- **item-level `confidence` 不是所有 item 都有**（见 §4.2），这是本轮修掉的一个真实失败源。

### 3.2 空数组的下游后果（决定"不能为空"的项）

| 数组 | 下游 | fatal? |
| --- | --- | --- |
| `hypotheses` | `AnalysisResultValidator` **不检查**；但回到 pipeline 后 `AbstractProtocolStage.requireHypotheses` 抛 `EP-02` | **是**（在 pipeline） |
| `summary` | 汇编器 `STG-16` | **是** |
| `mechanisms` / `verificationIndicators` / `variables` | 只触发 confidence cap（64→59 等） | 否 |
| `alternativeExplanations` / `falsificationConditions` | `EpistemicGateStage` 会在没有对应项时**丢弃该 hypothesis** | 否，但有连带 |
| 其余 | 无致命规则 | 否 |

**因此"预算"只能压在非致命数组上，且必须保护任何已发出 hypothesis 的替代解释与证伪条件。**

---

## 4. Prompt 变更

版本：`two-stage-reasoning-v3` → **`two-stage-reasoning-v4`**（`version.txt` 与 3 处审计断言同步）。

### 4.1 唯一一处功能性缺陷修复：Stage 2 从未被告知来源身份

`state.news().source().id()` 此前只出现在 `factsPromptText`（`TSS:976`、`TSS:983`）。
Stage 2 的回复必须携带 `sourceRefs[].sourceId`，而 `StageResponseReader.validateSpan` 会把它与
被分析来源逐字比较（**PR-01**）。**Stage 2 的 prompt 里从不出现这个 UUID**——模型只能编一个，
或者干脆不写 span（于是 `SnapshotEnvelope` 的 span-overlap 取证链断裂）。

修复：在 fact 清单前给出唯一合法 sourceId，并在读最后一段再次钉住。

### 4.2 第二处功能性缺陷修复：prompt 措辞与 schema 的字段体系不一致

原文两处笼统措辞在 `additionalProperties:false` + `FAIL_ON_UNKNOWN_PROPERTIES` 下会
把**合规回复整条变成 OUTPUT_INVALID**：

| 原措辞 | 与 schema 的冲突 |
| --- | --- |
| "Each item carries a `confidence` field" | `FalsificationCondition` / `Signal` / `Prediction` / `Unknown` **没有** confidence 组件；`Hypothesis` 用的是 `advisoryConfidence` |
| "give BOTH the fact id in its `factRefs` and the exact source span in its `sourceRefs`" | `Hypothesis` 用 `supportingFactRefs` 且**无** sourceRefs；`Prediction` 用 `basisFactRefs` 且无 sourceRefs；`PlanItem`/`Unknown` 有 sourceRefs 但无 fact ref；`FalsificationCondition`/`Signal` 两者皆无 |

修复后按 item 类型逐一点名，并显式禁止给未声明该字段的类型添加字段。
另修掉读最后一段的 "never state a confidence score"——它与正文冲突，会指引模型省略
item-level confidence，被 `ProtocolModel.advisory(null)` 静默清零成 0。

### 4.3 输出预算（Task B）

在读最后一段（prompt 明确声明该段覆盖前面的正文）写入证据规模预算：

- 短/薄来源：**1–2 个 hypothesis、至多 1 个 mechanism、至多 2 个 prediction**，其余数组为空；
- 明确写成 **budget 而非 ceiling**（"unless the facts support more"）；
- 明确保护条款：**不得**因此删除 counter argument、alternative explanation、
  falsification condition 或 prediction 的 verification criteria；
- `reasoning` 限 1–2 句；不得复制来源正文；UNKNOWN 合并时**必须点名**它覆盖的数组。

### 4.4 去冗余（Task C）

| 处理 | 说明 |
| --- | --- |
| **删除**读最后一段对 13 个数组的枚举复述 | 248 字符，纯重复 schema 的 `properties`/`required` |
| **删除**正文中重复的空数组规则 | 该规则由读最后一段（覆盖性位置）独家承载 |
| **删除**正文与读最后一段对 `confidence` 的两套说法 | 见 §4.2 |
| **删除**正文对 item 字段的笼统要求 | 见 §4.2 |
| **未动**领域指导（`DomainStrategySpecRenderer`，≤2000 字符） | 它同时供 monolith 使用，改动属于跨路径语义变更，超出本轮；记为 P3 观察 |
| **未动** `StageReplySchema` 的 `required` 全集 | 任务书明令禁止"通过降低 Schema 严格程度获得成功" |
| **未动** Reader 严格程度 | 任务书明令禁止 |

### 4.5 明确<b>没有</b>做的事

- 没有截断已生成的 JSON；
- 没有用 UNKNOWN 掩盖有效但未处理的内容（反而补强了 UNKNOWN 必须点名覆盖范围）；
- 没有伪造 Prediction；
- 没有放宽 Schema 或 Reader；
- 没有修改 `ConfidenceRubric`；
- 没有修改已冻结的 Epistemic 语义（`UNKNOWN` 仍是一等输出，PR-05/EP-03 未变）。

---

## 5. Token Budget 估计

固定 fixture：86 字符中文来源 + 3 条 fact，无 domain guidance。
字符数为**实测**；token 为 **ESTIMATED**（按 ~4 chars/token）。

| 组成 | v3 | v4 | Δ | 说明 |
| --- | --- | --- | --- | --- |
| prompt.txt | 3 026 | 3 921 | **+895** | 字段体系点名 + confidence 作用域 + 来源身份 + 输出预算指针 |
| 读最后一段（Java 拼接） | 682 | 1 283 | **+601** | 内含 −248 字符的数组枚举删除；+预算条款、+保护条款、+sourceId、+confidence 作用域 |
| fact 清单表头 | —— | —— | +35 | 补 "each span is verbatim source text" |
| sourceId 行 | 0 | 163 | **+163** | 功能性修复（PR-01） |
| 回复 schema（由 reader DTO 派生） | 9 965 | 9 965 | 0 | 未动 |
| **Stage 2 prompt 合计** | **≈14 221** | **15 915** | **+1 694（+11.91%）** | |
| **ESTIMATED prompt tokens** | ≈3 555 | ≈3 978 | **≈ +423** | |

**结论（诚实版）：本轮 prompt <b>变大</b>了约 11.9%。** 其中：

1. 真正服务于"压缩输出"的只有输出预算条款（约 +430 字符）与数组枚举删除（−248 字符）；
2. 其余增量全是**功能性修复**（来源身份 +163、字段体系点名、confidence 作用域），
   它们不减少可见输出，但消除了一整类 OUTPUT_INVALID；
3. 与 14 928 output tokens 相比，+423 ESTIMATED input tokens 的代价可以忽略。

**可见输出的实际缩减：UNKNOWN。** 本轮不发真实请求，无法测量；
也不得根据未保留的历史响应正文反推字段长度。设计预期（ESTIMATED，未验证）：
短新闻来源下，预算条款把 hypothesis/mechanism/prediction 的上界从"schema 允许的任意条数"
压到 1–2 / ≤1 / ≤2 条，其余数组为空，从而显著缩小可见 JSON。

---

## 6. SourceRef 回归（Task D）

| 断言 | 覆盖 | 结果 |
| --- | --- | --- |
| Stage 2 prompt 出现被分析来源的 UUID（两处） | `Stage2OutputOptimizationTest.stageTwoIsToldTheAnalyzedSourceIdItMustCite` | PASS |
| 正向：正确 sourceId + 0..20 偏移原样通过、未被改写 | `...theCorrectSourceIdentityIsAccepted` | PASS |
| 负向：伪造 sourceId → PR-01 | `...aFabricatedSourceRefOnAVariableIsRejectedAsPR01` | PASS |
| 负向：篡改 offset（quote 与区间不符）→ PR-07/PR-10 | `...aTamperedSpanOnAStageTwoArtifactIsRejected` | PASS |
| Fixture 身份一致性：`StageOneSnapshot.requireSameSource`，不一致时发请求前失败 | `Stage2ProbeRehearsalTest`（既有，`b374bc8`） | PASS |
| fact ID / quote / UTF-16 offset 不被篡改 | `TwoStageSynthesisTest` 跨阶段正例（既有） | PASS |
| 历史 SourceRef 错误复用 | `TwoStageSynthesisTest.aSourceThatIsNotTheSnapshotSourceIsRejected`（既有） | PASS |
| 新 NewsItem 使用实际持久化 Source ID | `TwoStagePostgresIntegrationTest` | PASS |

**已知边界（未修复，已固定为特性）：** reader 分别校验 `factRefs` 与 `sourceRefs`，
但**不做 fact↔span 交叉校验**（`SRR:1215` / `SRR:1227`）。即"引用 F1、挂 F2 的合法 span"
会通过。新用例 `theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem`
把该限制固定下来。详见 §9 的 P1 与 §12。

---

## 7. 测试结果

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| Contract 漂移 | `npm run contracts:check` | **PASS**（OpenAPI valid；生成的 Java 与 TS 均最新） |
| 后端全量 | `./scripts/mvn.sh -B -f apps/api/pom.xml test` | **PASS — 649 tests, 0 failures, 0 errors, 8 skipped** |
| 新增用例 | `-Dtest=Stage2OutputOptimizationTest` | **PASS — 15/15** |
| 前端 lint | `npm run lint` | PASS |
| 前端 typecheck | `npm run typecheck` | PASS |
| 前端单测 | `npm run test` | PASS — 4 files / **26 tests** |
| 前端构建 | `npm run build` | PASS |
| 空白卫生 | `git diff --check` | PASS（exit 0） |

### 7.1 SKIPPED 明细（**不是** PASS）

8 个 skipped **全部**是 opt-in 的真实 provider 测试，本轮未授权、未执行：

| 测试类 | skipped |
| --- | --- |
| `RealModelEndToEndTest` | 5 |
| `Stage1LiveProbeTest` | 1 |
| `Stage2LiveProbeTest` | 1 |
| `Stage2MinimalOutputDiagnosticTest` | 1 |

### 7.2 新增用例与任务 E 十项对照

| 任务 E 要求 | 覆盖用例 |
| --- | --- |
| 1 合法精简输出通过 | `everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts`、`theCorrectSourceIdentityIsAccepted` |
| 2 缺失必填字段被拒绝 | `TwoStageSynthesisTest.anOmittedStageTwoArrayIsRejectedRatherThanReadAsEmpty`（既有） |
| 3 无效 Fact ID 被拒绝 | `TwoStageSynthesisTest.aFactReferenceThatStageOneNeverProducedIsRejected`（既有） |
| 4 无效 SourceRef 被拒绝 | `aFabricatedSourceRefOnAVariableIsRejectedAsPR01`、`aTamperedSpanOnAStageTwoArtifactIsRejected` |
| 5 Prediction 验证条件完整 | `StageResponseReaderTest.predictionMustBeTimeBoundedToTheFuture` 等（既有） |
| 6 空数组符合协议 | `everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts` |
| 7 Hypothesis 证据关系正确 | `StageResponseReaderTest.hypothesisNeedsASupportingFactAndANoteWhenContradictionIsEmpty`（既有） |
| 8 长字符串与字段数量边界 | `aLongStringAndALargeItemCountAreHandledWithoutSilentLoss` |
| 9 Stage 1 → Stage 2 → Assembler 正向流 | `TwoStageSynthesisTest.aStageOneFactIsCitedAcrossEveryStageTwoArtifactAndAssembled`（既有，已随 v4 更新） |
| 10 旧 Monolithic 无回归 | `TwoStageSynthesisTest.theDefaultModeIsStillTheMonolithicDraft` + monolith 全量套件 |
| 额外 | `thePromptScopesItemConfidenceToTheTypesThatDeclareIt`、`onlyTheItemTypesWhoseSchemaDeclaresConfidenceMayCarryIt`、`thePromptNamesTheReferenceFieldsEachItemTypeActuallyDeclares`、`theOptimizationDidNotRelaxTheReplySchemaRoot`、`theReadLastContractDoesNotRestateTheSchemaArrayList`、`theShortenedContractKeepsEveryHardConstraint`、`theShortSourceBudgetSitsInTheConstraintThatOverridesTheProse`、`theStageTwoPromptSizeIsMeasuredAndBounded` |

### 7.3 未执行的检查（诚实声明）

- **未执行 `npm run smoke`**：需要 API + 隔离 PostgreSQL + 生产构建同时在线；本轮未改动
  任何被 smoke 覆盖的行为（无 contract、无路由、无持久化改动），且启动服务不属于本轮范围。
- **未执行任何真实 provider 请求**（任务书禁止）。
- **未执行 PostgreSQL 之外的 smoke**；`TwoStagePostgresIntegrationTest` 随全量套件运行，
  使用 Testcontainers 为该次运行单独拉起的隔离 `postgres:17.6-alpine`，未连接任何既有进程。

---

## 8. Codex Review 的真实执行状态

### 8.1 前置验证（在修改任何项目文件之前完成）

| 项 | 结果 |
| --- | --- |
| worktree / 分支 / 未提交修改 | `feat/wave3a-e2e`，工作区干净 |
| Preset 是否启用 | `dsh-codex-review-preset` 已在 `~/.dsh/profiles/desktop/package.json` 的 `dsh.profile.bundles` 中；`subagent-codex` provider 行存在于组装后的 `cordis.yml` |
| `subagent_codex` 是否注册 | **是**，并可被主 Agent 调用（工具目录内可见） |
| 只读隔离 | `CODEX_HOME=/Users/wumj/.codex-isolated/codex-review`，其 `config.toml` 声明 `sandbox_mode="read-only"`、`approval_policy="never"` |
| 冒烟测试 | 通过真实子代理调用执行：要求逐字报告 `AGENTS.md` 某条 bullet、统计二级标题数、执行 `git rev-parse --short HEAD` |
| 冒烟结论 | **返回真实审查内容**（bullet 逐字正确、4 个标题正确、commit `b374bc8` 正确），不是仅子进程启动成功 |
| **实际模型** | **`gpt-5.6-sol`** ——由 `~/.codex-isolated/codex-review/logs_2.sqlite` 的 `model=gpt-5.6-sol` 行直接证明（非子代理自述） |
| 沙箱实测 | 日志记录 `approval_policy=Never sandbox_policy=ReadOnly { network_access: false }`，reasoning effort = low |
| 是否修改全局 Codex 配置 | **否** |
| 是否切换其他模型 | **否**（未使用任何未经确认的高成本模型） |

> 说明：Codex 自述的模型标识为 `gpt-5`，与权威日志不符；本报告以日志的 `gpt-5.6-sol` 为准。

### 8.2 实际执行

| 轮次 | 范围 | 结论 |
| --- | --- | --- |
| 第 1 轮 | 完整 Git diff（含新增测试）、reader/validator/schema、protocol 文档；R1–R6 | `FAIL` — P0=0，P1=2，P2=2，P3=0 |
| 第 2 轮（增量，仅此一次） | 针对 4 条发现的修复；复查是否引入新问题 | `FAIL` — P0=0，P1=1，P2=1，P3=0 |

两轮均通过 `subagent_codex` 真实调用完成，非自查冒充。第 2 轮之后按任务书
"最多自动执行一次增量 Codex Review" 已用尽额度，**未再发起第 3 轮**。

---

## 9. Codex 审查发现

### 9.1 第 1 轮

| # | Sev | 轴 | 位置 | 问题 | 处置 |
| --- | --- | --- | --- | --- | --- |
| F1 | P1 | R1 | `Stage2OutputOptimizationTest.everyArrayEmptyWithOneUnknownIsStillAValidProtocolReply` | 测试声称"协议有效"，但其唯一 UNKNOWN 未解释分析数组为何为空，与 STG-09 rule 1（输入邀请解释时至少 1 个 hypothesis，否则须在 unknowns 说明）不符 | **已修复** |
| F2 | P1 | R3 | `StageResponseReader:1215/1227` | reader 分别校验 factRefs 与 sourceRefs，不做 fact↔span 交叉校验；"引用 F1 + 挂 F2 的合法 span"通过 | **部分修复**（prompt 层收紧 + 固定为已知边界；reader 刻意未改，见 §12） |
| F3 | P2 | R4 | `prompt.txt:17`、`TwoStageSynthesisService:1042` | "Each item carries a `confidence` field" 与 `FalsificationCondition`/`Signal`/`Prediction`/`Unknown`（无该组件）、`Hypothesis`（用 `advisoryConfidence`）冲突 | **已修复** |
| F4 | P2 | R5 | 原 `theOptimizationChangedNoReplySchemaOrContractArtifact` | 测试名与注释声称"schema 与 contract 逐字未动"，实际只断言根对象子集 | **已修复**（改名 + 注明由 `contracts:check`/`git diff` 证明） |

### 9.2 第 2 轮

| # | Sev | 结论 | 处置 |
| --- | --- | --- | --- |
| F1 | P1 | **RESOLVED** | 测试改名 `everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts`；UNKNOWN 点名所有空掉的分析数组，并断言 statement/unknownReason/wouldResolveWith 非空；注释明确限定为"reader 接受"而非"协议完整" |
| F2 | P1 | **PARTIALLY RESOLVED**（按设计） | prompt 明确禁止"挂上属于另一个 fact 的 span"；新增特性化用例固定该限制。reader 本身未改 |
| F3 | P2 | **RESOLVED** | 两处措辞都改成按 item 类型限定；新增用例对渲染后的 `$defs` 逐类型断言 |
| F4 | P2 | **RESOLVED** | 改名 `theOptimizationDidNotRelaxTheReplySchemaRoot` 并注明证明来源 |
| **F5（新）** | P2 | **新发现**：`prompt.txt:4` 的"每个基于事实的产物都要给 factRefs 和 sourceRefs"对 `Hypothesis`（`supportingFactRefs`，无 sourceRefs）、`Prediction`（`basisFactRefs`，无 sourceRefs）、`FalsificationCondition`/`Signal`（两者皆无）、`PlanItem`/`Unknown`（只有 sourceRefs）是错的；照字面写会带非法字段被整条拒绝。Codex 注明该不一致**先于**本轮修复存在 | **已修复**（见 §10），但**这之后的改动未被任何 Codex 轮次审查** |

---

## 10. DeepSeek 修复内容

### 10.1 第 1 轮之后的修复

1. **F1**：`everyArrayEmptyWithOneUnknownIsStillAValidProtocolReply` →
   `everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts`；UNKNOWN 改为
   "来源未给出可证伪的解释依据，故 variables/mechanisms/hypotheses/predictions 等分析数组为空"，
   并新增三条断言（点名覆盖数组、`unknownReason` 与 `wouldResolveWith` 非空）。
2. **F2（prompt 层）**：`prompt.txt` 增加
   "for each fact id you cite give that fact's own span: never reconstruct a span, and never
   attach a span that belongs to a different fact."；
   新增特性化用例 `theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem`
   固定 reader 的现有边界（含 rationale 与"未来收紧时本用例应当失败"的说明）。
3. **F3**：`prompt.txt` 改为
   "Only an item type whose schema declares a confidence field carries one (`confidence`, or
   `advisoryConfidence` on a hypothesis) ... Never add a confidence field to an item whose
   schema does not declare one."；读最后一段同步。新增 `thePromptScopesItemConfidenceToTheTypesThatDeclareIt`
   与 `onlyTheItemTypesWhoseSchemaDeclaresConfidenceMayCarryIt`（后者直接对 `$defs` 断言 12 个类型）。
4. **F4**：测试改名 `theOptimizationDidNotRelaxTheReplySchemaRoot`，注释注明 Contract 不变性由
   `contracts:check` 与 `git diff` 证明，嵌套定义同源性由 `Stage2ContractConsistencyTest` 锁定。
5. 顺带修复：prompt 断言改为空白归一化（`flat()`），避免折行位置影响断言。

### 10.2 第 2 轮之后的修复（未再审查）

6. **F5**：`prompt.txt` 的引用字段要求改为按 item 类型点名：

   ```
   factRefs for a variable, mechanism, stakeholder, effect, alternative or counter argument,
   supportingFactRefs for a hypothesis, basisFactRefs for a prediction.
   Where the item type also declares sourceRefs (variables, mechanisms, stakeholders, effects,
   alternatives, counter arguments, plan items and unknowns — plan items and unknowns declare a
   span but no fact reference) ...
   Falsification conditions and signals declare neither a fact reference nor a span.
   ```

   新增 `thePromptNamesTheReferenceFieldsEachItemTypeActuallyDeclares`：对 `$defs` 逐一断言
   `refFields(...)`，并断言 prompt 点名了 `supportingFactRefs`/`basisFactRefs`、
   说明了哪些类型两个字段都没有、且旧的笼统措辞已消失。
7. 去冗余：删除正文与读最后一段对"数组必须存在 / 空数组语义"的重复陈述（读最后一段独家承载）。
8. 规模闸门调整：新增用例改为同时断言 `promptText < 7000` 与 `chars < 18000`，并在
   javadoc 中注明该闸门只对固定 fixture 有效。

### 10.3 修复后的回归

见 §7：649 tests / 0 failures / 8 skipped，contracts:check PASS，前端四项 PASS。
**第 10.2 节的改动发生在最后一次 Codex 轮次之后，属于未经 Codex 审查的新增内容**——
已在审查包中明确标注。

---

## 11. 最终 Review Gate

**BLOCKED。**

理由：

- P0：**0 个**（两轮均为 0）。
- P1：**1 个未处理** —— F2（reader 不做 fact↔span 交叉校验）。本案已在 prompt 层收紧并把限制
  固定为可复现测试，但**没有**修改校验语义，因此不能宣布"无未处理 P1"。
- 第 2 轮之后新增的 F5 修复与去冗余改动未经 Codex 审查。
- Contract 与关键回归测试：全部通过（§7）。
- 审查结果可追溯：两轮原始输出、模型 ID、沙箱策略与自我报告的不一致均已记录。

按任务书"审查通过的条件"（无未处理 P0 **且** 无未处理 P1），本 Gate **不满足**，
因此**不创建本地提交**，并生成 ChatGPT 审查包（`docs/reviews/WAVE3A_STAGE2_CHATGPT_REVIEW_PACK.md`）。

---

## 12. Known Gaps

| # | 缺口 | 级别 | 说明 |
| --- | --- | --- | --- |
| KG-1 | reader 不做 fact↔span 交叉校验 | **P1（未处理）** | `SRR:1215` 只解析 fact id，`SRR:1227` 只校验 span 合法性。收紧需要修改 Stage 1、staged 拓扑与 monolith 共用的 reader；且 Live Probe fixture 的 fact 互相重叠（3/4 与 5），严格相等可能误拒。需人工决定是否在下一轮引入"span 必须至少属于一个被引用 fact"的规则 |
| KG-2 | 本轮 prompt 增大 11.9%（≈+423 ESTIMATED tokens） | P2 | 增量几乎全部来自功能性修复；唯一压缩项是 248 字符的 schema 枚举复述 |
| KG-3 | 可见输出缩减未被测量 | P2 | 未发真实请求；不得由历史未保留正文反推 |
| KG-4 | Stage 2 prompt 规模闸门只对固定 fixture 有效 | P3 | fact 清单与来源正文随输入增长 |
| KG-5 | domain guidance 仍可占 2000 字符 | P3 | `DomainStrategySpecRenderer` 同时供 monolith 使用，改动属于跨路径变更 |
| KG-6 | reasoning tokens 未持久化 | P3（既有） | `ModelRun` 冻结，无法从审计读回两阶段 reasoning 成本 |
| KG-7 | 历史 LIVE 响应正文未保留 | P2（既有） | 无法统计各字段实际长度；本轮未做任何此类推测 |
| KG-8 | `npm run smoke` 本轮未执行 | P2 | 见 §7.3 |

---

## 13. 下一次 LIVE 测试的准备情况

**状态：READY（可发起，但需用户授权；本轮不发起）。**

下一次 LIVE 应优先验证本轮的三条假设，按此顺序：

1. **PR-01 是否消失**：Stage 2 现在拿到唯一合法 sourceId。若 `variables[].sourceRefs`
   仍以 PR-01 失败，说明失败另有原因（须保留响应正文）。
2. **是否不再出现 OUTPUT_INVALID 的"非法字段"类错误**：confidence / factRefs / sourceRefs
   的字段体系已与 schema 一致。
3. **预算是否真的压缩了可见输出**：对比同一 fixture 的 `outputTokens`，并**首次保留响应正文**
   （当前 adapter 不暴露正文长度；这是本轮明确记录的观测缺口）。

建议的调用（**不得由本轮执行**）：

```sh
AI_STAGE2_LIVE_PROBE=true \
AI_FAST_PROVIDER=<authorized> AI_FAST_MODEL=<authorized-id> \
AI_FAST_BASE_URL=<authorized-root> AI_FAST_API_KEY=<secret> \
./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=Stage2LiveProbeTest
```

预先声明的判据（避免事后解释）：

| 观测 | 判读 |
| --- | --- |
| `PROBE2 status=SUCCEEDED` 且 `errorType=null` | provider 与 schema 均通 |
| 无 PR-01 / PR-07 / PR-10 | 来源身份链成立 |
| 无 "required artifact ... is absent" | 13 个数组全部出现 |
| `outputTokens` 明显低于 14 928 | 预算生效（**ESTIMATED 预期，非承诺**） |
| 仍为 TIMEOUT 且 reasoning 主导 | 瓶颈在 reasoning 而非可见输出；应转向 reasoning-effort / 预算治理，而不是继续压缩 prompt |

**仍需用户批准的事项**：见最终汇报第 10 节。
