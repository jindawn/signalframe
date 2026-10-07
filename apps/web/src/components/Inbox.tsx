"use client";
import { useEffect, useState } from "react";
import * as Progress from "@radix-ui/react-progress";
import { api, unwrap, type Schema } from "@/lib/api";
import { progress, terminal } from "@/lib/progress";
import { AnalysisView } from "./AnalysisView";
export function Inbox() {
  const [url, setUrl] = useState("");
  const [text, setText] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [job, setJob] = useState<Schema<"AnalysisJob"> | null>(null);
  const [analysis, setAnalysis] = useState<Schema<"Analysis"> | null>(null);
  const [runs, setRuns] = useState<Schema<"ModelRun">[]>([]);
  useEffect(() => {
    const restore = () => {
      const id = localStorage.getItem("signalframe.activeJob");
      if (id) setJobId(id);
    };
    const timer = setTimeout(restore, 0);
    return () => clearTimeout(timer);
  }, []);
  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const stream = new EventSource(`/api/v1/analysis-jobs/${jobId}/events`);
    let polling = false;
    async function tick() {
      if (cancelled || polling) return;
      polling = true;
      try {
        const current = unwrap(
          await api.GET("/api/v1/analysis-jobs/{id}", {
            params: { path: { id: jobId! } },
          }),
        );
        if (cancelled) return;
        setJob(current);
        if (terminal(current.status)) {
          stream.close();
          setBusy(false);
          localStorage.removeItem("signalframe.activeJob");
          if (current.status === "FAILED")
            setError(current.error ?? "分析失败");
          if (current.analysisId) {
            const [a, m] = await Promise.all([
              api.GET("/api/v1/analyses/{id}", {
                params: { path: { id: current.analysisId } },
              }),
              api.GET("/api/v1/model-runs", {
                params: { query: { jobId: current.id } },
              }),
            ]);
            if (!cancelled) {
              setAnalysis(unwrap(a));
              setRuns(unwrap(m));
            }
          }
        } else {
          setBusy(true);
          timer = setTimeout(tick, 500);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "请求失败");
          setBusy(false);
          stream.close();
        }
      } finally {
        polling = false;
      }
    }
    stream.addEventListener("progress", () => {
      void tick();
    });
    void tick();
    return () => {
      cancelled = true;
      clearTimeout(timer);
      stream.close();
    };
  }, [jobId]);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setAnalysis(null);
    setRuns([]);
    setJob(null);
    setBusy(true);
    try {
      const news = unwrap(
        await api.POST("/api/v1/news", {
          body: { url: url || null, text: text || null },
        }),
      );
      if (news.source.extractionStatus === "NEEDS_TEXT") {
        setError(news.source.message ?? "请粘贴正文");
        setBusy(false);
        return;
      }
      const created = unwrap(
        await api.POST("/api/v1/news/{id}/analyze", {
          params: { path: { id: news.id } },
        }),
      );
      setJob(created);
      localStorage.setItem("signalframe.activeJob", created.id);
      setJobId(created.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "请求失败");
      setBusy(false);
    }
  }
  return (
    <>
      <header>
        <div className="eyebrow">CAPTURE → UNDERSTAND</div>
        <h1>Inbox</h1>
        <p>把一条信息变成可验证的研究起点。</p>
      </header>
      <form className="panel input-panel" onSubmit={submit}>
        <label htmlFor="url">
          新闻 URL <span>可选</span>
        </label>
        <input
          id="url"
          type="url"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="https://…"
          maxLength={2048}
        />
        <label htmlFor="text">新闻正文 / 补充文字</label>
        <textarea
          id="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="粘贴新闻正文；无法提取的网页可通过正文继续分析。"
          rows={7}
          maxLength={100000}
        />
        <div className="form-footer">
          <small>默认 Mock · 无需 API Key · 数据保存在本地 PostgreSQL</small>
          <button disabled={busy || (!url.trim() && !text.trim())}>
            {busy ? "分析中…" : "Analyze →"}
          </button>
        </div>
      </form>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {job && (
        <section className="panel" aria-label="Pipeline Progress">
          <div className="row">
            <h2>Pipeline Progress</h2>
            <span role="status">{job.status}</span>
          </div>
          <Progress.Root
            className="progress"
            value={progress(job)}
            aria-label="分析进度"
          >
            <Progress.Indicator
              className="progress-fill"
              style={{ transform: `translateX(-${100 - progress(job)}%)` }}
            />
          </Progress.Root>
          <ol className="pipeline">
            {job.events.map((e) => (
              <li key={e.sequence}>
                <span>{e.step}</span>
                <small>{e.message}</small>
              </li>
            ))}
          </ol>
        </section>
      )}
      {analysis && <AnalysisView analysis={analysis} runs={runs} />}
    </>
  );
}
