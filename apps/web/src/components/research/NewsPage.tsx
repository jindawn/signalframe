"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useEffect, useState } from "react";
import type { Schema } from "@/lib/api";
import {
  ingestionCategoryLabel,
  ingestionDiagnostic,
  parseIngestionReason,
  rawDiagnostic,
} from "@/lib/ingestion";
import { extractionLabel } from "@/lib/labels";
import { AnalysisView } from "../AnalysisView";
export function NewsPage({ id }: { id: string }) {
  const [news, setNews] = useState<Schema<"NewsItem">>();
  const [analysis, setAnalysis] = useState<Schema<"Analysis">>();
  const [runs, setRuns] = useState<Schema<"ModelRun">[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    async function load() {
      setNews(
        unwrap(
          await api.GET("/api/v1/news/{id}", { params: { path: { id } } }),
        ),
      );
      const list = unwrap(
        await api.GET("/api/v1/news/{id}/analyses", {
          params: { path: { id } },
        }),
      );
      if (list[0]) {
        setAnalysis(list[0]);
        setRuns(
          unwrap(
            await api.GET("/api/v1/model-runs", {
              params: { query: { jobId: list[0].jobId } },
            }),
          ),
        );
      }
    }
    load().catch((e) => setError(e.message));
  }, [id]);
  const needsText = news?.source.extractionStatus === "NEEDS_TEXT";
  const reason = parseIngestionReason(news?.source.message);
  const diagnostic = ingestionDiagnostic(news?.source.message);
  // A reason code is shown for every failed URL, including the case where the
  // pasted text was analyzed anyway; extraction provenance is shown otherwise.
  const showDiagnostic = Boolean(news) && (needsText || reason !== null);
  const provenance = rawDiagnostic(news?.source.message);
  return (
    <>
      <header>
        <div className="eyebrow">SOURCE → WORLD MODEL</div>
        <h1>{news?.title ?? "新闻详情"}</h1>
      </header>
      <ApiError text={error} />
      {news && (
        <details className="panel">
          <summary>
            原始输入 · {extractionLabel[news.source.extractionStatus]}
          </summary>
          {showDiagnostic && (
            <div className="diagnostic" role="status">
              <p className="diagnostic-head">
                <span className="status-badge">
                  {reason && <span className="status-code">{reason}</span>}
                  <span className="status-label">
                    {ingestionCategoryLabel[diagnostic.category]}
                  </span>
                </span>
                <strong>{diagnostic.title}</strong>
              </p>
              <p className="muted">{diagnostic.detail}</p>
              <p className="hint">{diagnostic.action}</p>
            </div>
          )}
          {!showDiagnostic && provenance && (
            <p className="muted">
              <small>{provenance}</small>
            </p>
          )}
          {news.source.url && (
            <p className="breakable">
              <a href={news.source.url} target="_blank" rel="noreferrer">
                原网页 ↗
              </a>
            </p>
          )}
          {news.source.text ? (
            <p className="source-text">{news.source.text}</p>
          ) : (
            <p className="muted">
              该来源还没有正文，暂时无法分析。回到{" "}
              <Link href="/inbox">Inbox</Link> 粘贴正文后即可继续。
            </p>
          )}
          <small>Source {news.source.id}</small>
        </details>
      )}
      {analysis ? (
        <AnalysisView analysis={analysis} runs={runs} />
      ) : (
        news && (
          <section className="panel muted">
            尚无保存的分析。<Link href="/inbox">去 Inbox 分析 →</Link>
          </section>
        )
      )}
    </>
  );
}
