"use client";
import { api, unwrap } from "@/lib/api";
import { hypothesisStatusLabel } from "@/lib/labels";
import { ApiError } from "../ApiError";
import { useEffect, useState } from "react";
import type { Schema } from "@/lib/api";

/**
 * Marks the machine-readable trailer a transition appends to its timeline reason.
 *
 * The frozen `HypothesisEvent` record has no field for the dimension breakdown,
 * the rubric version, the version pair or the cited reference, and the API's JSON
 * codec rejects unknown properties, so the engine carries them in a deterministic
 * trailer inside `reason` (see `TransitionEventText` on the server). The timeline
 * shows the human sentence and renders the trailer as structured detail.
 */
const TRAILER = " | transition: ";

type TimelineEvent = Schema<"HypothesisEvent">;

type Trailer = {
  cause?: string;
  previousVersion?: number;
  version?: number;
  previousScore?: number;
  score?: number;
  band?: string;
  rubric?: string;
  method?: string;
  moved: string[];
};

const bandLabel: Record<Schema<"ConfidenceBand">, string> = {
  VERY_LOW: "极低",
  LOW: "低",
  MEDIUM: "中",
  HIGH: "高",
  VERY_HIGH: "极高",
};

const eventLabel: Record<TimelineEvent["eventType"], string> = {
  CREATED: "建立",
  EVIDENCE_ADDED: "新增证据",
  CONFIDENCE_CHANGED: "判断尺度变化",
  PREDICTION_VERIFIED: "预测验证",
  STATUS_CHANGED: "状态转移",
};

const dimensionLabel: Record<string, string> = {
  D1_SOURCE_QUALITY: "D1 来源质量",
  D2_EVIDENCE_DIRECTNESS: "D2 证据直接性",
  D3_INDEPENDENT_CORROBORATION: "D3 独立佐证",
  D4_MECHANISM_SUPPORT: "D4 机制支持",
  D5_COUNTER_EVIDENCE_RESILIENCE: "D5 反证韧性",
};

const stanceLabel: Record<Schema<"Evidence">["stance"], string> = {
  SUPPORTS: "支持",
  CONTRADICTS: "反对",
  NEUTRAL: "中性",
};

const predictionLabel: Record<Schema<"Prediction">["status"], string> = {
  OPEN: "未决",
  CONFIRMED: "已验证",
  REJECTED: "已否定",
  PARTIAL: "部分验证",
  UNRESOLVED: "无法判定",
};

function optional(value: string | undefined): string | undefined {
  return value === undefined || value === "" || value === "none"
    ? undefined
    : value;
}

function numberOrUndefined(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}

/**
 * Splits a stored reason into the human sentence and the transition trailer.
 *
 * Parsing from the last marker keeps the read unambiguous even when a caller's own
 * reason text contains the marker, and an absent or malformed trailer is reported
 * as "no trailer" rather than as a failure: the sentence is still shown.
 */
export function parseTransition(reason: string): {
  text: string;
  trailer?: Trailer;
} {
  const at = reason.lastIndexOf(TRAILER);
  if (at < 0) return { text: reason };
  const text = reason.slice(0, at);
  const values = new Map<string, string>();
  for (const pair of reason.slice(at + TRAILER.length).split(";")) {
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    values.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }
  if (!values.has("cause")) return { text: reason };
  const moved = optional(values.get("moved"));
  return {
    text,
    trailer: {
      cause: values.get("cause"),
      previousVersion: numberOrUndefined(values.get("previousVersion")),
      version: numberOrUndefined(values.get("version")),
      previousScore: numberOrUndefined(values.get("previousScore")),
      score: numberOrUndefined(values.get("score")),
      band: optional(values.get("band")),
      rubric: optional(values.get("rubric")),
      method: optional(values.get("method")),
      moved: moved ? moved.split(",").filter(Boolean) : [],
    },
  };
}

function statusText(status: Schema<"HypothesisStatus"> | undefined): string {
  return status ? hypothesisStatusLabel[status] : "—";
}

function bandText(band: string | undefined): string | undefined {
  if (!band) return undefined;
  return bandLabel[band as Schema<"ConfidenceBand">] ?? band;
}

function Movement({
  event,
  trailer,
}: {
  event: TimelineEvent;
  trailer?: Trailer;
}) {
  const previous = trailer?.previousScore ?? event.previousConfidence;
  const current = trailer?.score ?? event.confidence;
  return (
    <>
      {event.status !== undefined && event.previousStatus !== undefined && (
        <strong>
          {statusText(event.previousStatus)} → {statusText(event.status)}{" "}
        </strong>
      )}
      <strong>
        {previous === null || previous === undefined ? "" : `${previous} → `}
        {current}/100
        {bandText(trailer?.band) ? ` · ${bandText(trailer?.band)}` : ""}
      </strong>
    </>
  );
}

