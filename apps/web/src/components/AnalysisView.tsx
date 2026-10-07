"use client";
import Link from "next/link";
import type { Schema } from "@/lib/api";
import {
  claimGlyph,
  claimLabel,
  directionArrow,
  directionLabel,
  hypothesisStatusLabel,
  modelRunStatusLabel,
  stakeholderLabel,
  stakeholderOrder,
} from "@/lib/labels";

type Claim = Schema<"Statement">;

/** Non-color cue: glyph + contract enum + Chinese label. */
function ClaimTag({ type }: { type: Schema<"ClaimType"> }) {
  return (
    <span className={`tag claim ${type.toLowerCase()}`}>
      <span aria-hidden="true">{claimGlyph[type]}</span> {type} ·{" "}
      {claimLabel[type]}
    </span>
  );
}

function Confidence({ value, reason }: { value: number; reason?: string }) {
  return (
    <div className="confidence-line">
      <span className="confidence-label">Confidence · 判断尺度</span>
      <strong className="confidence-value">{value}/100</strong>
      {reason ? <span className="confidence-reason">{reason}</span> : null}
    </div>
  );
}

function Sources({ refs }: { refs: Claim["sourceRefs"] }) {
  if (!refs.length)
    return <small className="muted">该判断没有附带原文片段。</small>;
  return (
    <div className="source-refs">
      {refs.map((ref, index) => (
        <details key={`${ref.sourceId}-${index}`}>
          <summary>
            原文依据 · {ref.startOffset}–{ref.endOffset}
          </summary>
          <blockquote>{ref.quote}</blockquote>
          <small>Source {ref.sourceId}</small>
        </details>
      ))}
    </div>
  );
}

function ClaimList({ items, empty }: { items: Claim[]; empty: string }) {
  if (!items.length) return <p className="muted empty">{empty}</p>;
  return (
    <ul className="claims">
      {items.map((item, index) => (
        <li key={index}>
          <ClaimTag type={item.type} />
          <strong className="claim-statement">{item.statement}</strong>
          <p className="claim-reason">{item.reasoning}</p>
          <Confidence value={item.confidence} />
          <Sources refs={item.sourceRefs} />
        </li>
      ))}
    </ul>
  );
}

function Section({
  id,
  title,
  hint,
  tone,
  children,
}: {
  id: string;
  title: string;
  hint: string;
  tone?: "falsification";
  children: React.ReactNode;
}) {
  return (
    <section
      className={`panel section${tone ? ` section-${tone}` : ""}`}
      aria-labelledby={id}
    >
      <div className="section-head">
        <h2 id={id}>{title}</h2>
        <p className="section-hint">{hint}</p>
      </div>
      {children}
    </section>
  );
}

function VariableList({ items }: { items: Schema<"Variable">[] }) {
  if (!items.length)
    return <p className="muted empty">当前尚未识别出关键变量。</p>;
  return (
    <ul className="claims">
      {items.map((item) => (
        <li key={item.id}>
          <div className="var-head">
            <h3>{item.name}</h3>
            <span
              className={`direction direction-${item.direction.toLowerCase()}`}
            >
              <span aria-hidden="true">{directionArrow[item.direction]}</span>{" "}
              {directionLabel[item.direction]}
            </span>
          </div>
          <ClaimTag type={item.type} />
          <strong className="claim-statement">{item.statement}</strong>
          <p className="claim-reason">{item.reasoning}</p>
          <Confidence value={item.confidence} />
          <Sources refs={item.sourceRefs} />
        </li>
      ))}
    </ul>
  );
}

function MechanismList({ items }: { items: Schema<"CausalLink">[] }) {
  if (!items.length)
    return (
      <p className="muted empty">
        当前尚未给出可追踪的因果链；影响机制仍是未知项。
      </p>
    );
  return (
    <ul className="claims">
      {items.map((item) => (
        <li key={item.id}>
          <div className="causal">
            <span className="causal-node">{item.cause}</span>
            <span className="causal-arrow" aria-hidden="true">
              →
            </span>
            <span className="causal-node">{item.effect}</span>
          </div>
          <ClaimTag type={item.type} />
          <strong className="claim-statement">{item.statement}</strong>
          <p className="claim-reason">{item.reasoning}</p>
          <Confidence value={item.confidence} />
          <Sources refs={item.sourceRefs} />
        </li>
      ))}
    </ul>
  );
}

