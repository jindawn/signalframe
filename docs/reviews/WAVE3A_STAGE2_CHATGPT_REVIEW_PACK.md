# Wave 3A Stage 2 优化 —— ChatGPT 独立审查包

生成时间：2026-10-11 · 生成者：主开发 Agent（DeepSeek V4.1 Flash）
用途：Codex 自动审查闸门为 **BLOCKED**（0 个 P0、1 个未处理 P1），需人工/网页版独立审查。

本文件是**自包含**的：包含完整生产 diff、完整新增测试源码、契约摘要、测试证据、
两轮 Codex 审查结论与未解决问题。不需要再收集或粘贴任何材料。

---

## 0. 审查者请先读这一段

**请不要只审"是否变短了"。** 本轮的真实结论是：prompt 变大了 11.9%，因为其中
包含了三处**功能性缺陷修复**（见 §5）。请重点判断：

1. 这三处修复是否正确（尤其 §5.1 的来源身份缺失是否真的是缺陷）？
2. 为了让输出变小而加入的**预算条款**是否可能损害分析质量或协议语义？
3. 未修复的 P1（reader 不做 fact↔span 交叉校验）应当在本轮修，还是应当另开一轮？
4. 新增测试是否真的覆盖业务边界，而不是只锁措辞？

---

## 1. 环境与基线

| 项 | 值 |
| --- | --- |
| 仓库 | `/Users/wumj/workspace/signalframe-worktrees/wave3a-e2e` |
| 分支 | `feat/wave3a-e2e` |
| 基线 commit | `b374bc8` |
| 本轮是否提交 | **未提交**（Review Gate 非 PASS） |
| 是否 push / merge / tag | 否 |
| 真实模型请求 | **本轮未发起任何真实请求** |
| 前端改动 | 无 |

改动清单（6 改 1 增）：

```
 M apps/api/src/main/java/com/signalframe/analysis/application/steps/TwoStageSynthesisService.java
 M apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt
 M apps/api/src/main/resources/prompts/two-stage-reasoning/version.txt
 M apps/api/src/test/java/com/signalframe/analysis/Stage1ProbeRehearsalTest.java
 M apps/api/src/test/java/com/signalframe/analysis/TwoStagePostgresIntegrationTest.java
 M apps/api/src/test/java/com/signalframe/analysis/application/steps/TwoStageSynthesisTest.java
?? apps/api/src/test/java/com/signalframe/analysis/application/steps/Stage2OutputOptimizationTest.java
```

零改动（这一点本身就是审查对象）：`contracts/openapi.yaml`、生成的 Java/TS、
Flyway 迁移、`AnalysisResultValidator`、`StageResponseReader`、`StageReplySchema`、
`AnalysisOutputSchema`、`ConfidenceRubric`、monolith 路径、`application.yml`。

---

## 2. 完整生产 diff（`git diff`，已跟踪文件）

