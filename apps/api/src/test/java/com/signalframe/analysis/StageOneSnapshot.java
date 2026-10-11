package com.signalframe.analysis;

import com.signalframe.analysis.application.steps.PipelineState;
import com.signalframe.analysis.application.steps.ProtocolModel;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.NewsItem;
import com.signalframe.contract.SourceRef;
import java.time.Instant;
import java.util.*;

/**
 * Stage 1 已通过的真实输出快照，作为 Stage 2 的固定输入。
 *
 * <p>数据取自 2026-10-11 那次 PASS 的 Stage 1 Live Probe：原文、5 条 fact 的真实
 * ID、sourceId 与 UTF-16 offsets、2 条 unknown。quote 由 {@code substring(start,end)}
 * 派生，因此与原文逐字符一致；Stage 1 <b>不会</b>被重新运行。
 */
final class StageOneSnapshot {

  private StageOneSnapshot() {}

  /** Stage 1 实际分析的来源 ID。 */
  static final UUID SOURCE_ID = UUID.fromString("f4cec442-39e3-42c1-9539-32eb94fd5313");

  static final String TEXT =
    "【测试数据·非真实新闻】某公司于本周二发布公告称，其与一家供应商签署的零部件采购协议将于本季度到期，" +
    "双方已就续约价格启动谈判。公告同时提到，现有协议覆盖三种规格的零部件，年采购金额约为两亿元。";

  static final UUID[] FACT_IDS = {
    UUID.fromString("b1a7f3c2-5d94-4e18-9a2b-7c0e6f1d3a41"),
    UUID.fromString("c2b8e4d3-6ea5-4f29-8b3c-8d1f702e4b52"),
    UUID.fromString("d3c9f5e4-7fb6-4a3a-9c4d-9e20813f5c63"),
    UUID.fromString("e4d0a6f5-8ac7-4b4b-ad5e-af31924a6d74"),
    UUID.fromString("f5e1b706-9bd8-4c5c-be6f-b042035b7e85"),
  };

  /** 真实的 UTF-16 区间，按 Stage 1 返回顺序。 */
  static final int[][] SPANS = {
    { 12, 49 }, { 50, 62 }, { 70, 84 }, { 85, 95 }, { 63, 96 },
  };

  static final String[] UNKNOWNS = {
    "公告未给出具体的发布日期",
    "供应商身份未在公告中披露",
  };

  static String quote(int index) {
    return TEXT.substring(SPANS[index][0], SPANS[index][1]);
  }

  /** 5 条 fact，保留真实 ID、sourceId、quote 与 span。 */
  static List<ProtocolModel.Fact> facts() {
    var facts = new ArrayList<ProtocolModel.Fact>();
    for (int i = 0; i < FACT_IDS.length; i++) {
      var quote = quote(i);
      facts.add(
        new ProtocolModel.Fact(
          FACT_IDS[i],
          quote,
          "来源如此陈述",
          List.of(
            new SourceRef(SOURCE_ID, quote, SPANS[i][0], SPANS[i][1])
          ),
          ProtocolModel.ProofStatus.REPORTED
        )
      );
    }
    return List.copyOf(facts);
  }

  static List<ProtocolModel.Unknown> unknowns() {
    var list = new ArrayList<ProtocolModel.Unknown>();
    for (var statement : UNKNOWNS) list.add(
      new ProtocolModel.Unknown(null, statement, "来源未说明", "出版方的原始公告", List.of())
    );
    return List.copyOf(list);
  }

  /** Stage 2 的输入状态：已有的 facts 与 unknowns，外加一条真实来源。 */
  static PipelineState state(UUID jobId, NewsItem news) {
    return PipelineState.initial(jobId, "stage2-live-probe", news, Instant.now())
      .withFacts(facts())
      .withUnknowns(unknowns());
  }

  /** 离线演练用的内存来源，原文与快照一致。 */
  static NewsItem rehearsalNews() {
    return PipelineFixtures.news(TEXT);
  }
}
