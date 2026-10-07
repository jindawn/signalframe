"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
const links = [
  ["/", "Today"],
  ["/inbox", "Inbox"],
  ["/hypotheses", "Hypotheses"],
  ["/topics", "Topics"],
  ["/history", "History"],
  ["/settings", "Settings"],
];
export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  useEffect(() => {
    if ("serviceWorker" in navigator)
      navigator.serviceWorker.register("/sw.js").catch(() => {});
  }, []);
  return (
    <div className="workspace">
      <aside className="sidebar">
        <Link className="brand" href="/">
          ◈ SignalFrame<span>RESEARCH WORKSPACE</span>
        </Link>
        <nav aria-label="主导航">
          {links.map(([href, label]) => (
            <Link
              className={path === href ? "active" : ""}
              key={href}
              href={href}
            >
              {label}
            </Link>
          ))}
        </nav>
        <div className="sidebar-note">
          信息 → 事实 → 假设 → 验证
          <br />
          Local foundation · Mock ready
        </div>
      </aside>
      <main>{children}</main>
      <nav className="bottom-nav" aria-label="手机导航">
        {[...links.slice(0, 4), ["/more", "More"]].map(([href, label]) => (
          <Link
            className={path === href ? "active" : ""}
            key={href}
            href={href}
          >
            {label}
          </Link>
        ))}
      </nav>
    </div>
  );
}