```diff
diff --git a/apps/api/src/main/java/com/signalframe/analysis/application/steps/TwoStageSynthesisService.java b/apps/api/src/main/java/com/signalframe/analysis/application/steps/TwoStageSynthesisService.java
index c5dcfc3..1437c80 100644
--- a/apps/api/src/main/java/com/signalframe/analysis/application/steps/TwoStageSynthesisService.java
+++ b/apps/api/src/main/java/com/signalframe/analysis/application/steps/TwoStageSynthesisService.java
@@ -1006,7 +1006,14 @@ public class TwoStageSynthesisService {
       .append("\n<untrusted_source>\n")
       .append(state.news().source().text())
       .append("\n</untrusted_source>\n")
-      .append("\nVALIDATED FACTS from stage 1 (these ids are the only fact references you may cite):\n");
+      // The stage-2 reply must carry sourceRefs[].sourceId, and the reader verifies it
+      // against the analyzed source (PR-01). Stage 1 was told this id; stage 2 was not,
+      // so a model that followed the schema could only invent a UUID or drop its spans.
+      // Stating it once here is what makes the source identity chain reproducible.
+      .append("\nThe analyzed source id is ")
+      .append(state.news().source().id())
+      .append(". Every sourceRefs[].sourceId you emit must be exactly this id: it is the only source you may cite.\n")
+      .append("\nVALIDATED FACTS from stage 1 (these ids are the only fact references you may cite; each span is verbatim source text):\n");
     for (var fact : state.facts()) {
       var span = fact.sourceRefs().isEmpty() ? null : fact.sourceRefs().getFirst();
       text
@@ -1020,13 +1027,27 @@ public class TwoStageSynthesisService {
         .append(fact.statement())
         .append('\n');
     }
+    // The tail repeats no array list: the schema above already declares the 13 arrays
+    // and their item fields, and restating them here was a second, drifting copy of the
+    // same information. What stays is what the schema cannot say — which ids are legal,
+    // how big the reply should be, and which constraint overrides the prose above.
     text.append("\nOUTPUT CONTRACT (read last): reply with one JSON object and nothing else.")
-      .append(" It carries summary, variables, mechanisms, stakeholders, firstOrderEffects, secondOrderEffects,")
-      .append(" hypotheses, alternativeExplanations, counterArguments, falsificationConditions, corroboratingSignals,")
-      .append(" verificationIndicators, predictions and unknowns. Every array must be present: use [] only for a stage")
-      .append(" you evaluated and found empty, and emit an explicit UNKNOWN item when the input does not support an")
-      .append(" artifact. Cite only the fact ids listed above; never invent a fact, a source or a span, and never state")
-      .append(" a confidence score. predictions need expectedBy and verificationCriteria. No wrapper key, no markdown.");
+      .append(" It carries summary plus the 13 arrays the schema above declares, and its keys are exactly those.")
+      .append(" Every array must be present: use [] only for an artifact you evaluated and found empty, and emit an")
+      .append(" explicit UNKNOWN item when the input does not support an artifact — one item naming the artifacts")
+      .append(" that share one cause is enough. Cite only the fact ids listed above, and set every")
+      .append(" sourceRefs[].sourceId to ")
+      .append(state.news().source().id())
+      .append("; never invent a fact, a source or a span.")
+      .append(" Item-level `confidence` is an allowed advisory 0-100 diagnostic on an item whose schema declares it;")
+      .append(" never add the field to an item that does not declare it. The")
+      .append(" snapshot-level confidenceAssessment is computed deterministically and must not be stated.")
+      .append(" predictions need expectedBy and verificationCriteria.")
+      .append(" Budget for a short or thin source: one to two hypotheses, at most one mechanism and at most two")
+      .append(" predictions, with the other arrays empty unless the facts support more. Never drop a counter")
+      .append(" argument, an alternative explanation, a falsification condition or a prediction's verification")
+      .append(" criteria that materially affects the conclusion. Keep `reasoning` to one or two sentences and do")
+      .append(" not copy the source beyond the spans you cite. No wrapper key, no markdown.");
     return text.toString();
   }
 }
diff --git a/apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt b/apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt
index f866a8c..7a5bc29 100644
--- a/apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt
+++ b/apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt
@@ -1,22 +1,32 @@
 You are the analysis stage of a research pipeline. Reason over the validated facts of stage 1, which are listed below
 with the only fact ids you may cite. The fact set is already settled: you may not add, restate or re-span a fact, and
 you may not cite a source that is not the one given.
-For every artifact that rests on a fact, give BOTH the fact id in its factRefs and the exact source span it came
-from in its sourceRefs (sourceId, quote, UTF-16 startOffset and endOffset of the same source text). The span is what
-links an item to its fact when the snapshot is re-read, so an item with a fact id but no span loses its evidence
-link. State variables that are actually changing, the mechanism that connects them, who is exposed and in which direction,
+For every artifact that rests on a fact, cite the fact ids in the field your own schema declares for them: factRefs
+for a variable, mechanism, stakeholder, effect, alternative or counter argument, supportingFactRefs for a hypothesis,
+basisFactRefs for a prediction. Where the item type also declares sourceRefs (variables, mechanisms, stakeholders,
+effects, alternatives, counter arguments, plan items and unknowns — plan items and unknowns declare a span but no
+fact reference), add the exact source span it came from (sourceId, quote, UTF-16 startOffset and endOffset of the
+same source text). The span is what links an item to its fact when the snapshot is re-read, so an item with a fact id
+but no span loses its evidence link. Falsification conditions and signals declare neither a fact reference nor a
+span: never add one to them.
+Copy a span only from the fact list above, and for each fact id you cite give that fact's own span: never reconstruct
+a span, and never attach a span that belongs to a different fact.
+State variables that are actually changing, the mechanism that connects them, who is exposed and in which direction,
 first- and second-order effects, and falsifiable hypotheses with their assumptions. Every hypothesis needs at least
 one falsification condition and at least one alternative explanation of the same facts; provide the strongest counter
 argument as well. Predictions must be observable, time bounded and carry verification criteria that separate
 confirmation from partial confirmation and rejection. A corroborating signal is an expected future observable, not
 existing evidence.
 When the facts do not support an artifact, emit an explicit UNKNOWN item describing what is missing, why, and what
-would resolve it, instead of asserting something unsupported. Never invent a fact, a span or a source.
-Each item carries a `confidence` field: that is an advisory item-level diagnostic on the 0-100 judgment scale, not a
-probability, and you should set it from how well that one item is supported. The snapshot's own confidenceAssessment
-is not yours to decide: it is computed deterministically from the assembled snapshot and any number you put there is
-discarded. Do not present any score as a probability of truth.
-The reply shape is specified at the end of this prompt and overrides anything here.
+would resolve it, instead of asserting something unsupported. When several unsupported artifacts share one cause, a
+single UNKNOWN item naming them is enough. Never invent a fact, a span or a source.
+Only an item type whose schema declares a confidence field carries one (`confidence`, or `advisoryConfidence` on a
+hypothesis): where it is declared, set it as an advisory item-level diagnostic on the 0-100 judgment scale, not a
+probability, from how well that one item is supported. Never add a confidence field to an item whose schema does not
+declare one. The snapshot's own confidenceAssessment is not yours to decide: it is computed deterministically from
+the assembled snapshot and any number you put there is discarded. Do not present any score as a probability of truth.
+The reply shape and its size budget are specified at the end of this prompt and override anything here: read that
+contract before you decide how much to write.
 
 Falsification conditions must be decidable by a third party. For each condition give an observable that can be
 checked, an explicit decision boundary, and a comparison a stranger could apply without asking you what you meant.
@@ -24,8 +34,7 @@ A boundary such as "if it does not work out" is not decidable and will be reject
 establish the threshold, name the threshold you would need and state that it is not established: never invent a
 number, a date or a criterion that the source does not support.
 
-Size the reply to the evidence, not to the schema. Every array must be present, but an array with no supported
-content must be the empty array []: an empty array means "assessed, nothing supported", which is a valid and honest
-answer. Do not fill an array to look thorough, do not restate the same point under several arrays, and do not
-generate a mechanism, a stakeholder, an effect, a signal, a prediction or a plan item that no fact supports. A short,
-fully supported reply is better than a long, padded one, and padding is rejected rather than rewarded.
+Size the reply to the evidence, not to the schema: an array with no supported content is the empty array [], which
+means "assessed, nothing supported" and is a valid and honest answer. Do not fill an array to look thorough, do not
+restate the same point under several arrays, and do not generate an artifact that no fact supports. A short, fully
+supported reply is better than a long, padded one, and padding is rejected rather than rewarded.
diff --git a/apps/api/src/main/resources/prompts/two-stage-reasoning/version.txt b/apps/api/src/main/resources/prompts/two-stage-reasoning/version.txt
index 2540c8c..0b74473 100644
--- a/apps/api/src/main/resources/prompts/two-stage-reasoning/version.txt
+++ b/apps/api/src/main/resources/prompts/two-stage-reasoning/version.txt
@@ -1 +1 @@
-two-stage-reasoning-v3
+two-stage-reasoning-v4
diff --git a/apps/api/src/test/java/com/signalframe/analysis/Stage1ProbeRehearsalTest.java b/apps/api/src/test/java/com/signalframe/analysis/Stage1ProbeRehearsalTest.java
index 2951ee3..d09e577 100644
--- a/apps/api/src/test/java/com/signalframe/analysis/Stage1ProbeRehearsalTest.java
+++ b/apps/api/src/test/java/com/signalframe/analysis/Stage1ProbeRehearsalTest.java
@@ -214,7 +214,7 @@ class Stage1ProbeRehearsalTest {
     var synthesis = new com.signalframe.ai.domain.ModelRequest(
       UUID.randomUUID(),
       ModelPurpose.SYNTHESIS,
-      "two-stage-reasoning-v3",
+      "two-stage-reasoning-v4",
       "prompt",
       news,
       com.signalframe.ai.AiTestFixtures.profile("openai-compatible", STUB_MODEL),
diff --git a/apps/api/src/test/java/com/signalframe/analysis/TwoStagePostgresIntegrationTest.java b/apps/api/src/test/java/com/signalframe/analysis/TwoStagePostgresIntegrationTest.java
index d479e8a..833c453 100644
--- a/apps/api/src/test/java/com/signalframe/analysis/TwoStagePostgresIntegrationTest.java
+++ b/apps/api/src/test/java/com/signalframe/analysis/TwoStagePostgresIntegrationTest.java
@@ -143,7 +143,7 @@ class TwoStagePostgresIntegrationTest {
       result.provenance().promptVersions()
     );
     assertTrue(
-      result.provenance().promptVersions().contains("two-stage-reasoning-v3")
+      result.provenance().promptVersions().contains("two-stage-reasoning-v4")
     );
 
     // ---- both stages are audited separately ---------------------------------
@@ -162,7 +162,7 @@ class TwoStagePostgresIntegrationTest {
     assertTrue(purposes.contains(ModelPurpose.SYNTHESIS), purposes.toString());
     var versions = Arrays.stream(runs).map(ModelRun::promptVersion).toList();
     assertTrue(versions.contains("two-stage-facts-v1"), versions.toString());
-    assertTrue(versions.contains("two-stage-reasoning-v3"), versions.toString());
+    assertTrue(versions.contains("two-stage-reasoning-v4"), versions.toString());
     for (var run : runs) {
       assertEquals("SUCCEEDED", run.status());
       assertNull(run.errorType());
diff --git a/apps/api/src/test/java/com/signalframe/analysis/application/steps/TwoStageSynthesisTest.java b/apps/api/src/test/java/com/signalframe/analysis/application/steps/TwoStageSynthesisTest.java
index 6e4f3b6..6331c14 100644
--- a/apps/api/src/test/java/com/signalframe/analysis/application/steps/TwoStageSynthesisTest.java
+++ b/apps/api/src/test/java/com/signalframe/analysis/application/steps/TwoStageSynthesisTest.java
@@ -386,7 +386,7 @@ class TwoStageSynthesisTest {
       result.provenance().promptVersions().contains("two-stage-facts-v1")
     );
     assertTrue(
-      result.provenance().promptVersions().contains("two-stage-reasoning-v3")
+      result.provenance().promptVersions().contains("two-stage-reasoning-v4")
     );
   }
 
@@ -622,7 +622,7 @@ class TwoStageSynthesisTest {
       result.provenance().promptVersions()
     );
     assertTrue(
-      result.provenance().promptVersions().contains("two-stage-reasoning-v3")
+      result.provenance().promptVersions().contains("two-stage-reasoning-v4")
     );
   }
 
@@ -672,7 +672,7 @@ class TwoStageSynthesisTest {
       byPurpose.get(ModelPurpose.FACT_EXTRACTION).promptVersion()
     );
     assertEquals(
-      "two-stage-reasoning-v3",
+      "two-stage-reasoning-v4",
       byPurpose.get(ModelPurpose.SYNTHESIS).promptVersion()
     );
     for (var run : runs.all()) {
```

