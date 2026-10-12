package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.NewsItem;
import com.signalframe.contract.SourceRef;
import com.signalframe.shared.JsonCodec;
import com.signalframe.shared.confidence.ConfidenceRubric;
import java.nio.file.Files;
import java.nio.file.Path;
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

  /** 根对象里某个数组的元素类型名（取自 {@code items.$ref}）。 */
  @SuppressWarnings("unchecked")
  static String itemTypeOf(Map<String, Object> schema, String array) {
    var root = (Map<String, Object>) schema.get("properties");
    var prop = (Map<String, Object>) root.get(array);
    assertNotNull(prop, "根对象缺少数组 " + array);
    var items = (Map<String, Object>) prop.get("items");
    var ref = String.valueOf(items.get("$ref"));
    return ref.substring(ref.lastIndexOf('/') + 1);
  }

  /** 某个 item 类型在 {@code required} 里声明的 "*Refs" 字段名。 */
  @SuppressWarnings("unchecked")
  static Set<String> requiredRefFields(Map<String, Object> defs, String name) {
    var req = (List<Object>) ((Map<String, Object>) defs.get(name)).get("required");
    var out = new TreeSet<String>();
    if (req != null) for (var r : req) {
      var s = String.valueOf(r);
      if (s.endsWith("Refs")) out.add(s);
    }
    return out;
  }

  /** 根对象引用的全部元素类型名。 */
  @SuppressWarnings("unchecked")
  static Set<String> replyItemTypes(Map<String, Object> schema) {
    var root = (Map<String, Object>) schema.get("properties");
    var out = new TreeSet<String>();
    for (var key : root.keySet()) {
      var prop = (Map<String, Object>) root.get(key);
      if ("array".equals(prop.get("type"))) out.add(itemTypeOf(schema, key));
    }
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

  /**
   * 独立复核指出原用例的覆盖不足：只钉了 7 个类型，漏了 {@code Mechanism}/
   * {@code Stakeholder}/{@code Effect}/{@code Alternative}/{@code CounterArgument}，
   * 也没有钉"数组 → 元素类型"这一层——而 {@code verificationIndicators} 的元素是
   * {@code PlanItem}，因此它必须有 span，正文却只提"plan items"。
   * 本用例把两层都钉住，并用"元素类型集合"做完整性护栏：新增类型时它会失败。
   */
  @SuppressWarnings("unchecked")
  @Test
  void everyReplyArrayMapsToTheItemTypeWhoseReferenceFieldsItDeclares() {
    var schema = (Map<String, Object>) JSON.read(service().reasoningReplySchema(), Map.class);
    var defs = (Map<String, Object>) schema.get("$defs");

    var arrays = new LinkedHashMap<String, String>();
    arrays.put("variables", "Variable");
    arrays.put("mechanisms", "Mechanism");
    arrays.put("stakeholders", "Stakeholder");
    arrays.put("firstOrderEffects", "Effect");
    arrays.put("secondOrderEffects", "Effect");
    arrays.put("hypotheses", "Hypothesis");
    arrays.put("alternativeExplanations", "Alternative");
    arrays.put("counterArguments", "CounterArgument");
    arrays.put("falsificationConditions", "FalsificationCondition");
    arrays.put("corroboratingSignals", "Signal");
    arrays.put("verificationIndicators", "PlanItem");
    arrays.put("predictions", "Prediction");
    arrays.put("unknowns", "Unknown");
    for (var e : arrays.entrySet()) {
      assertEquals(e.getValue(), itemTypeOf(schema, e.getKey()), e.getKey() + " 的元素类型");
    }

    assertEquals(Set.of("factRefs", "sourceRefs"), refFields(defs, "Variable"));
    assertEquals(Set.of("factRefs", "sourceRefs"), refFields(defs, "Mechanism"));
    assertEquals(Set.of("factRefs", "sourceRefs"), refFields(defs, "Stakeholder"));
    assertEquals(
      Set.of("derivedFromRefs", "factRefs", "sourceRefs"),
      refFields(defs, "Effect"),
      "Effect 还有 derivedFromRefs（指向一阶效应，STG-08），它不是事实引用"
    );
    assertEquals(Set.of("factRefs", "sourceRefs"), refFields(defs, "Alternative"));
    assertEquals(Set.of("factRefs", "sourceRefs"), refFields(defs, "CounterArgument"));
    assertEquals(
      Set.of("supportingFactRefs", "supportingEvidenceRefs", "contradictingEvidenceRefs", "alternativeRefs", "falsificationRefs"),
      refFields(defs, "Hypothesis")
    );
    assertEquals(Set.of("basisFactRefs"), refFields(defs, "Prediction"));
    assertEquals(Set.of("sourceRefs"), refFields(defs, "PlanItem"));
    assertEquals(Set.of("sourceRefs"), refFields(defs, "Unknown"));
    assertEquals(Set.of(), refFields(defs, "FalsificationCondition"));
    assertEquals(Set.of(), refFields(defs, "Signal"));

    // 这些引用字段在 schema 里都是 required：漏写即整条被拒
    assertEquals(Set.of("factRefs", "sourceRefs"), requiredRefFields(defs, "Variable"));
    assertEquals(Set.of("derivedFromRefs", "factRefs", "sourceRefs"), requiredRefFields(defs, "Effect"));
    assertEquals(Set.of("basisFactRefs"), requiredRefFields(defs, "Prediction"));
    assertEquals(Set.of("sourceRefs"), requiredRefFields(defs, "PlanItem"));

    assertEquals(
      Set.of(
        "Variable", "Mechanism", "Stakeholder", "Effect", "Hypothesis", "Alternative",
        "CounterArgument", "FalsificationCondition", "Signal", "PlanItem", "Prediction", "Unknown"
      ),
      replyItemTypes(schema),
      "根对象引用的元素类型集合必须与本用例断言过的集合一致（新增类型时本用例应失败）"
    );
  }

  /**
   * Q1 的回归断言：正文**不得**对所有事实引用类型无条件要求 span。
   * 旧措辞"for each fact id you cite give that fact's own span"会让模型给
   * {@code Hypothesis}/{@code Prediction} 补一个它们没有声明的 {@code sourceRefs}，
   * 在 {@code additionalProperties:false} 下整条回复被拒。
   */
  @SuppressWarnings("unchecked")
  @Test
  void thePromptScopesTheSourceSpanToTheTypesThatDeclareSourceRefs() {
    var schema = (Map<String, Object>) JSON.read(service().reasoningReplySchema(), Map.class);
    var defs = (Map<String, Object>) schema.get("$defs");
    var prompt = flat(promptFor(news(), ""));

    var withSpan = new TreeSet<String>();
    var withoutSpan = new TreeSet<String>();
    for (var name : defs.keySet()) {
      if (name.equals("Reasoning") || name.equals("SourceRef")) continue;
      var props = (Map<String, Object>) ((Map<String, Object>) defs.get(name)).get("properties");
      if (props == null) continue;
      if (props.containsKey("sourceRefs")) withSpan.add(name); else withoutSpan.add(name);
    }
    assertEquals(
      Set.of("Variable", "Mechanism", "Stakeholder", "Effect", "Alternative", "CounterArgument", "PlanItem", "Unknown"),
      withSpan,
      "声明 sourceRefs 的类型集合"
    );
    assertEquals(
      Set.of("Hypothesis", "Prediction", "FalsificationCondition", "Signal"),
      withoutSpan,
      "不声明 sourceRefs 的类型集合"
    );

    assertTrue(
      prompt.contains("A source span is required only from an item type that declares sourceRefs as well"),
      "span 要求必须是条件式的，只施加于同时声明 sourceRefs 的类型"
    );
    assertTrue(
      prompt.contains("An item type that declares no sourceRefs must not be given one"),
      "必须明确禁止给不声明 sourceRefs 的类型添加 span"
    );
    assertTrue(
      prompt.contains("verification indicators (whose items are plan items)"),
      "verificationIndicators 的元素是 PlanItem，必须被点名，否则模型会漏 span"
    );
    assertTrue(
      prompt.contains("A hypothesis keeps its own reference fields"),
      "Hypothesis 原有的合法引用字段及其语义必须保留"
    );

    for (var banned : List.of(
      "for each fact id you cite give that fact's own span",
      "an item with a fact id but no span loses its evidence link",
      "Where the item type also declares sourceRefs"
    )) {
      assertFalse(prompt.contains(banned), "无条件 span 措辞回归：" + banned);
    }
  }

  /**
   * 把当前窄回复 schema 导出到构建输出目录，供独立复核直接读取。
   * 写在 {@code target/} 下（构建产物，不污染任何被跟踪文件）。
   */
  @SuppressWarnings("unchecked")
  @Test
  void theNarrowReplySchemaIsExportedForIndependentReview() throws Exception {
    var schema = service().reasoningReplySchema();
    var parsed = (Map<String, Object>) JSON.read(schema, Map.class);
    var root = (Map<String, Object>) parsed.get("properties");
    assertEquals(Boolean.FALSE, parsed.get("additionalProperties"), "根对象仍必须拒绝未声明字段");
    assertEquals(14, root.size(), "13 个数组 + summary");
    assertTrue(root.containsKey("summary"), "summary 仍必须存在");

    var out = Path.of("target", "stage2-narrow-reply-schema.json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, schema.endsWith("\n") ? schema : schema + "\n");
    assertTrue(Files.size(out) > 0, "导出的 schema 不得为空");
    assertTrue(Files.readString(out).contains("\"$defs\""), "导出的 schema 必须含 $defs");
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
