# Wave 3A — Review Gate 状态与证据索引

| 项 | 值 |
| --- | --- |
| **Review Gate 状态** | **BLOCKED（未签署）** |
| 分支 / HEAD | `feat/wave3a-e2e` / `b374bc8` |
| 更新日期 | 2026-10-11 |
| 用途 | 为**人工**审查者提供完成 F5 审查与 Review Gate 签署所需的最小证据集 |
| 本文档是否构成签署 | **不是**。本文档不改变 Review Gate 状态；Codex 与 DeepSeek 均无权签署 |

---

## 0. 文档权威性与时效性（先读本节）

仓库内同时存在**历史轮次记录**与**当前状态记录**，两者冲突时**以当前状态记录为准**：

| 文档 | 性质 | 时效 |
| --- | --- | --- |
| `WAVE3A_STAGE2_OPTIMIZATION.md` | 上一轮**历史快照**（含 `1 个未处理 P1`、`Review Gate BLOCKED`） | **已过期**——该 P1 已由 A1 接受；**按其日期阅读，不追改** |
| `WAVE3A_STAGE2_CHATGPT_REVIEW_PACK.md` | 上一轮**历史审查包**（含 `P1（未处理）`） | **已过期**——同上 |
| `WAVE3A_STAGE2_P1_DECISION_REQUEST.md` | 决策请求 | **已答复（CLOSED）**——方案 A1 已批准 |
| **本文档** | **当前 Review Gate 状态** | **权威（当前）** |
| `WAVE3A_KG1_RISK_ACCEPTANCE.md` | **当前 KG-1 风险接受记录** | **权威（A1 已生效）** |
| `WAVE3A_F5_REVIEW_HANDOFF.md` | **当前 F5 审查交接包** | **权威（当前）** |
| `contracts/PROVENANCE_BOUNDARY.md` | 契约侧**非规范性**边界说明 | **权威（当前）** |
| `WAVE3A_G4_READINESS.md` | **当前 G4 就绪说明与实施计划** | **权威（当前）** |

> **规程**：历史记录**不被追改**（保持证据原样）。
> 若发现历史记录中的结论与当前状态不一致，**以本文档 §1 为准**，不要修改历史记录。

---

## 1. 为什么仍是 BLOCKED

Review Gate 的判定条件来自上一轮**任务书**（不在仓库内，仅被
`WAVE3A_STAGE2_OPTIMIZATION.md:396` 引用）：

> 审查通过的条件 = **无未处理 P0 且 无未处理 P1**

| 阻塞项 | 类别 | 现状 |
| --- | --- | --- |
| B-a：KG-1（reader 不做 fact↔span 交叉校验） | 契约边界 | ✅ 已由用户按 **A1** 转为「已批准的已知边界」——见 `WAVE3A_KG1_RISK_ACCEPTANCE.md` §0 |
| B-b：第二轮之后的 F5 修复与去冗余改动**未经任何审查轮次** | **人工审查** | **WAITING_REVIEW** —— 证据包已就绪（`WAVE3A_F5_REVIEW_HANDOFF.md`，含 Q1–Q6 与 PASS/FAIL 标准），**等待独立人工审查者**；不再重复调查 |
| B-c：正式 Review Gate 签署 | **人工门禁** | **WAITING_APPROVAL** —— 签署页见 §7，**未填写、未生效** |

**结论**：B-a 已被正式风险接受流程消解；**B-b 与 B-c 仍开放，故状态保持 BLOCKED。**
本状态**不是**技术失败，也**不得**被解读为 PASS。

> **停止重复调查声明**：B-b 的阻塞原因是「缺少人工审查者」，**不是**证据缺失。
> 证据已齐备（代码、契约、测试、风险、UNKNOWN 清单、快照 SHA）。
> 在人工审查完成前，本项**不再被重复调查或重新生成材料**。

---

## 2. 状态口径（严格区分）

| 项 | 口径 | 依据 |
| --- | --- | --- |
| 契约漂移（`contracts:check`） | **PASS** | 见 §4 |
| 定点回归（reader / two-stage / 契约一致性 / 优化用例） | **PASS** | 见 §5 |
| KG-1 | **已批准的已知边界**（**非**技术修复 PASS） | `WAVE3A_KG1_RISK_ACCEPTANCE.md` §0 |
| F5 审查 | **BLOCKED**（未完成） | §3 |
| Review Gate | **BLOCKED**（未签署） | §1 |
| G4（Full Real AnalysisResult） | **FAIL** | `WAVE3A_GATE.md` §6；需真实可用 provider，**离线结果不可替代** |
| 可见输出缩减 | **UNKNOWN** | 未发真实请求，不得由历史正文反推 |