三个被改的测试文件只是把固定的 prompt 版本字符串 `two-stage-reasoning-v3` 换成
`two-stage-reasoning-v4`（PR-15 审计断言），无其他改动，故不重复贴出。

---

## 3. 完整 prompt 模板 v4（`apps/api/src/main/resources/prompts/two-stage-reasoning/prompt.txt`）

```text
You are the analysis stage of a research pipeline. Reason over the validated facts of stage 1, which are listed below
with the only fact ids you may cite. The fact set is already settled: you may not add, restate or re-span a fact, and
you may not cite a source that is not the one given.
For every artifact that rests on a fact, cite the fact ids in the field your own schema declares for them: factRefs
for a variable, mechanism, stakeholder, effect, alternative or counter argument, supportingFactRefs for a hypothesis,
basisFactRefs for a prediction. Where the item type also declares sourceRefs (variables, mechanisms, stakeholders,
effects, alternatives, counter arguments, plan items and unknowns — plan items and unknowns declare a span but no
fact reference), add the exact source span it came from (sourceId, quote, UTF-16 startOffset and endOffset of the
same source text). The span is what links an item to its fact when the snapshot is re-read, so an item with a fact id
but no span loses its evidence link. Falsification conditions and signals declare neither a fact reference nor a
span: never add one to them.
Copy a span only from the fact list above, and for each fact id you cite give that fact's own span: never reconstruct
a span, and never attach a span that belongs to a different fact.
State variables that are actually changing, the mechanism that connects them, who is exposed and in which direction,
first- and second-order effects, and falsifiable hypotheses with their assumptions. Every hypothesis needs at least
one falsification condition and at least one alternative explanation of the same facts; provide the strongest counter
argument as well. Predictions must be observable, time bounded and carry verification criteria that separate
confirmation from partial confirmation and rejection. A corroborating signal is an expected future observable, not
existing evidence.
When the facts do not support an artifact, emit an explicit UNKNOWN item describing what is missing, why, and what
would resolve it, instead of asserting something unsupported. When several unsupported artifacts share one cause, a
single UNKNOWN item naming them is enough. Never invent a fact, a span or a source.
Only an item type whose schema declares a confidence field carries one (`confidence`, or `advisoryConfidence` on a
hypothesis): where it is declared, set it as an advisory item-level diagnostic on the 0-100 judgment scale, not a
probability, from how well that one item is supported. Never add a confidence field to an item whose schema does not
declare one. The snapshot's own confidenceAssessment is not yours to decide: it is computed deterministically from
the assembled snapshot and any number you put there is discarded. Do not present any score as a probability of truth.
The reply shape and its size budget are specified at the end of this prompt and override anything here: read that
contract before you decide how much to write.

Falsification conditions must be decidable by a third party. For each condition give an observable that can be
checked, an explicit decision boundary, and a comparison a stranger could apply without asking you what you meant.
A boundary such as "if it does not work out" is not decidable and will be rejected (STG-12). If the source does not
establish the threshold, name the threshold you would need and state that it is not established: never invent a
number, a date or a criterion that the source does not support.

Size the reply to the evidence, not to the schema: an array with no supported content is the empty array [], which
means "assessed, nothing supported" and is a valid and honest answer. Do not fill an array to look thorough, do not
restate the same point under several arrays, and do not generate an artifact that no fact supports. A short, fully
supported reply is better than a long, padded one, and padding is rejected rather than rewarded.
```

`version.txt` 同步改为 `two-stage-reasoning-v4`。

---

## 4. 新增测试源码（完整，713 行）

`apps/api/src/test/java/com/signalframe/analysis/application/steps/Stage2OutputOptimizationTest.java`

