"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";

type NavItem = { href: string; label: string; note: string };

const links: NavItem[] = [
  { href: "/", label: "Today", note: "今日概览" },
  { href: "/inbox", label: "Inbox", note: "捕获与分析" },
  { href: "/hypotheses", label: "Hypotheses", note: "判断与证据" },
  { href: "/topics", label: "Topics", note: "研究主题" },
  { href: "/history", label: "History", note: "历史记录" },
  { href: "/settings", label: "Settings", note: "模型配置" },
];

/** Mobile keeps the four entries the workspace cannot do without. */
const mobileLinks: { href: string; label: string; glyph: string }[] = [
  { href: "/inbox", label: "Inbox", glyph: "＋" },
  { href: "/hypotheses", label: "Hypotheses", glyph: "◆" },
  { href: "/topics", label: "Topics", glyph: "▤" },
  { href: "/more", label: "More", glyph: "⋯" },
];

const secondaryRoutes = ["/more", "/history", "/settings"];

function isActive(path: string, href: string) {
  if (href === "/") return path === "/";
  if (href === "/inbox")
    return (
      path === "/inbox" ||
      path.startsWith("/news/") ||
      path.startsWith("/analyses/")
    );
  if (href === "/more")
    return secondaryRoutes.some(
      (route) => path === route || path.startsWith(`${route}/`),
    );
  return path === href || path.startsWith(`${href}/`);
}

export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;
    const register = () => {
      navigator.serviceWorker.register("/sw.js").catch(() => {});
    };
    register();
  }, []);
  return (
    <div className="workspace">
      <a className="skip-link" href="#main">
        跳到主要内容
      </a>
      <aside className="sidebar">
        <Link className="brand" href="/">
          ◈ SignalFrame<span>RESEARCH WORKSPACE</span>
        </Link>
        <nav aria-label="主导航">
          {links.map(({ href, label, note }) => (
            <Link
              className={isActive(path, href) ? "active" : ""}
              aria-current={isActive(path, href) ? "page" : undefined}
              key={href}
              href={href}
            >
              {label}
              <small>{note}</small>
            </Link>
          ))}
        </nav>
        <div className="sidebar-note">
          信息 → 事实 → 假设 → 验证
          <br />
          Local foundation · Mock ready
        </div>
      </aside>
      <div className="mobile-bar">
        <Link className="brand" href="/">
          ◈ SignalFrame
        </Link>
        <Link className="mobile-cta" href="/inbox">
          ＋ 分析
        </Link>
      </div>
      <main id="main" tabIndex={-1}>
        {children}
      </main>
      <nav className="bottom-nav" aria-label="手机导航">
        {mobileLinks.map(({ href, label, glyph }) => (
          <Link
            className={isActive(path, href) ? "active" : ""}
            aria-current={isActive(path, href) ? "page" : undefined}
            key={href}
            href={href}
          >
            <span className="nav-glyph" aria-hidden="true">
              {glyph}
            </span>
            <span className="nav-label">{label}</span>
          </Link>
        ))}
      </nav>
    </div>
  );
}
