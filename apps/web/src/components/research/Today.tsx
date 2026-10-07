"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useLoad } from "@/lib/useLoad";
const newsLoad = async () => unwrap(await api.GET("/api/v1/news"));
const hypothesesLoad = async () => unwrap(await api.GET("/api/v1/hypotheses"));
export function Today() {
  const { data: news, error } = useLoad(newsLoad);
  const { data: hypotheses, error: he } = useLoad(hypothesesLoad);
  return (
    <>
      <header>
        <div className="eyebrow">YOUR CHANGING WORLD MODEL</div>
        <h1>今日关键变化</h1>
        <p>从信息中识别变量，以证据持续修正判断。</p>
        <Link className="button" href="/inbox">
          + 添加信息
        </Link>
      </header>
      <ApiError text={error || he} />
      <section className="panel">
        <h2>Key Variables · 关键变量</h2>
        <p className="muted">
          在新闻详情中查看变量方向。Mock 演示不推断真实变化。
        </p>
      </section>
      <div className="two-columns">
        <section className="panel">
          <h2>High Value News</h2>
          {news?.length ? (
            news.slice(0, 6).map((n) => (
              <Link className="list-item" key={n.id} href={`/news/${n.id}`}>
                <span className="tag">INPUT</span>
                <strong>{n.title}</strong>
                <small>
                  {new Date(n.createdAt).toLocaleDateString("zh-CN")}
                </small>
              </Link>
            ))
          ) : (
            <p className="muted">尚无信息。去 Inbox 粘贴一段新闻开始。</p>
          )}
        </section>
        <section className="panel">
          <h2>Hypothesis Updates</h2>
          {hypotheses?.length ? (
            hypotheses.slice(0, 4).map((h) => (
              <Link
                key={h.id}
                className="list-item"
                href={`/hypotheses/${h.id}`}
              >
                <span className="tag hypothesis">HYPOTHESIS</span>
                <strong>{h.title}</strong>
                <small>
                  判断尺度 {h.confidence}/100 · {h.status}
                </small>
              </Link>
            ))
          ) : (
            <p className="muted">分析完成后，假设将在这里出现。</p>
          )}
        </section>
      </div>
      <section className="panel">
        <h2>Need Verification</h2>
        <p className="muted">
          分析结果包含验证指标。到期预测自动追踪留给后续任务。
        </p>
      </section>
    </>
  );
}