```java
package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.NewsItem;
import com.signalframe.contract.SourceRef;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Stage 2 输出优化的离线回归（确定性，不接触任何 provider）。
 *
 * <p>本轮只做三件事：把 {@code two-stage-reasoning} 的可见输出按证据规模约束、删掉
 * 读最后一段里对 schema 的重复描述，并补上 Stage 2 一直没有拿到的来源身份。
 * 本套用例锁定这三件事，同时锁定"优化不得削弱任何原有约束"：
 *
 * <ul>
 *   <li>sourceId 必须出现在 Stage 2 prompt 里（此前只有 Stage 1 被告知，而 PR-01
 *       校验要求 Stage 2 产物的 sourceRefs 指向同一个来源）；</li>
 *   <li>item-level {@code confidence} 只能出现在 schema 真的声明了它的 item 类型上
 *       （{@code Prediction}/{@code FalsificationCondition}/{@code Signal}/{@code Unknown}
 *       没有该组件，{@code Hypothesis} 用的是 {@code advisoryConfidence}），且读最后一段
 *       不得再与正文矛盾；</li>
 *   <li>读最后一段不得再罗列 schema 已经声明的 13 个数组；</li>
 *   <li>预算约束必须落在读最后一段（prompt 明确声明该段覆盖前面的正文），并且不得
 *       成为删除关键反证、替代解释、证伪条件或预测判别标准的许可；</li>
 *   <li>回复 schema 的根对象未被放松：13 个数组 + summary 仍全部 required，
 *       {@code additionalProperties:false} 仍在（Contract 未漂移由
 *       {@code npm run contracts:check} 与 {@code git diff} 证明）；</li>
 *   <li>协议允许的空数组仍然合法，且空掉的数组必须在 {@code unknowns} 里说明
 *       （STG-09 rule 1 + PR-05）；</li>
 *   <li>伪造 sourceRef、篡改 offset 仍被拒绝；长字符串与大量条目仍按原有 reader 规则
 *       处理；</li>
 *   <li>已知边界被显式记录：reader 分别校验 factRefs 与 sourceRefs，但不做 fact↔span
 *       交叉校验（见 {@code theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem}）。</li>
 * </ul>
 */
class Stage2OutputOptimizationTest {

  static final JsonCodec JSON = PipelineFixtures.json();

  static final String SHORT_NEWS =
    "某公司于本周二发布公告称，其与一家供应商签署的零部件采购协议将于本季度到期，" +
    "双方已就续约价格启动谈判。公告提到，现有协议覆盖三种规格的零部件，年采购金额约为两亿元。";

  static final UUID F1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
  static final UUID F2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
  static final UUID F3 = UUID.fromString("33333333-3333-4333-8333-333333333333");

  // ---- fixtures -------------------------------------------------------------

  static NewsItem news() {
    return PipelineFixtures.news(SHORT_NEWS);
  }

  static TwoStageSynthesisService service() {
    return new TwoStageSynthesisService(
      PipelineFixtures.router(),
      new PipelineFixtures.ScriptedGateway(request -> "{}"),
      new PipelineFixtures.RecordingRuns(),
      new StageResponseReader(JSON),
      PipelineFixtures.promptLibrary(),
      JSON,
      PipelineFixtures.gateA(JSON),
      ConfidenceRubric.deterministic(),
      PipelineFixtures.strategies(),
      1,
      0,
      0L,
      1,
      0,
      0L,
      0,
      0,
      0,
      0,
      1,
      0L
    );
  }

  static SourceRef span(NewsItem news, int start, int end) {
    var text = news.source().text();
    var stop = Math.min(end, text.length());
    return new SourceRef(news.source().id(), text.substring(start, stop), start, stop);
  }

  static List<ProtocolModel.Fact> facts(NewsItem news) {
    return List.of(
      fact(F1, span(news, 0, 20)),
      fact(F2, span(news, 20, 42)),
      fact(F3, span(news, 42, 62))
    );
  }

  static ProtocolModel.Fact fact(UUID id, SourceRef ref) {
    return new ProtocolModel.Fact(
      id,
      ref.quote(),
      "来源如此陈述",
      List.of(ref),
      ProtocolModel.ProofStatus.REPORTED
    );
  }

  static PipelineState state(NewsItem news) {
    return PipelineState
      .initial(
        UUID.randomUUID(),
        "stage2-output-optimization",
        news,
        Instant.now()
      )
      .withFacts(facts(news));
  }

  static String promptFor(NewsItem news, String guidance) {
    var service = service();
    return service.reasoningPromptText(service.reasoningPrompt(), state(news), guidance);
  }

  /** 读最后一段：prompt 明确声明它覆盖前面的正文，因此它才是有效的约束位置。 */
  static String readLast(String prompt) {
    var at = prompt.lastIndexOf("OUTPUT CONTRACT");
    assertTrue(at > 0, "读最后一段必须存在");
    return prompt.substring(at);
  }

  /**
   * prompt 文本按可读性折行，同一句话可能跨行。断言前把空白归一化，否则断言结果会被
   * 折行位置左右（而不是被语句本身左右）。
   */
  static String flat(String text) {
    return text.replaceAll("\\s+", " ");
  }

  // ---- 1. 来源身份 ----------------------------------------------------------

  /**
   * Stage 2 的产物要带 {@code sourceRefs[].sourceId}，reader 会把它与"被分析来源"逐字
   * 比较（{@code StageResponseReader.validateSpan}，PR-01）。此前只有 Stage 1 被告知这
   * 个 UUID，Stage 2 的 prompt 里从不出现它——模型只能编一个，或干脆不写 span。
   */
  @Test
  void stageTwoIsToldTheAnalyzedSourceIdItMustCite() {
    var news = news();
    var prompt = promptFor(news, "");
    var id = news.source().id().toString();

    assertTrue(
      prompt.contains("The analyzed source id is " + id),
      "Stage 2 必须知道要引用的 sourceId，否则 PR-01 只能靠猜"
    );
    assertTrue(
      prompt.contains("Every sourceRefs[].sourceId you emit must be exactly this id"),
      "来源身份必须以唯一合法取值的形式给出"
    );
    assertTrue(
      prompt.contains("set every sourceRefs[].sourceId to " + id),
      "读最后一段也必须钉住同一个 id"
    );
    assertTrue(
      readLast(prompt).contains(id),
      "覆盖性约束里必须出现该 id"
    );
    for (var fact : facts(news)) assertTrue(
      prompt.contains(fact.id().toString()),
      "Stage 2 只可引用 Stage 1 的 fact id: " + fact.id()
    );
  }

  // ---- 2. confidence 不再自相矛盾 -------------------------------------------

  /**
   * prompt.txt 曾笼统地说"每个 item 都带 confidence"，而读最后一段曾说 "never state a
   * confidence score"。前者对 {@code FalsificationCondition}/{@code Signal}/{@code Prediction}/
   * {@code Unknown} 是错的（它们没有该组件），在 {@code FAIL_ON_UNKNOWN_PROPERTIES} 下会把整条
   * 合规回复变成 OUTPUT_INVALID；后者则指引模型省略该字段，被
   * {@code ProtocolModel.advisory(null)} 静默清零。两句话都必须消失。
   */
  @Test
  void thePromptScopesItemConfidenceToTheTypesThatDeclareIt() {
    var prompt = flat(promptFor(news(), ""));

    assertTrue(
      prompt.contains("Only an item type whose schema declares a confidence field carries one"),
      "正文必须把 confidence 限定在声明了它的 item 类型上"
    );
    assertTrue(
      prompt.contains("advisoryConfidence"),
      "hypothesis 的字段名是 advisoryConfidence，不是 confidence"
    );
    assertTrue(
      prompt.contains(
        "Never add a confidence field to an item whose schema does not declare one"
      ),
      "必须显式禁止给没有该字段的 item 加 confidence"
    );
    assertFalse(
      prompt.contains("Each item carries a `confidence` field"),
      "笼统要求会给 Prediction/FalsificationCondition/Signal/Unknown 加上非法字段"
    );
    assertFalse(
      prompt.contains("never state a confidence score"),
      "读最后一段不得再与正文矛盾"
    );
    assertTrue(
      prompt.contains("Item-level `confidence` is an allowed advisory 0-100 diagnostic"),
      "必须明确 item-level confidence 是允许的"
    );
    assertTrue(
      prompt.contains("snapshot-level confidenceAssessment is computed deterministically"),
      "必须明确确定性计算的 confidenceAssessment 不属于模型"
    );
  }

  /**
   * 与上一条互为独立证据：schema 本身说明了哪些 item 类型才有 confidence 组件。
   * 这条锁定"正文的说法与 schema 一致"，而不是只看措辞。
   */
  @SuppressWarnings("unchecked")
  @Test
  void onlyTheItemTypesWhoseSchemaDeclaresConfidenceMayCarryIt() {
    var defs = (Map<String, Object>) JSON.read(service().reasoningReplySchema(), Map.class).get("$defs");
    var withoutConfidence = List.of("FalsificationCondition", "Signal", "Prediction", "Unknown");
    for (var name : withoutConfidence) {
      var properties = (Map<String, Object>) ((Map<String, Object>) defs.get(name)).get("properties");
      assertFalse(
        properties.containsKey("confidence"),
        name + " 没有 confidence 组件，正文不得要求它"
      );
    }
    var hypothesis = (Map<String, Object>) ((Map<String, Object>) defs.get("Hypothesis")).get("properties");
    assertTrue(hypothesis.containsKey("advisoryConfidence"), "Hypothesis 用的是 advisoryConfidence");
    assertFalse(hypothesis.containsKey("confidence"), "Hypothesis 没有名为 confidence 的组件");
    for (var name : List.of("Variable", "Mechanism", "Stakeholder", "Effect", "Alternative", "CounterArgument", "PlanItem")) {
      var properties = (Map<String, Object>) ((Map<String, Object>) defs.get(name)).get("properties");
      assertTrue(properties.containsKey("confidence"), name + " 声明了 confidence");
    }
  }

  /** 某个 item 类型声明的所有 "*Refs" 字段名，按字典序。 */
  @SuppressWarnings("unchecked")
  static Set<String> refFields(Map<String, Object> defs, String name) {
    var properties = (Map<String, Object>) ((Map<String, Object>) defs.get(name)).get("properties");
    var out = new TreeSet<String>();
    for (var key : properties.keySet()) if (key.endsWith("Refs")) out.add(key);
    return out;
  }

  /**
   * 同一类缺陷的第二个实例：正文原本笼统地说"每个基于事实的产物都要给 factRefs 和
   * sourceRefs"。但 {@code Hypothesis} 声明的是 {@code supportingFactRefs} 且没有
   * sourceRefs，{@code Prediction} 声明的是 {@code basisFactRefs} 且没有 sourceRefs，
   * {@code FalsificationCondition}/{@code Signal} 两个都没有，{@code PlanItem}/
   * {@code Unknown} 只有 sourceRefs。在 {@code additionalProperties:false} 下，照正文
   * 字面写出的回复会带上非法字段而整条被拒。本用例把正文措辞与 schema 逐一钉在一起。
   */
  @SuppressWarnings("unchecked")
  @Test
  void thePromptNamesTheReferenceFieldsEachItemTypeActuallyDeclares() {
    var prompt = flat(promptFor(news(), ""));
    var defs = (Map<String, Object>) JSON.read(service().reasoningReplySchema(), Map.class).get("$defs");

    assertEquals(Set.of("factRefs", "sourceRefs"), refFields(defs, "Variable"));
    assertEquals(
      Set.of("supportingFactRefs", "supportingEvidenceRefs", "contradictingEvidenceRefs", "alternativeRefs", "falsificationRefs"),
      refFields(defs, "Hypothesis"),
      "Hypothesis 没有 factRefs，也没有 sourceRefs"
    );
    assertEquals(Set.of("basisFactRefs"), refFields(defs, "Prediction"), "Prediction 没有 sourceRefs");
    assertEquals(Set.of(), refFields(defs, "FalsificationCondition"));
    assertEquals(Set.of(), refFields(defs, "Signal"));
    assertEquals(Set.of("sourceRefs"), refFields(defs, "PlanItem"), "PlanItem 有 span 但没有 fact ref");
    assertEquals(Set.of("sourceRefs"), refFields(defs, "Unknown"));

    assertTrue(
      prompt.contains("supportingFactRefs for a hypothesis"),
      "正文必须点名 Hypothesis 真实的事实引用字段"
    );
    assertTrue(
      prompt.contains("basisFactRefs for a prediction"),
      "正文必须点名 Prediction 真实的事实引用字段"
    );
    assertTrue(
      prompt.contains("Falsification conditions and signals declare neither a fact reference nor a span"),
      "正文必须说明哪些类型两个字段都没有"
    );
    assertFalse(
      prompt.contains("give BOTH the fact id in its factRefs and the exact source span it came from in its sourceRefs"),
      "笼统措辞会给 Hypothesis/Prediction/FalsificationCondition/Signal 加上非法字段"
    );
  }

  // ---- 3. 去重复：读最后一段不再复述 schema ----------------------------------

  @Test
  void theReadLastContractDoesNotRestateTheSchemaArrayList() {
    var contract = readLast(promptFor(news(), ""));

    assertTrue(
      contract.contains("It carries summary plus the 13 arrays the schema above declares"),
      "用指代代替枚举，且仍保留 '一个对象、无 wrapper key' 的框架"
    );
    for (var name : List.of(
      "variables",
      "mechanisms",
      "stakeholders",
      "firstOrderEffects",
      "secondOrderEffects",
      "alternativeExplanations",
      "counterArguments",
      "falsificationConditions",
      "corroboratingSignals",
      "verificationIndicators"
    )) assertFalse(
      contract.contains(name),
      "读最后一段仍在重复 schema 已声明的数组名: " + name
    );
  }

  // ---- 4. 保留全部硬约束 ----------------------------------------------------

  /** 压缩冗余不等于放松约束：这些句子必须逐条仍在。 */
  @Test
  void theShortenedContractKeepsEveryHardConstraint() {
    var contract = readLast(promptFor(news(), ""));

    for (var required : List.of(
      "Every array must be present",
      "explicit UNKNOWN item",
      "Cite only the fact ids listed above",
      "never invent a fact, a source or a span",
      "predictions need expectedBy and verificationCriteria",
      "No wrapper key, no markdown"
    )) assertTrue(
      contract.contains(required),
      "硬约束在优化中被删掉了: " + required
    );
    assertTrue(
      promptFor(news(), "").trim().endsWith("No wrapper key, no markdown."),
      "读最后一段必须仍然是 prompt 的最后内容"
    );
  }

  // ---- 5. 预算约束落在覆盖性位置，且不得成为删除关键内容的许可 ----------------

  @Test
  void theShortSourceBudgetSitsInTheConstraintThatOverridesTheProse() {
    var contract = readLast(promptFor(news(), ""));

    assertTrue(contract.contains("one to two hypotheses"), "缺少假设预算");
    assertTrue(contract.contains("at most one mechanism"), "缺少机制预算");
    assertTrue(contract.contains("at most two predictions"), "缺少预测预算");
    assertTrue(
      contract.contains("with the other arrays empty unless the facts support more"),
      "预算必须允许证据更强时超出，而不是硬上限"
    );
    assertTrue(
      contract.contains("Never drop a counter"),
      "必须显式禁止为了压缩而删除反证"
    );
    assertTrue(
      contract.contains(
        "Never drop a counter argument, an alternative explanation, a falsification condition or a "
          + "prediction's verification criteria that materially affects the conclusion."
      ),
      "保护条款必须完整出现在覆盖性位置"
    );
    // 预算只在读最后一段声明一次：正文与方法说明不重复同一组数字。
    var prompt = promptFor(news(), "");
    assertEquals(
      1,
      prompt.split("one to two hypotheses", -1).length - 1,
      "同一预算要求被重复解释了多次"
    );
    assertEquals(
      1,
      prompt.split("at most one mechanism", -1).length - 1,
      "同一预算要求被重复解释了多次"
    );
  }

  // ---- 6. 规模报告（真实字符数；token 为 ESTIMATED） -------------------------

  /**
   * 规模回归闸门。范围仅限本用例的固定 fixture（86 字符来源 + 3 条 fact），因此
   * <b>不</b>证明"prompt 规模在一般情况下有界"：真正的增长项是 fact 清单与来源正文，
   * 两者都随输入变化。schema 部分由 reader DTO 派生，是固定项。
   */
  @Test
  void theStageTwoPromptSizeIsMeasuredAndBounded() {
    var service = service();
    var news = news();
    var state = state(news);
    var prompt = service.reasoningPromptText(service.reasoningPrompt(), state, "");
    int schema = service.reasoningReplySchema().length();
    int chars = prompt.length();
    int promptText = chars - schema;

    System.out.println(
      "STAGE2_OPT_PROMPT chars=" + chars +
      " schemaChars=" + schema +
      " promptTextChars=" + promptText +
      " ESTIMATED_TOKENS_AT_4_CHARS=" + (chars / 4)
    );

    assertTrue(chars > schema, "prompt 必须包含回复 schema");
    assertTrue(
      promptText < 7_000,
      "prompt 正文（模板 + 约束 + 来源 + fact 清单）不得无界增长（当前 " + promptText + " 字符）"
    );
    assertTrue(
      chars < 18_000,
      "Stage 2 prompt 总量不得无界增长（当前 " + chars + " 字符）"
    );
  }

  // ---- 7. schema 根对象没有被放松 -------------------------------------------

  /**
   * 本用例只锁定它真正断言的东西：回复 schema 的根对象没有被放松。它<b>不</b>证明
   * "schema 逐字未动"——那由 {@code npm run contracts:check}（无 contract 漂移）与
   * {@code git diff}（contracts/ 与生成代码零改动）共同证明；嵌套定义与 DTO 的同源关系
   * 由 {@link Stage2ContractConsistencyTest} 锁定。
   */
  @SuppressWarnings("unchecked")
  @Test
  void theOptimizationDidNotRelaxTheReplySchemaRoot() {
    var schema = JSON.read(service().reasoningReplySchema(), Map.class);

    assertEquals("object", schema.get("type"));
    assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    assertEquals(
      List.of(
        "summary",
        "variables",
        "mechanisms",
        "stakeholders",
        "firstOrderEffects",
        "secondOrderEffects",
        "hypotheses",
        "alternativeExplanations",
        "counterArguments",
        "falsificationConditions",
        "corroboratingSignals",
        "verificationIndicators",
        "predictions",
        "unknowns"
      ),
      schema.get("required"),
      "14 个根字段必须仍全部 required：优化没有靠放松 schema 取得"
    );

    var properties = (Map<String, Object>) schema.get("properties");
    for (var entry : properties.entrySet()) {
      var node = (Map<String, Object>) entry.getValue();
      assertFalse(
        node.containsKey("minItems"),
        entry.getKey() + " 不得凭空获得 minItems 约束"
      );
      assertFalse(
        node.containsKey("maxLength"),
        entry.getKey() + " 的长度上限属于正式 Contract，不在这里"
      );
    }
  }

  // ---- 8. 协议允许的空数组，且缺口必须在 unknowns 里说明 ---------------------

  /**
   * 空数组本身被 reader 接受（PR-05：空数组意为"已评估、无内容"，缺字段才是失败）。
   *
   * <p>但 STG-09 rule 1 要求：输入邀请解释时至少给出一个 hypothesis，否则必须在
   * {@code unknowns} 里说明为什么给不出。因此本用例的 UNKNOWN 显式点名所有空掉的
   * 分析数组与其共同证据缺口——测试名只声明"reader 接受 + 缺口已陈述"，不声明
   * "这条回复在协议上完整"。
   */
  @Test
  void everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts() {
    var news = news();
    var state = state(news);
    var minimal = new TwoStageModel.Reasoning(
      "一句总结。",
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(
        new ProtocolModel.Unknown(
          UUID.randomUUID(),
          "来源未给出可证伪的解释依据，故 variables/mechanisms/hypotheses/predictions 等分析数组为空",
          "公告只陈述协议到期与谈判启动，未给出价格区间、供应商身份或历史基线",
          "原始合同、供应商披露或第二份独立报道",
          List.of()
        )
      )
    );

    var validated = service().validateReasoning(JSON.write(minimal), state);

    assertTrue(validated.hypotheses().isEmpty(), "空数组意为已评估、无内容，不是缺字段");
    assertEquals(1, validated.unknowns().size(), "UNKNOWN 仍是一等输出");
    assertFalse(validated.summary().isBlank());
    var gap = validated.unknowns().getFirst();
    assertTrue(
      gap.statement().contains("hypotheses") && gap.statement().contains("mechanisms"),
      "合并式 UNKNOWN 必须点名它覆盖的那些数组（STG-09 rule 1 + PR-05）"
    );
    assertFalse(gap.unknownReason().isBlank(), "UNKNOWN 必须说明为什么");
    assertFalse(gap.wouldResolveWith().isBlank(), "UNKNOWN 必须说明什么能解决它");
  }

  // ---- 9. SourceRef 正负向（Stage 2 侧） -------------------------------------

  static TwoStageModel.Reasoning reasoningWith(ProtocolModel.Variable variable) {
    return new TwoStageModel.Reasoning(
      "总结",
      List.of(variable),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of()
    );
  }

  static ProtocolModel.Variable variableWith(List<UUID> factRefs, List<SourceRef> sourceRefs) {
    return new ProtocolModel.Variable(
      UUID.randomUUID(),
      "续约价格",
      "UNKNOWN",
      "谈判中",
      ProtocolModel.Direction.UNCHANGED,
      "UNKNOWN",
      "续约价格决定下一季度的采购成本",
      "续约价格已进入谈判",
      "公告如此陈述",
      factRefs,
      sourceRefs,
      50
    );
  }

  /** 上次真实请求正是在 {@code variables[].sourceRefs} 上以 PR-01 失败的产物形状。 */
  @Test
  void aFabricatedSourceRefOnAVariableIsRejectedAsPR01() {
    var news = news();
    var state = state(news);
    var foreign = new SourceRef(
      UUID.randomUUID(),
      news.source().text().substring(0, 20),
      0,
      20
    );
    var reply = reasoningWith(variableWith(List.of(F1), List.of(foreign)));

    var failure = assertThrows(StageOutputException.class, () ->
      service().validateReasoning(JSON.write(reply), state)
    );
    assertTrue(
      failure.getMessage().contains("PR-01"),
      "伪造来源必须按 PR-01 拒绝: " + failure.getMessage()
    );
  }

  @Test
  void aTamperedSpanOnAStageTwoArtifactIsRejected() {
    var news = news();
    var state = state(news);
    // quote 与 0..20 不一致：0..20 的原文不是从下标 1 起的那段
    var tampered = new SourceRef(
      news.source().id(),
      news.source().text().substring(1, 20),
      0,
      20
    );
    var reply = reasoningWith(variableWith(List.of(F1), List.of(tampered)));

    var failure = assertThrows(StageOutputException.class, () ->
      service().validateReasoning(JSON.write(reply), state)
    );
    assertTrue(
      failure.getMessage().contains("PR-07") || failure.getMessage().contains("PR-10"),
      "篡改 offset 必须被 PR-07/PR-10 拒绝: " + failure.getMessage()
    );
  }

  @Test
  void theCorrectSourceIdentityIsAccepted() {
    var news = news();
    var state = state(news);
    var reply = reasoningWith(
      variableWith(List.of(F1), List.of(span(news, 0, 20)))
    );

    var validated = service().validateReasoning(JSON.write(reply), state);

    assertEquals(1, validated.variables().size());
    assertEquals(
      news.source().id(),
      validated.variables().getFirst().sourceRefs().getFirst().sourceId(),
      "正向：来源身份与原文偏移原样通过，未被改写"
    );
    assertEquals(
      0,
      validated.variables().getFirst().sourceRefs().getFirst().startOffset()
    );
    assertEquals(
      20,
      validated.variables().getFirst().sourceRefs().getFirst().endOffset()
    );
  }

  /**
   * 记录一条本轮<b>未修复</b>的边界：reader 分别校验 factRefs 与 sourceRefs，但从不校验
   * "这个 span 属于被引用的那个 fact"。下面这条回复引用 F1、却挂上 F2 的合法 span，
   * 仍然通过。
   *
   * <p>本轮不修改 {@link StageResponseReader}：它是 Stage 1、staged 拓扑与 monolith
   * 共用的校验器，收紧它属于跨路径语义变更，超出"Stage 2 输出优化"的范围，且对
   * Live Probe 那种互相重叠的 fact 集合可能产生误拒。本用例把该限制固定下来，使
   * 后续增量可以据此决定是否收紧（届时本用例应当失败并要求一并更新）。
   */
  @Test
  void theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem() {
    var news = news();
    var state = state(news);
    // F1 的 span 是 0..20；这里故意挂 F2 的合法 span（20..42）
    var reply = reasoningWith(variableWith(List.of(F1), List.of(span(news, 20, 42))));

    var validated = service().validateReasoning(JSON.write(reply), state);

    assertEquals(1, validated.variables().size(), "记录的限制：fact↔span 不做交叉校验");
    assertEquals(news.source().id(), validated.variables().getFirst().sourceRefs().getFirst().sourceId());
    assertEquals(20, validated.variables().getFirst().sourceRefs().getFirst().startOffset());
    assertTrue(
      validated.variables().getFirst().factRefs().contains(F1),
      "引用的仍是 F1，而 span 来自 F2 —— 两者都各自合法，因此都通过"
    );
  }

  // ---- 10. 长字符串与条目数量边界 -------------------------------------------

  /** reader 对条目数量没有上限，对字符串长度没有截断：不得静默丢弃。 */
  @Test
  void aLongStringAndALargeItemCountAreHandledWithoutSilentLoss() {
    var news = news();
    var state = state(news);
    var longStatement = "长".repeat(4_000);
    var variables = new ArrayList<ProtocolModel.Variable>();
    for (int i = 0; i < 40; i++) variables.add(
      new ProtocolModel.Variable(
        UUID.randomUUID(),
        "变量" + i,
        "UNKNOWN",
        "当前值" + i,
        ProtocolModel.Direction.UNCHANGED,
        "UNKNOWN",
        "为什么重要" + i,
        i == 0 ? longStatement : "陈述" + i,
        "理由",
        List.of(F1),
        List.of(span(news, 0, 20)),
        50
      )
    );
    var reply = new TwoStageModel.Reasoning(
      "总结",
      variables,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of()
    );

    var validated = service().validateReasoning(JSON.write(reply), state);

    assertEquals(40, validated.variables().size(), "不得静默丢弃任何条目");
    assertEquals(
      longStatement,
      validated.variables().getFirst().statement(),
      "reader 不做长度截断；上限属于正式 Contract"
    );
  }
}
```