export function HypothesisPage({ id }: { id: string }) {
  const [detail, setDetail] = useState<Schema<"HypothesisDetail">>();
  const [error, setError] = useState("");
  useEffect(() => {
    api
      .GET("/api/v1/hypotheses/{id}", { params: { path: { id } } })
      .then(unwrap)
      .then(setDetail)
      .catch((e) => setError(e.message));
  }, [id]);

  const hypothesis = detail?.hypothesis;
  const supporting = detail?.evidence.filter((e) => e.stance === "SUPPORTS").length;
  const contradicting = detail?.evidence.filter(
    (e) => e.stance === "CONTRADICTS",
  ).length;
  const openPredictions = detail?.predictions.filter(
    (p) => p.status === "OPEN",
  ).length;

  return (
    <>
      <header>
        <div className="eyebrow">HYPOTHESIS TIMELINE · 假设时间线</div>
        <h1>{hypothesis?.title ?? "假设详情"}</h1>
      </header>
      <ApiError text={error} />
      {detail && hypothesis && (
        <>
          <section className="panel">
            <div className="row">
              <span className="tag">
                {hypothesisStatusLabel[hypothesis.status]}
              </span>
              {hypothesis.confidenceBand && (
                <span className="tag">
                  判断尺度带 {bandText(hypothesis.confidenceBand)}
                </span>
              )}
              <span className="tag">版本 v{detail.version}</span>
            </div>
            <p>{hypothesis.description}</p>
            <p className="confidence">
              {hypothesis.confidence}
              <small> /100 判断尺度</small>
            </p>
            {/* Required in every case: the number is an ordinal judgment about how
                well the snapshot is supported, never a probability of truth. */}
            <p className="muted">
              这是可审计的判断尺度，不是「成真的概率」，也不能跨 rubric
              版本比较。尺度升高不代表已被验证；验证看状态与预测记录。
            </p>
            <div className="row">
              <span>支持证据 {supporting ?? 0}</span>
              <span>反对证据 {contradicting ?? 0}</span>
              <span>未决预测 {openPredictions ?? 0}</span>
            </div>
            <details>
              <summary>判断依据（rubric 计算明细）</summary>
              <p className="muted">{hypothesis.confidenceReason}</p>
            </details>
          </section>

          <section className="panel">
            <h2>Timeline · 判断演进</h2>
            {detail.timeline.length === 0 ? (
              <p className="muted">尚无时间线记录。</p>
            ) : (
              <ol className="timeline">
                {[...detail.timeline].reverse().map((e) => {
                  const { text, trailer } = parseTransition(e.reason);
                  return (
                    <li key={e.id}>
                      <span className="tag">{eventLabel[e.eventType]}</span>
                      <Movement event={e} trailer={trailer} />
                      <p>{text}</p>
                      {trailer && trailer.moved.length > 0 && (
                        <p>
                          {trailer.moved.map((dimension) => (
                            <span className="tag" key={dimension}>
                              {dimensionLabel[dimension] ?? dimension}
                            </span>
                          ))}
                        </p>
                      )}
                      {trailer?.method === "MODEL_JUDGMENT" && (
                        <p className="muted">
                          此条不是 rubric 推导（历史数据缺少可评分快照），分数按原值保留。
                        </p>
                      )}
                      {trailer?.cause === "PREDICTION_VERIFIED" && (
                        <p className="muted">状态由验证记录决定，不由分数决定。</p>
                      )}
                      <small>
                        {new Date(e.createdAt).toLocaleString("zh-CN")}
                        {trailer?.rubric ? ` · rubric ${trailer.rubric}` : ""}
                      </small>
                    </li>
                  );
                })}
              </ol>
            )}
          </section>

          <section className="panel">
            <h2>Evidence · 证据</h2>
            {detail.evidence.length ? (
              detail.evidence.map((e) => (
                <p key={e.id}>
                  <span className="tag">{stanceLabel[e.stance]}</span>
                  强度 {e.strength}/100 · {e.reason}
                  <br />
                  <small>
                    {new Date(e.createdAt).toLocaleString("zh-CN")}
                    {e.factRefs?.length ? ` · 关联事实 ${e.factRefs.length}` : ""}
                  </small>
                </p>
              ))
            ) : (
              <p className="muted">尚未关联证据。</p>
            )}
          </section>

          <section className="panel">
            <h2>Predictions · 到期预测</h2>
            {detail.predictions.length ? (
              detail.predictions.map((p) => (
                <p key={p.id}>
                  <span className="tag">{predictionLabel[p.status]}</span>
                  {p.statement}
                  <br />
                  <small>
                    预期到期 {new Date(p.expectedBy).toLocaleDateString("zh-CN")}
                    {p.whereToCheck ? ` · 核对处 ${p.whereToCheck}` : ""}
                  </small>
                  <br />
                  <small className="muted">
                    判定标准：{p.verificationCriteria}
                  </small>
                </p>
              ))
            ) : (
              <p className="muted">尚未建立到期预测。</p>
            )}
          </section>
        </>
      )}
    </>
  );
}
