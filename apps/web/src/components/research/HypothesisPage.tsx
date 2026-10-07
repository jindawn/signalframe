"use client";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useEffect, useState } from "react";
import type { Schema } from "@/lib/api";
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
  return (
    <>
      <header>
        <div className="eyebrow">HYPOTHESIS TIMELINE</div>
        <h1>{detail?.hypothesis.title ?? "假设详情"}</h1>
      </header>
      <ApiError text={error} />
      {detail && (
        <>
          <section className="panel">
            <p>{detail.hypothesis.description}</p>
            <strong>当前判断尺度 {detail.hypothesis.confidence}/100</strong>
            <p>{detail.hypothesis.confidenceReason}</p>
            <div className="row">
              <span>
                支持证据{" "}
                {detail.evidence.filter((e) => e.stance === "SUPPORTS").length}
              </span>
              <span>
                反对证据{" "}
                {
                  detail.evidence.filter((e) => e.stance === "CONTRADICTS")
                    .length
                }
              </span>
            </div>
          </section>
          <section className="panel">
            <h2>Timeline · 判断演进</h2>
            <ol className="timeline">
              {detail.timeline.map((e) => (
                <li key={e.id}>
                  <span className="tag">{e.eventType}</span>
                  <strong>
                    {e.previousConfidence === null
                      ? ""
                      : `${e.previousConfidence} → `}
                    {e.confidence}/100
                  </strong>
                  <p>{e.reason}</p>
                  <small>{new Date(e.createdAt).toLocaleString("zh-CN")}</small>
                </li>
              ))}
            </ol>
          </section>
          <section className="panel">
            <h2>Predictions</h2>
            {detail.predictions.length ? (
              detail.predictions.map((p) => (
                <p key={p.id}>
                  {p.statement} · {p.status}
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