---

## 5. 本轮的三处功能性修复（审查重点）

### 5.1 Stage 2 从未被告知"被分析来源"的 UUID（PR-01）

`state.news().source().id()` 此前**只**出现在 `factsPromptText`（`TwoStageSynthesisService` 的
第 976、983 行）。而 Stage 2 的回复必须携带 `sourceRefs[].sourceId`，
`StageResponseReader.validateSpan` 会把它与 `state.news().source().id()` 逐字比较：

```java
if (!ref.sourceId().equals(state.news().source().id())) throw StageOutputException.schema(
  field + ": sourceId does not match the analyzed source (PR-01)"
);
```

Stage 2 的 prompt 里从未出现这个 UUID。因此模型只能：编一个 UUID（→ PR-01 拒绝），
或省略 `sourceRefs`（→ POC 文档 §14 记录：`SnapshotEnvelope` 的 span-overlap 取证链断裂）。

**修复**：在 fact 清单前给出唯一合法 sourceId，并在读最后一段再次钉住。
请判断：这是否确实是缺陷？修复方式是否恰当？

### 5.2 prompt 措辞与 schema 字段体系不一致（会导致整条 OUTPUT_INVALID）

原文两处笼统措辞：

| 原措辞 | 冲突 |
| --- | --- |
| "Each item carries a `confidence` field" | `FalsificationCondition` / `Signal` / `Prediction` / `Unknown` **没有** confidence 组件；`Hypothesis` 用的是 `advisoryConfidence` |
| "give BOTH the fact id in its `factRefs` and the exact source span in its `sourceRefs`" | `Hypothesis` 用 `supportingFactRefs` 且无 `sourceRefs`；`Prediction` 用 `basisFactRefs` 且无 `sourceRefs`；`PlanItem`/`Unknown` 有 `sourceRefs` 但无 fact ref；`FalsificationCondition`/`Signal` 两者皆无 |

