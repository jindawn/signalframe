"use client";
import { api, unwrap } from "@/lib/api";
import { ApiError } from "../ApiError";
import { useLoad } from "@/lib/useLoad";
const profilesLoad = async () =>
  unwrap(await api.GET("/api/v1/settings/model-profiles"));
export function Settings() {
  const { data, error } = useLoad(profilesLoad);
  return (
    <>
      <header>
        <h1>Model Settings</h1>
        <p>业务使用 Purpose；模型名称由 Profile 配置。无 Key 时使用 Mock。</p>
      </header>
      <ApiError text={error} />
      {data?.map((p) => (
        <section className="panel" key={p.name}>
          <div className="row">
            <h2>{p.name}</h2>
            <span className="tag">{p.effectiveMode}</span>
          </div>
          <dl className="profile">
            {Object.entries(p.profile).map(([k, v]) => (
              <div key={k}>
                <dt>{k}</dt>
                <dd>{String(v)}</dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
      <section className="notice">
        持久配置使用 .env / application.yml。PUT API
        修改仅在当前进程有效。页面不读取或展示密钥值。
      </section>
    </>
  );
}
