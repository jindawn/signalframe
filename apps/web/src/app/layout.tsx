import type { Metadata, Viewport } from "next";
import { Shell } from "@/components/Shell";
import "./globals.css";
export const metadata: Metadata = {
  title: "SignalFrame · Research Workspace",
  description: "把新闻转化为可验证的世界模型",
  manifest: "/manifest.webmanifest",
  appleWebApp: {
    capable: true,
    statusBarStyle: "default",
    title: "SignalFrame",
  },
};
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#164c43",
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN">
      <body>
        <Shell>{children}</Shell>
      </body>
    </html>
  );
}
