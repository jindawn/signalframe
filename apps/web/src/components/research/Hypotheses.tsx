"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useLoad } from "@/lib/useLoad";
const hypothesesLoad = async () => unwrap(await api.GET("/api/v1/hypotheses"));
export function Hypotheses() {
  const { data, error } = useLoad(hypothesesLoad);
  return (
    <>
      <header>
        <div className="eyebrow">BELIEFS, OPEN TO REVISION</div>
        <h1>Hypotheses</h1>
        <p>保留假设，也保留推翻它的条件。</p>
      </header>
      <ApiError text={error} />
      {data?.map((h) => (
        <section className="panel" key={h.id}>
          <div className="row">
            <Link className="item-link" href={`/hypotheses/${h.id}`}>
              {h.title} →
            </Link>
            <span className="confidence">
              {h.confidence}
              <small>/100</small>
            </span>
          </div>
          <p>{h.confidenceReason}</p>
          <small>
            {h.status} · {new Date(h.updatedAt).toLocaleString("zh-CN")} ·
            证据与时间线见详情
          </small>
        </section>
      ))}
      {data?.length === 0 && (
        <section className="panel muted">暂无假设。先分析一段新闻。</section>
      )}
    </>
  );
}