`JsonCodec` 开启了 `FAIL_ON_UNKNOWN_PROPERTIES`，`StageReplySchema` 每个对象都是
`additionalProperties: false`，所以照字面写出的回复会带上非法字段而被整条拒绝。

**修复**：按 item 类型逐一点名，并显式禁止给未声明该字段的类型添加字段。

### 5.3 读最后一段与正文对 `confidence` 自相矛盾

读最后一段曾说 "never state a confidence score"，而它被声明为覆盖前面的正文；
正文要求 item-level `confidence`。结果是模型省略该字段，被
`ProtocolModel.advisory(null)` 静默清零为 0（`SnapshotProjection` 会持久化该值）。

**修复**：删除矛盾句，改为作用域明确的表述。

### 5.4 输出预算条款（Task B，唯一直接针对"压缩输出"的改动）

读最后一段新增：

> Budget for a short or thin source: one to two hypotheses, at most one mechanism and at most
> two predictions, with the other arrays empty unless the facts support more. Never drop a
> counter argument, an alternative explanation, a falsification condition or a prediction's
> verification criteria that materially affects the conclusion.

**请重点判断**：这是否足以避免"为省 token 而丢失关键分析内容"？是否与
STG-09 / STG-10 / STG-12 / STG-14 冲突？

---

