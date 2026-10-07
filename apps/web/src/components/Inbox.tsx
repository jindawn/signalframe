"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type Schema } from "@/lib/api";
import { safeError, throwIfFailed } from "@/lib/errors";
import { extractionLabel } from "@/lib/labels";
import { terminal } from "@/lib/progress";
import { AnalysisView } from "./AnalysisView";
import { JobProgress } from "./JobProgress";

const ACTIVE_JOB_KEY = "signalframe.activeJob";
const RECOVERABLE_URL_TEXT = "无法可靠提取该网页，可粘贴正文继续分析。";

type Notice = { tone: "error" | "info"; text: string };
type FieldErrors = { url?: string; text?: string };

function validate(url: string, text: string): FieldErrors {
  const errors: FieldErrors = {};
  const trimmedUrl = url.trim();
  if (!trimmedUrl && !text.trim())
    errors.text = "请至少输入一个 URL 或一段新闻正文。";
  else if (trimmedUrl && !/^https?:\/\/\S+$/i.test(trimmedUrl))
    errors.url = "URL 需要以 http:// 或 https:// 开头，且不能包含空格。";
  return errors;
}

export function Inbox() {
  const [url, setUrl] = useState("");
  const [text, setText] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [job, setJob] = useState<Schema<"AnalysisJob"> | null>(null);
  const [news, setNews] = useState<Schema<"NewsItem"> | null>(null);
  const [analysis, setAnalysis] = useState<Schema<"Analysis"> | null>(null);
  const [runs, setRuns] = useState<Schema<"ModelRun">[]>([]);

  useEffect(() => {
    // Deferred so the restore runs after hydration and does not cascade renders.
    const timer = setTimeout(() => {
      const id = window.localStorage.getItem(ACTIVE_JOB_KEY);
      if (!id) return;
      setBusy(true);
      setNotice({ tone: "info", text: "正在恢复上一次未完成的分析任务…" });
      setJobId(id);
    }, 0);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let inFlight = false;
    let failures = 0;
    const stream =
      typeof EventSource === "undefined"
        ? null
        : new EventSource(`/api/v1/analysis-jobs/${jobId}/events`);

    const finish = async (current: Schema<"AnalysisJob">) => {
      stream?.close();
      window.localStorage.removeItem(ACTIVE_JOB_KEY);
      if (current.status === "FAILED") {
        setBusy(false);
        setNotice({
          tone: "error",
          text: `分析未完成：${safeError(current.error, "可以重试本次分析。")}`,
        });
        return;
      }
      setNotice(null);
      if (!current.analysisId) {
        setBusy(false);
        return;
      }
      try {
        const [analysisResult, runResult] = await Promise.all([
          api.GET("/api/v1/analyses/{id}", {
            params: { path: { id: current.analysisId } },
          }),
          api.GET("/api/v1/model-runs", {
            params: { query: { jobId: current.id } },
          }),
        ]);
        if (cancelled) return;
        setAnalysis(throwIfFailed(analysisResult));
        setRuns(throwIfFailed(runResult));
      } catch (error) {
        if (!cancelled)
          setNotice({
            tone: "error",
            text: safeError(error, "分析已保存，但读取结果失败，请重试。"),
          });
      } finally {
        if (!cancelled) setBusy(false);
      }
    };

    const tick = async () => {
      if (cancelled || inFlight) return;
      inFlight = true;
      try {
        const current = throwIfFailed(
          await api.GET("/api/v1/analysis-jobs/{id}", {
            params: { path: { id: jobId } },
          }),
        );
        if (cancelled) return;
        failures = 0;
        setJob(current);
        setBusy(true);
        if (terminal(current.status)) {
          await finish(current);
          return;
        }
        timer = setTimeout(tick, 500);
      } catch (error) {
        if (cancelled) return;
        failures += 1;
        if (failures >= 5) {
          setBusy(false);
          setNotice({
            tone: "error",
            text: safeError(error, "无法获取任务状态，请稍后重试。"),
          });
          stream?.close();
          return;
        }
        setNotice({
          tone: "info",
          text: "与后端的连接中断，正在自动重试…",
        });
        timer = setTimeout(tick, 600 * failures);
      } finally {
        inFlight = false;
      }
    };

    stream?.addEventListener("progress", () => {
      void tick();
    });
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
      stream?.close();
    };
  }, [jobId]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    const errors = validate(url, text);
    setFieldErrors(errors);
    if (errors.url || errors.text) {
      setNotice({
        tone: "error",
        text: errors.url ?? errors.text ?? "请检查输入。",
      });
      return;
    }
    window.localStorage.removeItem(ACTIVE_JOB_KEY);
    setNotice(null);
    setAnalysis(null);
    setRuns([]);
    setJob(null);
    setJobId(null);
    setNews(null);
    setBusy(true);
    try {
      const item = throwIfFailed(
        await api.POST("/api/v1/news", {
          body: { url: url.trim() || null, text: text.trim() || null },
        }),
      );
      setNews(item);
      if (item.source.extractionStatus === "NEEDS_TEXT") {
        setBusy(false);
        setNotice({ tone: "error", text: RECOVERABLE_URL_TEXT });
        return;
      }
      const created = throwIfFailed(
        await api.POST("/api/v1/news/{id}/analyze", {
          params: { path: { id: item.id } },
        }),
      );
      setJob(created);
      window.localStorage.setItem(ACTIVE_JOB_KEY, created.id);
      setJobId(created.id);
    } catch (error) {
      setBusy(false);
      setNotice({
        tone: "error",
        text: safeError(error, "提交失败，请稍后重试。"),
      });
    }
  }

  async function retry() {
    const newsId = job?.newsId ?? news?.id;
    if (!newsId || busy) return;
    setNotice(null);
    setJob(null);
    setBusy(true);
    try {
      const created = throwIfFailed(
        await api.POST("/api/v1/news/{id}/analyze", {
          params: { path: { id: newsId } },
        }),
      );
      setJob(created);
      window.localStorage.setItem(ACTIVE_JOB_KEY, created.id);
      setJobId(created.id);
    } catch (error) {
      setBusy(false);
      setNotice({
        tone: "error",
        text: safeError(error, "重试失败，请稍后重试。"),
      });
    }
  }

  function reset() {
    window.localStorage.removeItem(ACTIVE_JOB_KEY);
    setJobId(null);
    setJob(null);
    setAnalysis(null);
    setRuns([]);
    setNews(null);
    setNotice(null);
  }

  return (
    <>
      <header className="page-head">
        <div className="eyebrow">CAPTURE → UNDERSTAND</div>
        <h1>Inbox</h1>
        <p>把一条信息变成可验证的研究起点。</p>
      </header>
      <form className="panel input-panel" onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="url">
            新闻 URL <span>可选</span>
          </label>
          <p className="hint" id="url-hint">
            提取失败时不会中断，可以改为粘贴正文继续。
          </p>
          <input
            id="url"
            name="url"
            type="url"
            inputMode="url"
            autoComplete="off"
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            placeholder="https://…"
            maxLength={2048}
            aria-invalid={fieldErrors.url ? true : undefined}
            aria-describedby={fieldErrors.url ? "url-error" : "url-hint"}
          />
          {fieldErrors.url && (
            <p className="field-error" id="url-error">
              {fieldErrors.url}
            </p>
          )}
        </div>
        <div className="field">
          <label htmlFor="text">新闻正文 / 补充文字</label>
          <p className="hint" id="text-hint">
            直接粘贴正文即可；URL 与正文会合并成一个可追踪来源。
          </p>
          <textarea
            id="text"
            name="text"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="粘贴新闻正文；无法提取的网页可通过正文继续分析。"
            rows={7}
            maxLength={100000}
            aria-invalid={fieldErrors.text ? true : undefined}
            aria-describedby={fieldErrors.text ? "text-error" : "text-hint"}
          />
          {fieldErrors.text && (
            <p className="field-error" id="text-error">
              {fieldErrors.text}
            </p>
          )}
        </div>
        <div className="form-footer">
          <small>默认 Mock · 无需 API Key · 数据保存在本地 PostgreSQL</small>
          <button
            type="submit"
            disabled={busy || (!url.trim() && !text.trim())}
            aria-busy={busy}
          >
            {busy ? "分析中…" : "Analyze →"}
          </button>
        </div>
      </form>

      {notice && (
        <p role="alert" className={`alert alert-${notice.tone}`}>
          {notice.text}
        </p>
      )}

      {busy && !job && (
        <section className="panel" aria-label="提交状态">
          <p className="muted" aria-live="polite">
            正在创建来源并提交分析任务…
          </p>
        </section>
      )}

      {job && <JobProgress job={job} />}

      {job?.status === "FAILED" && (
        <div className="panel action-bar">
          <button type="button" onClick={retry} disabled={busy}>
            重试分析
          </button>
          <button type="button" className="secondary" onClick={reset}>
            返回修改输入
          </button>
        </div>
      )}

      {news && (
        <details className="panel captured">
          <summary>
            已捕获的输入 · {extractionLabel[news.source.extractionStatus]}
          </summary>
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
            <p className="muted">这段输入没有正文文本。</p>
          )}
          <small>
            标题 {news.title} · 领域 {news.domain}
          </small>
        </details>
      )}

      {analysis && (
        <div className="panel action-bar success-bar">
          <Link className="button" href={`/analyses/${analysis.id}`}>
            打开完整分析详情 →
          </Link>
          <Link className="button secondary" href={`/news/${analysis.newsId}`}>
            查看来源与历史分析 →
          </Link>
        </div>
      )}

      {analysis && <AnalysisView analysis={analysis} runs={runs} />}

      {!busy && !job && !analysis && (
        <section className="panel" aria-labelledby="inbox-empty">
          <h2 id="inbox-empty">还没有分析结果</h2>
          <ol className="steps-hint">
            <li>粘贴新闻 URL，或直接粘贴正文。</li>
            <li>点击 Analyze，任务会进入持久化队列。</li>
            <li>任务完成后在这里阅读结构化结论与可证伪条件。</li>
          </ol>
        </section>
      )}
    </>
  );
}