---

## 3. B-b：F5 未审查变更的精确范围

**性质**：以下改动发生在**最后一次 Codex 轮次之后**，从未被任何审查轮次覆盖。

| # | 改动 | 位置 | 说明 |
| --- | --- | --- | --- |
| F5-1 | 引用字段要求改为按 item 类型点名 | `prompts/two-stage-reasoning/prompt.txt:5-6,10` | 消除"每个基于事实的产物都要给 factRefs 和 sourceRefs"这一对多类型错误的笼统表述 |
| F5-2 | 删除正文与读最后一段对"数组必须存在/空数组语义"的重复陈述 | `prompt.txt` | 读最后一段独家承载，避免双份规范 |
| F5-3 | 规模闸门改为同时断言 `promptText < 7000` 与 `chars < 18000` | `Stage2OutputOptimizationTest.theStageTwoPromptSizeIsMeasuredAndBounded` | javadoc 已注明该闸门只对固定 fixture 有效 |

**审查者需要判断的问题**（应与上一轮审查包 §10 一并答复）：

1. F5-1 的类型点名是否**完整且无遗漏**（12 类产物中每一类的引用字段是否都被正确描述）？
2. F5-2 的去冗余是否**删掉了任何硬约束**（与 `theShortenedContractKeepsEveryHardConstraint` 的断言是否足够）？
3. F5-3 的规模闸门是否会被误读为对任意输入的保证？

**已提供的对应测试**（可作为审查起点）：
`thePromptNamesTheReferenceFieldsEachItemTypeActuallyDeclares`、
`theReadLastContractDoesNotRestateTheSchemaArrayList`、
`theShortenedContractKeepsEveryHardConstraint`、
`theStageTwoPromptSizeIsMeasuredAndBounded`。

---

## 4. 契约不变性证据（B-b 的关键前提）

```
$ git status --porcelain -- contracts/ apps/web/src/lib/api.generated.ts \
      apps/api/src/main/java/com/signalframe/contract
(空输出)
```

→ **`contracts/`、生成的 Java 契约、生成的 TS 客户端：零改动。**

```
$ git diff --stat b374bc8..685c9fd
 TwoStageSynthesisService.java            | 35 ++++++----
 two-stage-reasoning/prompt.txt           | 39 ++++++----
 two-stage-reasoning/version.txt          |  2 +-
 Stage1ProbeRehearsalTest.java            |  2 +-
 TwoStagePostgresIntegrationTest.java     |  4 +--
 TwoStageSynthesisTest.java               |  6 ++--
 6 files changed, 59 insertions(+), 29 deletions(-)
```

**变更已提交为 4 个本地提交**（基线 `b374bc8`，未 push）：

| # | Commit | 内容 |
| --- | --- | --- |
| 1 | `0467d34` | Stage 2 prompt v4（实现 + 版本断言） |
| 2 | `41ea6f6` | `Stage2OutputOptimizationTest`（新增 15 用例） |
| 3 | `3c7a56e` | 轮次报告 + P1 决策请求 + KG-1 风险接受记录 |
| 4 | `685c9fd` | 审查包 + `contracts/PROVENANCE_BOUNDARY.md` |

工作区现已**干净**（仅 `AGENTS.local.md` 与本文档等元数据未跟踪）。

```
$ npm run contracts:check
OpenAPI valid; generated Java and TypeScript are current.   → PASS
```

额外不变性锚点：`theOptimizationDidNotRelaxTheReplySchemaRoot`（根对象子集未被放松）；
`Stage2ContractConsistencyTest`（7/7 PASS，锁定嵌套定义同源性）。

**prompt 版本已如实推进**：`two-stage-reasoning/version.txt` = `two-stage-reasoning-v4`
（版本随改动递增，审计可追溯）。

---

## 5. 测试证据（本轮实测）

