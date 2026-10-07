"use client";
import Link from "next/link";
import type { Schema } from "@/lib/api";
function Claims({ items }: { items: Schema<"Statement">[] }) {
  return items.length ? (
    <ul className="claims">
      {items.map((x, i) => (
        <li key={i}>
          <span className={`tag ${x.type.toLowerCase()}`}>{x.type}</span>
          <strong>{x.statement}</strong>
          <p>{x.reasoning}</p>
          <small>判断尺度 {x.confidence}/100</small>
          {x.sourceRefs.map((s, j) => (
            <details key={j}>
              <summary>
                原文依据 · {s.startOffset}–{s.endOffset}
              </summary>
              <blockquote>{s.quote}</blockquote>
              <small>Source {s.sourceId}</small>
            </details>
          ))}
        </li>
      ))}
    </ul>
  ) : (
    <p className="muted">当前尚未形成该项判断。</p>
  );
}
export function AnalysisView({
  analysis,
  runs = [],
}: {
  analysis: Schema<"Analysis">;
  runs?: Schema<"ModelRun">[];
}) {
  const r = analysis.result;
  const groups: [string, Schema<"Statement">[]][] = [
    ["Facts · 来源事实", r.facts],
    ["Key Variables · 核心变量", r.variables],
    ["Causal Chain · 因果链", r.mechanisms],
    ["Stakeholders · 利益相关者", r.stakeholders],
    ["First Order · 一级影响", r.firstOrderEffects],
    ["Second Order · 二级影响", r.secondOrderEffects],
    ["Alternative Explanations · 替代解释", r.alternativeExplanations],
    ["Counter Arguments · 反方论证", r.counterArguments],
    ["Falsification · 可证伪条件", r.falsificationConditions],
    ["Corroborating Signals · 旁证", r.corroboratingSignals],
    ["Verification Plan · 验证计划", r.verificationIndicators],
    ["Unknowns · 未知事项", r.unknowns],
    ["Next Observations · 后续观察", r.upcomingObservations],
  ];
  return (
    <section aria-label="分析结果">
      <div className="notice">
        {r.demo
          ? "Mock 演示结果 · 未经独立核实"
          : "模型生成分析 · 请核查原始证据"}
        。置信度是判断尺度，不是统计概率。
      </div>
      <div className="panel summary">
        <div className="eyebrow">
          SUMMARY{" "}
          <span className="tag">
            {analysis.score.grade} · {analysis.domain}
          </span>
        </div>
        <h2>{r.summary}</h2>
        <p>Why It Matters · {analysis.score.reason}</p>
        <p>
          当前置信度 <strong>{r.confidenceAssessment.score}/100</strong> ·{" "}
          {r.confidenceAssessment.reason}
        </p>
        <small>
          是否修改已有假设：
          {r.modifiesExistingHypotheses ? "是" : "本轮尚未关联既有假设"}
        </small>
      </div>
      <div className="analysis-grid">
        {groups.map(([title, items]) => (
          <section className="panel" key={title}>
            <h3>{title}</h3>
            <Claims items={items} />
          </section>
        ))}
        <section className="panel">
          <h3>Hypotheses · 主要假设</h3>
          {r.hypotheses.map((h) => (
            <div key={h.id}>
              <Link className="item-link" href={`/hypotheses/${h.id}`}>
                {h.title} →
              </Link>
              <Claims items={[h]} />
            </div>
          ))}
        </section>
        <section className="panel">
          <h3>Model Runs · 调用审计</h3>
          {runs.length ? (
            runs.map((m) => (
              <div key={m.id} className="audit">
                <strong>
                  {m.provider} / {m.model}
                </strong>
                <p>
                  {m.purpose} · {m.promptVersion} · {m.status}
                </p>
                <small>
                  {m.totalTokens ?? "未知"} tokens · {m.latencyMs} ms · cost{" "}
                  {m.estimatedCost ?? "未估算"}
                </small>
              </div>
            ))
          ) : (
            <p className="muted">暂无调用记录。</p>
          )}
        </section>
      </div>
    </section>
  );
}
