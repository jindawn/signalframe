"use client";
import Link from "next/link";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useEffect, useState } from "react";
import type { Schema } from "@/lib/api";
export function TopicPage({ id }: { id: string }) {
  const [topic, setTopic] = useState<Schema<"Topic">>();
  const [error, setError] = useState("");
  useEffect(() => {
    api
      .GET("/api/v1/topics/{id}", { params: { path: { id } } })
      .then(unwrap)
      .then(setTopic)
      .catch((e) => setError(e.message));
  }, [id]);
  return (
    <>
      <header>
        <h1>{topic?.name ?? "主题详情"}</h1>
      </header>
      <ApiError text={error} />
      {topic && (
        <section className="panel">
          <h2>关联新闻</h2>
          {topic.newsIds.map((id) => (
            <Link className="list-item" key={id} href={`/news/${id}`}>
              {id} →
            </Link>
          ))}
          <h2>关联假设</h2>
          {topic.hypothesisIds.map((id) => (
            <Link className="list-item" key={id} href={`/hypotheses/${id}`}>
              {id} →
            </Link>
          ))}
        </section>
      )}
    </>
  );
}
