"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useLoad } from "@/lib/useLoad";
const topicsLoad = async () => unwrap(await api.GET("/api/v1/topics"));
export function Topics() {
  const { data, error } = useLoad(topicsLoad);
  return (
    <>
      <header>
        <h1>Topics</h1>
        <p>跨新闻跟踪同一个研究主题。</p>
      </header>
      <ApiError text={error} />
      <section className="panel">
        {data?.length ? (
          data.map((t) => (
            <Link className="list-item" key={t.id} href={`/topics/${t.id}`}>
              {t.name} · {t.newsIds.length} 条新闻
            </Link>
          ))
        ) : (
          <p className="muted">
            主题契约与关系已准备好。自动关联由后续任务实现。
          </p>
        )}
      </section>
    </>
  );
}