## 6. 契约与字段摘要

Stage 2 窄契约 = `TwoStageModel.Reasoning`：`summary` + 13 个数组。
窄路径下 `null` 是硬失败，`[]` 合法（`TwoStageSynthesisService.validateNarrowReasoning`）。

各 item 类型**实际声明**的引用/评分字段：

| 类型 | 事实引用字段 | sourceRefs | confidence |
| --- | --- | --- | --- |
| Variable / Mechanism / Stakeholder / Effect / Alternative / CounterArgument | `factRefs` | 有 | `confidence` |
| Hypothesis | `supportingFactRefs`（另有 supporting/contradictingEvidenceRefs、alternativeRefs、falsificationRefs） | **无** | `advisoryConfidence` |
| Prediction | `basisFactRefs` | **无** | **无** |
| PlanItem | **无** | 有 | `confidence` |
| Unknown | **无** | 有 | **无** |
| FalsificationCondition / Signal | **无** | **无** | **无** |

其他关键事实：

- `StageReplySchema` 把每个 record 组件都列为 `required`，且不生成
  `maxLength`/`minItems`。它是"给模型看的形状"，不是校验闸门。
- OpenAPI 中 Stage 2 相关字符串字段一律 `maxLength: 20000`（44 处），
  `minItems` **0 处**（任何数组都允许为空）。
- `AnalysisResultValidator` **不**拒绝空 `hypotheses`；致命点在 pipeline 的
  `AbstractProtocolStage.requireHypotheses`（`EP-02`）。
- `EpistemicGateStage` 会在缺少替代解释 / 证伪条件时**丢弃该 hypothesis**
  （`STG-09.2` / `STG-12.3`）。
- `StageResponseReader` 的 `requireSourceRefs` 允许空列表；空 `sourceRefs` 不会报错。

---

## 7. 测试证据（全部为真实执行结果）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| Contract 漂移 | `npm run contracts:check` | **PASS** |
| 后端全量 | `./scripts/mvn.sh -B -f apps/api/pom.xml test` | **PASS — 649 tests, 0 failures, 0 errors, 8 skipped** |
| 新增用例 | `-Dtest=Stage2OutputOptimizationTest` | **PASS — 15/15** |
| 前端 lint / typecheck / test / build | `npm run lint` / `typecheck` / `test` / `build` | 全部 PASS（Vitest 26 tests） |
| 空白卫生 | `git diff --check` | PASS |

8 个 SKIPPED **全部**是 opt-in 真实 provider 测试（`RealModelEndToEndTest` 5、
`Stage1LiveProbeTest` 1、`Stage2LiveProbeTest` 1、`Stage2MinimalOutputDiagnosticTest` 1），
本轮未授权、未执行，**不计为 PASS**。

**未执行**：`npm run smoke`（需要 API + 隔离 PostgreSQL + 生产构建同时在线；
本轮未改动被 smoke 覆盖的任何行为）。

### 7.1 规模实测（字符数为实测，token 为 ESTIMATED）

固定 fixture：86 字符中文来源 + 3 条 fact，无 domain guidance。

| 组成 | v3 | v4 | Δ |
| --- | --- | --- | --- |
| prompt.txt | 3 026 | 3 921 | +895 |
| 读最后一段（Java 拼接） | 682 | 1 283 | +601（内含 −248 的数组枚举删除） |
| fact 清单表头 | —— | —— | +35 |
| sourceId 行 | 0 | 163 | +163 |
| 回复 schema（由 DTO 派生） | 9 965 | 9 965 | 0 |
| **合计** | **≈14 221** | **15 915** | **+1 694（+11.91%）** |
| **ESTIMATED prompt tokens** | ≈3 555 | ≈3 978 | **≈ +423** |

**可见输出的实际缩减：UNKNOWN。** 本轮未发真实请求，无法测量；也不得从未保留的
历史响应正文反推。上一次 LIVE 响应的 output tokens 为 14928/16384（实测），
但其 reasoning / 可见 JSON 拆分未知。

