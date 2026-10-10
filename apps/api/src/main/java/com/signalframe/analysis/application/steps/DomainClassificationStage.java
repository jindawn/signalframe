package com.signalframe.analysis.application.steps;

import com.signalframe.contract.DomainType;
import com.signalframe.contract.JobStatus;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pipeline step `ClassifyDomain` (protocol STG mapping: context only).
 *
 * <p>Deterministic and model-free: it sets {@code DomainType} so the strategy
 * layer can recommend variables and verification metrics. It produces no
 * epistemic artifact, and it assigns no confidence (protocol DS-01: strategies
 * and domain context never move the protocol's semantics).
 *
 * <p>The rule is a fixed, ordered keyword table — never a model call, a clock, a
 * locale-dependent sort or a random choice — so identical input always yields the
 * same {@link DomainType} (PR-14), and the domain strategy that is selected from
 * it is reproducible. The first rule that matches wins, so exactly one domain is
 * produced and exactly one strategy can be selected from it (DS-04).
 *
 * <p><b>Scope note.</b> This is the deterministic placeholder the foundation has
 * always used, widened so that every dictionary TASK-05 ships is reachable from
 * input text; it is not a classifier with measured quality. ASCII keywords are
 * matched on word boundaries so that {@code ai} no longer matches "said" or
 * "email", and CJK keywords are matched as substrings because those scripts have
 * no word boundaries. Input that matches no rule is {@link DomainType#OTHER} and
 * resolves to the fallback strategy, which is the honest answer (DS-05) rather
 * than a guess.
 */
@Component
public class DomainClassificationStage implements ProtocolStage {

  static final String NAME = "DomainClassification";
  static final String STEP = "ClassifyDomain";

  /** One ordered rule: the domain it claims and the keywords that claim it. */
  private record Rule(DomainType domain, List<Pattern> keywords) {}

  /**
   * Fixed evaluation order. A more specific subject is listed before a broader
   * one so that, for example, a chip story mentioning revenue is TECH rather
   * than BUSINESS. The order is part of the rule, not an accident of iteration.
   */
  private static final List<Rule> RULES = List.of(
    rule(
      DomainType.AI,
      "ai",
      "artificial intelligence",
      "machine learning",
      "deep learning",
      "neural network",
      "large language model",
      "llm",
      "generative",
      "foundation model",
      "人工智能",
      "大模型",
      "机器学习",
      "深度学习",
      "神经网络",
      "生成式"
    ),
    rule(
      DomainType.HEALTHCARE,
      "healthcare",
      "pharmaceutical",
      "clinical trial",
      "fda",
      "drug approval",
      "vaccine",
      "patient",
      "医疗",
      "医药",
      "药物",
      "临床",
      "患者",
      "疫苗",
      "医院"
    ),
    rule(
      DomainType.ENERGY,
      "energy",
      "oil price",
      "natural gas",
      "electricity",
      "power grid",
      "renewable",
      "solar",
      "wind power",
      "battery",
      "能源",
      "石油",
      "天然气",
      "电力",
      "电网",
      "光伏",
      "储能",
      "电池"
    ),
    rule(
      DomainType.REAL_ESTATE,
      "real estate",
      "housing",
      "mortgage",
      "property price",
      "rent",
      "vacancy",
      "房地产",
      "房价",
      "楼市",
      "租金",
      "抵押",
      "土地"
    ),
    rule(
      DomainType.GEOPOLITICS,
      "geopolitic",
      "alliance",
      "treaty",
      "nato",
      "military",
      "war",
      "sanction",
      "diplomacy",
      "地缘",
      "联盟",
      "条约",
      "军事",
      "战争",
      "外交",
      "冲突"
    ),
    rule(
      DomainType.POLICY,
      "regulation",
      "regulator",
      "legislation",
      "statute",
      "bill",
      "compliance",
      "tariff",
      "rulemaking",
      "监管",
      "法规",
      "立法",
      "政策",
      "合规",
      "关税",
      "法案",
      "条例"
    ),
    rule(
      DomainType.FINANCE,
      "stock",
      "share price",
      "market cap",
      "valuation",
      "bond",
      "credit spread",
      "interest rate",
      "central bank",
      "earnings per share",
      "股价",
      "市值",
      "估值",
      "债券",
      "利率",
      "央行",
      "信贷",
      "融资",
      "股市",
      "基金"
    ),
    rule(
      DomainType.MACRO,
      "gdp",
      "inflation",
      "cpi",
      "pmi",
      "monetary policy",
      "fiscal",
      "trade deficit",
      "通胀",
      "通货膨胀",
      "宏观经济",
      "财政",
      "货币政策",
      "经济增速",
      "进出口"
    ),
    rule(
      DomainType.EMPLOYMENT,
      "employment",
      "hiring",
      "layoff",
      "wage",
      "labor force",
      "workforce",
      "job openings",
      "就业",
      "招聘",
      "工资",
      "劳动力",
      "失业",
      "裁员"
    ),
    rule(
      DomainType.CONSUMER,
      "consumer",
      "retail sales",
      "e-commerce",
      "brand",
      "spending",
      "消费",
      "零售",
      "电商",
      "品牌",
      "购买"
    ),
    rule(
      DomainType.TECH,
      "semiconductor",
      "chip",
      "gpu",
      "cloud",
      "software",
      "hardware",
      "open source",
      "data center",
      "firmware",
      "芯片",
      "半导体",
      "云计算",
      "软件",
      "开源",
      "数据中心",
      "算力",
      "硬件",
      "操作系统"
    ),
    rule(
      DomainType.BUSINESS,
      "revenue",
      "profit",
      "margin",
      "acquisition",
      "merger",
      "restructuring",
      "company",
      "enterprise",
      "营收",
      "收入",
      "利润",
      "毛利",
      "公司",
      "并购",
      "收购",
      "企业"
    )
  );

  @Override
  public PipelineState execute(PipelineState in) {
    return in
      .withDomain(classify(in.news().source().text(), in.news().title()))
      .executed(NAME, null);
  }

  /**
   * Pure, deterministic keyword rule. It is shared with the draft stage so that
   * the prompt guidance and the recorded {@code DomainType} always agree.
   */
  static DomainType classify(String sourceText, String title) {
    String text = normalize(sourceText) + " " + normalize(title);
    for (var rule : RULES) {
      for (var keyword : rule.keywords()) {
        if (keyword.matcher(text).find()) return rule.domain();
      }
    }
    return DomainType.OTHER;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private static Rule rule(DomainType domain, String... keywords) {
    return new Rule(
      domain,
      List.of(keywords).stream().map(DomainClassificationStage::pattern).toList()
    );
  }

  /**
   * ASCII keywords match on word boundaries; non-ASCII keywords match as
   * substrings. Lower-casing happens before matching, so the pattern is built
   * from the already-lowered literal.
   */
  private static Pattern pattern(String keyword) {
    boolean ascii = keyword.chars().allMatch(c -> c < 128);
    String literal = Pattern.quote(keyword.toLowerCase(Locale.ROOT));
    return Pattern.compile(ascii ? "\\b" + literal + "\\b" : literal);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.ANALYZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