function StakeholderList({ items }: { items: Schema<"StakeholderImpact">[] }) {
  if (!items.length)
    return <p className="muted empty">当前尚未识别出明确的利益相关者。</p>;
  return (
    <div className="stakeholders">
      {stakeholderOrder.map((direction) => {
        const group = items.filter((item) => item.direction === direction);
        if (!group.length) return null;
        return (
          <div className="stakeholder-group" key={direction}>
            <h3>
              {stakeholderLabel[direction]}{" "}
              <small className="muted">({direction})</small>
            </h3>
            <ul className="claims">
              {group.map((item) => (
                <li key={item.id}>
                  <strong className="claim-statement">{item.stakeholder}</strong>
                  <ClaimTag type={item.type} />
                  <p className="claim-reason">{item.statement}</p>
                  <p className="claim-reason muted">{item.reasoning}</p>
                  <Confidence value={item.confidence} />
                  <Sources refs={item.sourceRefs} />
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}

function HypothesisList({ items }: { items: Schema<"Hypothesis">[] }) {
  if (!items.length)
    return <p className="muted empty">当前尚未形成可检验的假设。</p>;
  return (
    <ul className="claims hypotheses">
      {items.map((item) => (
        <li key={item.id}>
          <h3 className="hypothesis-title">
            <Link className="item-link" href={`/hypotheses/${item.id}`}>
              {item.title} →
            </Link>
          </h3>
          <div className="tag-row">
            <ClaimTag type={item.type} />
            <span className="tag">
              {hypothesisStatusLabel[item.status]} · {item.status}
            </span>
          </div>
          <strong className="claim-statement">{item.statement}</strong>
          <p className="claim-reason">{item.description}</p>
          <p className="claim-reason muted">{item.reasoning}</p>
          <Confidence value={item.confidence} reason={item.confidenceReason} />
          <Sources refs={item.sourceRefs} />
        </li>
      ))}
    </ul>
  );
}

function IndicatorList({ items }: { items: Schema<"Indicator">[] }) {
  if (!items.length)
    return <p className="muted empty">当前尚未给出验证指标。</p>;
  return (
    <ul className="claims">
      {items.map((item) => (
        <li key={item.id}>
          <h3>{item.name}</h3>
          <ClaimTag type={item.type} />
          <dl className="indicator">
            <div>
              <dt>如何度量</dt>
              <dd>{item.measurement}</dd>
            </div>
            <div>
              <dt>追踪频率</dt>
              <dd>{item.frequency}</dd>
            </div>
          </dl>
          <p className="claim-reason">{item.statement}</p>
          <p className="claim-reason muted">{item.reasoning}</p>
          <Confidence value={item.confidence} />
          <Sources refs={item.sourceRefs} />
        </li>
      ))}
    </ul>
  );
}

function ModelRuns({ runs }: { runs: Schema<"ModelRun">[] }) {
  return (
    <section className="panel model-runs" aria-labelledby="model-runs-heading">
      <h2 id="model-runs-heading">Model Runs · 调用审计</h2>
      <p className="section-hint">
        次要信息：模型调用记录，用于核对耗时、用量与失败原因。
      </p>
      {runs.length ? (
        <ul className="audit-list">
          {runs.map((run) => (
            <li className="audit" key={run.id}>
              <strong>
                {run.provider} / {run.model}
              </strong>
              <p className="muted">
                {run.purpose} · prompt {run.promptVersion} ·{" "}
                {modelRunStatusLabel[run.status]}（{run.status}）
              </p>
              <small>
                {run.latencyMs} ms · tokens {run.totalTokens ?? "未知"} · cost{" "}
                {run.estimatedCost === null ? "未估算" : run.estimatedCost}
              </small>
              {run.errorType ? (
                <small className="muted"> 错误类型 {run.errorType}</small>
              ) : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted">暂无调用记录。</p>
      )}
    </section>
  );
}

export function AnalysisView({
  analysis,
  runs = [],
}: {
  analysis: Schema<"Analysis">;
  runs?: Schema<"ModelRun">[];
}) {
  const result = analysis.result;
  const confidence = result.confidenceAssessment;
  return (
    <section aria-label="分析结果">
      <div className="notice">
        {result.demo
          ? "Mock 演示结果 · 未经独立核实"
          : "模型生成分析 · 请核查原始证据"}
        。置信度是判断尺度，不是统计概率。
      </div>
      <div className="analysis-layout">
        <div className="analysis-main">
          <section className="panel section summary" aria-labelledby="summary">
            <div className="section-head">
              <h2 id="summary">Summary · 摘要</h2>
              <p className="section-hint">这条新闻真正发生了什么。</p>
            </div>
            <p className="lead">{result.summary}</p>
            <p className="why">
              <strong>Why It Matters · 为什么重要：</strong>
              {analysis.score.reason}
            </p>
            <ul className="meta-list">
              <li>
                <span className="meta-key">Domain · 领域</span>
                <span className="meta-value">{analysis.domain}</span>
              </li>
              <li>
                <span className="meta-key">News Grade · 信息分级</span>
                <span className="meta-value">{analysis.score.grade}</span>
              </li>
              <li>
                <span className="meta-key">更新既有假设</span>
                <span className="meta-value">
                  {result.modifiesExistingHypotheses
                    ? "是"
                    : "本轮尚未关联既有假设"}
                </span>
              </li>
            </ul>
            <div className="overall-confidence">
              <Confidence value={confidence.score} reason={confidence.reason} />
            </div>
          </section>

          <Section
            id="facts"
            title="Facts · 来源事实"
            hint="标记为 FACT 的原文陈述；事实与推断分开呈现。"
          >
            <ClaimList items={result.facts} empty="当前没有可确认的事实。" />
          </Section>

          <Section
            id="variables"
            title="Key Variables · 关键变量"
            hint="变量、方向与解释。方向未知时不会假装有结论。"
          >
            <VariableList items={result.variables} />
          </Section>

          <Section
            id="mechanism"
            title="Mechanism · 影响机制"
            hint="从原因到结果的因果链。"
          >
            <MechanismList items={result.mechanisms} />
          </Section>

          <Section
            id="stakeholders"
            title="Stakeholders · 利益相关者"
            hint="区分受益方、受损方与不确定方。"
          >
            <StakeholderList items={result.stakeholders} />
          </Section>

          <Section
            id="first-order"
            title="First-order Effects · 一级影响"
            hint="直接、短期的影响。"
          >
            <ClaimList
              items={result.firstOrderEffects}
              empty="当前尚未形成一级影响判断。"
            />
          </Section>

          <Section
            id="second-order"
            title="Second-order Effects · 二级影响"
            hint="由一级影响衍生的间接后果。"
          >
            <ClaimList
              items={result.secondOrderEffects}
              empty="当前尚未形成二级影响判断。"
            />
          </Section>

          <Section
            id="hypotheses"
            title="Hypotheses · 主要假设"
            hint="标题、陈述、置信度与置信度理由。"
          >
            <HypothesisList items={result.hypotheses} />
          </Section>

          <Section
            id="alternatives"
            title="Alternative Explanations · 替代解释"
            hint="同一现象的其他可能解释。"
          >
            <ClaimList
              items={result.alternativeExplanations}
              empty="当前尚未给出替代解释。"
            />
          </Section>

          <Section
            id="counter"
            title="Counter Arguments · 反方论证"
            hint="与主叙事相反的证据与推理。"
          >
            <ClaimList
              items={result.counterArguments}
              empty="当前尚未记录反方论证。"
            />
          </Section>

          <Section
            id="falsification"
            title="Falsification · 可证伪条件"
            hint="出现什么证据，会说明当前判断可能是错的。"
            tone="falsification"
          >
            <ClaimList
              items={result.falsificationConditions}
              empty="当前尚未给出可证伪条件，这意味着判断暂时无法被检验。"
            />
          </Section>

          <Section
            id="signals"
            title="Corroborating Signals · 旁证"
            hint="支持或削弱当前判断的外部信号。契约当前未提供方向与状态字段，因此只展示信号与理由。"
          >
            <ClaimList
              items={result.corroboratingSignals}
              empty="当前尚未发现旁证。"
            />
          </Section>

          <Section
            id="verification"
            title="Verification Plan · 验证计划"
            hint="接下来应该继续跟踪什么。"
          >
            <IndicatorList items={result.verificationIndicators} />
            <h3 className="sub-head">后续观察</h3>
            <ClaimList
              items={result.upcomingObservations}
              empty="当前尚未安排后续观察。"
            />
          </Section>

          <Section
            id="unknowns"
            title="Unknowns · 未知事项"
            hint="现在还不知道、且不应假装知道的部分。"
          >
            <ClaimList
              items={result.unknowns}
              empty="当前没有记录未知事项，这本身值得怀疑。"
            />
          </Section>
        </div>

        <aside className="analysis-aside" aria-label="补充信息">
          <ModelRuns runs={runs} />
          <section className="panel" aria-labelledby="reading-notes">
            <h2 id="reading-notes">Reading Notes · 阅读提示</h2>
            <ul className="notes">
              <li>
                <span className="tag fact">FACT · 事实</span>
                只来自输入的原文陈述，未经独立核实。
              </li>
              <li>
                <span className="tag inference">INFERENCE · 推断</span>
                由已有信息推导，可能被后续证据推翻。
              </li>
              <li>
                <span className="tag hypothesis">HYPOTHESIS · 假设</span>
                待检验的判断，附带可证伪条件。
              </li>
              <li>
                <span className="tag prediction">PREDICTION · 预测</span>
                对未来的判断，当前契约中仍为占位。
              </li>
            </ul>
          </section>
        </aside>
      </div>
    </section>
  );
}
