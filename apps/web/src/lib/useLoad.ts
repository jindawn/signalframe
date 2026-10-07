"use client";
import { useEffect, useState } from "react";
export function useLoad<T>(load: () => Promise<T>) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    load()
      .then((v) => {
        if (live) setData(v);
      })
      .catch((e) => {
        if (live) setError(e instanceof Error ? e.message : "请求失败");
      });
    return () => {
      live = false;
    };
  }, [load]);
  return { data, error };
}
