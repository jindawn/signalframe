"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useEffect, useState } from "react";
import type { Schema } from "@/lib/api";
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
  return (
    <>
      <header>
        <div className="eyebrow">SOURCE → WORLD MODEL</div>
        <h1>{news?.title ?? "新闻详情"}</h1>
      </header>
      <ApiError text={error} />
      {news && (
        <details className="panel">
          <summary>原始输入 · {news.source.extractionStatus}</summary>
          {news.source.url && (
            <a href={news.source.url} target="_blank" rel="noreferrer">
              原网页 ↗
            </a>
          )}
          <p className="source-text">{news.source.text}</p>
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