| # | 检查 | 命令 | 结果 |
| --- | --- | --- | --- |
| T-1 | 契约漂移 | `npm run contracts:check` | **PASS**（OpenAPI valid；生成 Java/TS 最新） |
| T-2 | **全量后端回归** | `./scripts/mvn.sh -B -f apps/api/pom.xml test` | **PASS — 649 tests, 0 failures, 0 errors, 8 skipped · BUILD SUCCESS** |
| T-3 | 定点回归 | `mvn test -Dtest=Stage2OutputOptimizationTest,TwoStageSynthesisTest,StageResponseReaderTest,Stage2ContractConsistencyTest` | **PASS — 65 tests, 0 failures, 0 errors, 0 skipped** |
| T-4 | R-7 边界行为 | 6 个具名测试（见 `WAVE3A_KG1_RISK_ACCEPTANCE.md` §9.1） | **PASS — 6/6** |
| T-5 | 空白卫生 | `git diff --check` | **OK（exit 0）** |
| T-6 | **浏览器 Smoke（G5 层）** | `WEB_PORT=3101 API_BASE_URL=http://127.0.0.1:8090 npm run smoke` | **PASS — 24 passed / 0 failed**（隔离 Postgres 55432 + 隔离 API 8090） |

定点回归构成：`TwoStageSynthesisTest` 20 · `Stage2ContractConsistencyTest` 7 ·
`Stage2OutputOptimizationTest` 15 · `StageResponseReaderTest` 23。

### 5.1 8 个 skipped 的性质（**不是 PASS**）

全部为 opt-in 的真实 provider 测试，本轮未授权、未执行：

| 测试类 | skipped |
| --- | --- |
| `RealModelEndToEndTest` | 5 |
| `Stage1LiveProbeTest` | 1 |
| `Stage2LiveProbeTest` | 1 |
| `Stage2MinimalOutputDiagnosticTest` | 1 |

### 5.2 未执行的检查（诚实声明）

**未发起任何真实 provider 请求**（G4 相关，见 §2 与 `WAVE3A_G4_READINESS.md`）。

### 5.3 Smoke 执行记录（含一次失败与根因，完整留痕）

**第一次运行：3 failed / 21 passed。** 失败用例：
`LIVE and MOCK are distinguished and the credential value never reaches the page`（三套浏览器各一次）。

**根因（非代码回归）**：启动 API 时**遗漏 `AI_FAST_API_KEY` 前置**。
该用例要求 `analysis.deep` 的凭据**可解析**才会显示 `LIVE`；
未注入该变量时凭据不可解析，profile 报 `MOCK`，断言 `getByText("LIVE")` 超时失败。
`WAVE3A_GATE.md:400` 记录的既有成功配置**包含** `AI_FAST_API_KEY=ollama`（占位值，非机密）。

**修复**：以文档记录的完整环境重启 API（`AI_FAST_API_KEY=ollama`），并重置隔离库保证干净起点。
**第二次运行：24 passed / 0 failed（12.1s）。** 自主排障 1 轮即解决。

**副作用与处置**：smoke 的 spec 会**无条件重写** `docs/evidence/*.png`（4 个被跟踪截图）。
本轮 UI 未改动，属非预期二进制变更，已**还原至 HEAD 版本**，未纳入任何提交。
若团队希望提交刷新后的视觉证据，应作为独立决定处理。

**清理**：API 进程已停止，隔离容器 `sf-smoke-pg` 已删除，端口 8090 已释放；
共享库容器 `signalframe-postgres-1` **未被触碰**。

安全执行规程（供后续复现，参照 `WAVE3A_GATE.md` §8 的 G5 做法）：

```sh
docker run -d --name sf-smoke-pg -p 55432:5432 \
  -e POSTGRES_USER=signalframe -e POSTGRES_PASSWORD=<local> -e POSTGRES_DB=signalframe \
  postgres:17.6-alpine
./scripts/mvn.sh -B -f apps/api/pom.xml package -DskipTests
DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/signalframe DATABASE_USER=signalframe \
POSTGRES_PASSWORD=<local> SERVER_PORT=8090 AI_FAST_API_KEY=ollama \
  .tools/jdk/Contents/Home/bin/java -jar apps/api/target/api-0.1.0.jar &
WEB_PORT=3101 API_BASE_URL=http://127.0.0.1:8090 npm run smoke
# 清理：pkill -f api-0.1.0.jar; docker rm -f sf-smoke-pg
```