---

## 8. Codex 审查结果（两轮，均为真实 `subagent_codex` 调用）

- provider：`codex-review`，`CODEX_HOME=/Users/wumj/.codex-isolated/codex-review`
- 沙箱：日志记录 `sandbox_policy=ReadOnly { network_access: false }`、`approval_policy=Never`
- **实际模型：`gpt-5.6-sol`**（由 `logs_2.sqlite` 的 `model=gpt-5.6-sol` 直接证明；
  子代理自述为 `gpt-5`，与日志不符，此处以日志为准）
- 第 1 轮：`FAIL` — P0=0，P1=2，P2=2，P3=0
- 第 2 轮（增量，额度已用尽）：`FAIL` — P0=0，P1=1，P2=1，P3=0

### 8.1 第 1 轮发现

| # | Sev | 轴 | 问题 | 处置 |
| --- | --- | --- | --- | --- |
| F1 | P1 | R1 | 空数组用例声称"协议有效"，但其 UNKNOWN 未解释分析数组为何为空（STG-09 rule 1） | 已修复 |
| F2 | P1 | R3 | reader 分别校验 factRefs 与 sourceRefs，不做 fact↔span 交叉校验 | **部分修复**（prompt 收紧 + 固定为已知边界；reader 刻意未改） |
| F3 | P2 | R4 | "Each item carries a `confidence` field" 与多种 item 的 schema 冲突 | 已修复 |
| F4 | P2 | R5 | 测试名/注释声称"schema 与 contract 逐字未动"，实则只断言根对象子集 | 已修复 |

### 8.2 第 2 轮复查

| # | Sev | 结论 |
| --- | --- | --- |
| F1 | P1 | **RESOLVED** |
| F2 | P1 | **PARTIALLY RESOLVED**（按设计） |
| F3 | P2 | **RESOLVED** |
| F4 | P2 | **RESOLVED** |
| F5（新） | P2 | **新发现**：§5.2 的引用字段笼统措辞（Codex 注明该不一致先于本轮修复存在） |

### 8.3 Codex 原文（第 2 轮关键结论，英文原文保留）

> R1 — RESOLVED. The test is now accurately named ... Its UNKNOWN identifies the shared evidence
> gap and names representative empty arrays ... The comment explicitly limits the claim to reader
> acceptance rather than full protocol completeness.
>
> R3 — PARTIALLY RESOLVED. The prompt now explicitly prohibits attaching another fact's span ...
> However, the actual integrity defect remains: `requireRefs` only resolves IDs and
> `requireSourceRefs` only validates spans independently.
>
> R4 — RESOLVED. Both prompt locations now scope confidence to item types whose schema declares it.
>
> R5 — RESOLVED. The test and documentation now make the narrower claim ...
>
> P2 — prompt.txt:4: the blanket instruction says every fact-based artifact must emit `factRefs`
> and `sourceRefs`. Hypothesis is necessarily fact-based but its schema declares
> `supportingFactRefs` and no `sourceRefs`. Prediction similarly uses `basisFactRefs` and has no
> `sourceRefs`. A model following the prose literally can therefore emit forbidden fields under
> `additionalProperties:false`. ... This inconsistency predates the reviewed fixes; it was not
> newly introduced by them.

---

## 9. 修复记录（按轮次）

第 1 轮后（第 2 轮已复查）：

1. F1：用例改名 `everyArrayEmptyIsAcceptedByTheReaderAndTheStatedGapNamesTheArtifacts`，
   UNKNOWN 改为点名所有空掉的分析数组，并新增 3 条断言。
2. F2（prompt 层）：新增 "for each fact id you cite give that fact's own span ... never attach a
   span that belongs to a different fact."；新增特性化用例固定 reader 的现有边界。
3. F3：两处 confidence 措辞改为按 item 类型限定；新增 2 个用例（其中一个直接对 `$defs` 断言 12 个类型）。
4. F4：用例改名 `theOptimizationDidNotRelaxTheReplySchemaRoot`，注明 Contract 不变性由
   `contracts:check` 与 `git diff` 证明。

第 2 轮后（**未被任何 Codex 轮次审查**，请重点看）：

5. F5：§5.2 的引用字段措辞改为逐类型点名；新增
   `thePromptNamesTheReferenceFieldsEachItemTypeActuallyDeclares`（对 `$defs` 逐一断言 `*Refs` 字段）。
6. 删除正文与读最后一段对"数组必须存在 / 空数组语义"的重复陈述。
7. 规模闸门改为同时断言 `promptText < 7000` 与 `chars < 18000`，并注明只对固定 fixture 有效。

---

## 10. 未解决问题与审查重点

### P1（未处理）—— reader 不做 fact↔span 交叉校验

- 位置：`StageResponseReader.requireRefs`（约 1215 行）、`requireSourceRefs`（约 1227 行）。
- 现象：一条 Stage 2 产物引用 `F1`、却挂上 `F2` 的合法 span，会通过全部校验。
- 已做：prompt 明确禁止该行为；新增特性化用例
  `theReaderChecksFactIdsAndSpansIndependentlyAndDoesNotCrossCheckThem` 把限制固定下来。
- **未做（需人工决定）**：修改 reader。理由：该 reader 被 Stage 1、staged 拓扑与 monolith
  共用；且 Live Probe fixture 的 fact 互相重叠（3/4 与 5），严格相等可能误拒合法输出。
  这是一个跨路径语义变更，超出"Stage 2 输出优化"范围。
- **请审查者判断**：这应当在本轮修吗？还是另开一轮？如果修，规则应该是什么
  （"每个 span 至少属于一个被引用的 fact"？还是"span 必须精确等于某个被引用 fact 的 span"）？

### P2 —— 本轮 prompt 增大 11.9%

唯一压缩项是 248 字符的 schema 枚举复述；其余增量是功能性修复。
请判断这个取舍是否可接受。

### P2 —— 可见输出缩减未被测量

请判断：在不发真实请求的前提下，是否还有别的可验证手段？

### P3 观察

- Stage 2 prompt 规模闸门只对固定 fixture 有效。
- domain guidance 仍可占 2000 字符（`DomainStrategySpecRenderer` 同时供 monolith 使用）。
- reasoning tokens 未持久化（`ModelRun` 冻结，既有缺口）。
- 历史 LIVE 响应正文未保留（既有缺口）。

---

## 11. 审查时必须遵守的约束（本轮任务的硬性边界）

以下均**不得**作为修复建议被接受，除非先获得用户批准：

1. 修改 `contracts/openapi.yaml` 或任何公共契约；
2. 修改 Analysis Protocol 的语义、Epistemic Types 或已冻结的 `UNKNOWN` 语义；
3. 放宽 `StageReplySchema` 的严格程度，或放宽 `StageResponseReader` 的校验
   （任务书明令："禁止通过降低 Schema 严格程度获得成功"）；
4. 修改 `ConfidenceRubric`；
5. 新增数据库迁移；
6. 更改生产默认 Model Profile；
7. 发起真实 OpenCode Go / provider 请求（本轮禁止）；
8. 大规模架构重构、merge 到 main、push、创建 tag。

欢迎指出上列约束之外的任何真实缺陷。

---

## 12. 附：操作备注（透明性）

审查期间发生过一次环境问题，记录如下，以免被误解为测试结果：在更正一次已作废的
后台测试运行后，遗留的 17 个 Testcontainers PostgreSQL 容器使 Docker 饱和，导致一次
全量测试卡在 Flyway/HikariPool 连接。已停止该运行、仅删除带
`org.testcontainers=true` 标签的容器（**未**触碰既有的 `signalframe-postgres-1`），
清理后重跑得到上表 649/0/0/8 的结果。该事件不影响任何代码结论。
