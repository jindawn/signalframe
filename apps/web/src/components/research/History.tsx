"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useLoad } from "@/lib/useLoad";
const newsLoad = async () => unwrap(await api.GET("/api/v1/news"));
export function History() {
  const { data, error } = useLoad(newsLoad);
  return (
    <>
      <header>
        <h1>History</h1>
        <p>回到原始输入，复查当时的判断。</p>
      </header>
      <ApiError text={error} />
      <section className="panel">
        {data?.map((n) => (
          <Link key={n.id} className="list-item" href={`/news/${n.id}`}>
            <strong>{n.title}</strong>
            <small>
              {new Date(n.createdAt).toLocaleString("zh-CN")} ·{" "}
              {n.source.extractionStatus}
            </small>
          </Link>
        ))}
        {data?.length === 0 && <p className="muted">暂无记录。</p>}
      </section>
    </>
  );
}