> **注意**：`AI_FAST_API_KEY` **必需**（占位值即可），否则 `LIVE/MOCK` 用例必然失败。
> 事后需 `git checkout -- docs/evidence/` 还原被重写的截图。

---

## 6. 人工审查者需要完成的事项（checklist）

- [ ] **b1**：完成第二轮 F5 变更的独立审查（§3 三个问题 + 上一轮审查包 §10 遗留问题）。
- [ ] **b2**：确认 KG-1 的 A1 风险接受可接受（记录已生效，见 `WAVE3A_KG1_RISK_ACCEPTANCE.md`）。
- [ ] **b3**：就 R-6（provenance 归属一致性）是否立项、以及规则取「包含」还是「重叠」给出结论。
- [ ] **b4**：签署正式 **Review Gate**（**仅人工**；Codex 意见不可替代）。
- [ ] **b5**：决定是否批准提交本轮改动（当前 6 改 + 未跟踪文档**均未提交**）。

**G4 不在本 Gate 的签署范围内**：G4 需真实可用 provider 凭据与真实请求，
属单独授权事项，**不得**用本 Gate 的通过替代。

---

## 7. 正式 Review Gate 人工签署页（模板）

> 本节是**签署模板**，当前**未填写、未生效**。签署只能由人工完成；
> DeepSeek 与 Codex 均无权填写或代签。

### 7.1 签署人应确认的事实

| # | 声明 | 是否确认 |
| --- | --- | --- |
| D-1 | 已独立审查第二轮之后的 F5 变更（F5-1 / F5-2 / F5-3），**无未处理 P0/P1** | ☐ |
| D-2 | 已确认 KG-1 的 **A1** 风险接受范围与残余风险，并接受之 | ☐ |
| D-3 | 已确认契约工件**逐字未改**（`contracts:check` PASS + 工件哈希） | ☐ |
| D-4 | 已确认全量回归 **649/0/0/8-skipped** 与定点 **65/65** 的真实性 | ☐ |
| D-5 | 已知悉 8 个 skipped 是未授权的真实 provider 测试，**不是 PASS** | ☐ |
| D-6 | 已就 **R-6** 给出结论（立项 / 暂缓 / 规则形式） | ☐ |
| D-7 | 已确认 **G4 不在本 Gate 范围**，且仍为 FAIL | ☐ |
| D-8 | 已确认提交范围并授权 Git 写操作 | ☐ |

### 7.2 签署

| 项 | 值 |
| --- | --- |
| Review Gate 结论 | ☐ PASS ☐ 有条件 PASS ☐ 驳回 |
| 签署人 | __________________ |
| 签署日期 | __________________ |
| 附条件（如有） | __________________ |

### 7.3 签署**不**代表的事项（防止误读）

- **不**代表 G4 通过（G4 仍为 FAIL，需真实验证）；
- **不**代表 KG-1 已被技术修复（仅为已批准的风险边界）；
- **不**代表 R-6 已完成；
- **不**代表可以跳过真实 provider 验证。

---

## 8. 明确未做的事（本轮）

- 未把 Review Gate 改为 PASS；未填写 §7 签署页；
- 未代替人工签署任何门禁；
- 未修改 Schema / SourceRef 语义 / Stage Contract / 验收标准；
- 未修改 `contracts/` 或任何生成物（仅**新增**一份非规范性说明）；
- 未提交、推送、合并或部署；
- 未发起任何真实 provider 请求；
- 未调用 Codex（无"自主排障失败且关键技术阻塞"的卡点）。

---

## 9. 相关文档索引

| 文档 | 内容 |
| --- | --- |
| `WAVE3A_KG1_RISK_ACCEPTANCE.md` | KG-1 风险接受（A1）与 R-7 验证证据 |
| `WAVE3A_F5_REVIEW_HANDOFF.md` | F5 独立审查交接包（Q1–Q6） |
| `WAVE3A_G4_READINESS.md` | G4 缺失条件、调用/token 上界、实施步骤 |
| `WAVE3A_STAGE2_P1_DECISION_REQUEST.md` | P1 决策请求（已答复，CLOSED） |
| `contracts/PROVENANCE_BOUNDARY.md` | 契约侧非规范性边界说明 |
| `WAVE3A_STAGE2_OPTIMIZATION.md` / `WAVE3A_STAGE2_CHATGPT_REVIEW_PACK.md` | **历史轮次记录（已过期，不追改）** |
