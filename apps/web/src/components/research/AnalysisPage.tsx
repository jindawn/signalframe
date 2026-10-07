"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type Schema } from "@/lib/api";
import { safeError, throwIfFailed } from "@/lib/errors";
import { extractionLabel } from "@/lib/labels";
import { AnalysisView } from "../AnalysisView";

export function AnalysisPage({ id }: { id: string }) {
  const [analysis, setAnalysis] = useState<Schema<"Analysis">>();
  const [news, setNews] = useState<Schema<"NewsItem">>();
  const [runs, setRuns] = useState<Schema<"ModelRun">[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      setLoading(true);
      setError("");
      try {
        const found = throwIfFailed(
          await api.GET("/api/v1/analyses/{id}", {
            params: { path: { id } },
          }),
        );
        if (cancelled) return;
        setAnalysis(found);
        const [item, runList] = await Promise.all([
          api.GET("/api/v1/news/{id}", {
            params: { path: { id: found.newsId } },
          }),
          api.GET("/api/v1/model-runs", {
            params: { query: { jobId: found.jobId } },
          }),
        ]);
        if (cancelled) return;
        setNews(throwIfFailed(item));
        setRuns(throwIfFailed(runList));
      } catch (caught) {
        if (!cancelled)
          setError(safeError(caught, "无法加载该分析结果，请稍后重试。"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [id]);

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link href="/inbox">← 返回 Inbox</Link>
        <Link href="/history">History</Link>
      </nav>
      <header className="page-head">
        <div className="eyebrow">ANALYSIS DETAIL · 结构化分析</div>
        <h1>{news?.title ?? "分析详情"}</h1>
        <dl className="head-meta">
          <div>
            <dt>分析 ID</dt>
            <dd className="breakable">{id}</dd>
          </div>
          {news && (
            <div>
              <dt>来源</dt>
              <dd>{extractionLabel[news.source.extractionStatus]}</dd>
            </div>
          )}
          {analysis && (
            <div>
              <dt>领域</dt>
              <dd>{analysis.domain}</dd>
            </div>
          )}
          {analysis && (
            <div>
              <dt>完成时间</dt>
              <dd>{new Date(analysis.createdAt).toLocaleString("zh-CN")}</dd>
            </div>
          )}
        </dl>
      </header>
      {loading && (
        <section className="panel" aria-live="polite">
          <p className="muted">正在加载分析结果…</p>
        </section>
      )}
      {error && (
        <p role="alert" className="alert alert-error">
          {error}
        </p>
      )}
      {!loading && !error && !analysis && (
        <section className="panel">
          <p className="muted">没有找到对应的分析结果。</p>
          <p>
            <Link className="item-link" href="/inbox">
              去 Inbox 发起一次分析 →
            </Link>
          </p>
        </section>
      )}
      {analysis && <AnalysisView analysis={analysis} runs={runs} />}
    </>
  );
}
