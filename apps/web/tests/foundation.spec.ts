import { test, expect } from "@playwright/test";
import path from "node:path";
test("text → durable job → mock analysis → provenance → hypothesis timeline", async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/inbox");
  await expect(
    page.getByRole("heading", { name: "Inbox", exact: true }),
  ).toBeVisible();
  await page
    .getByLabel("新闻正文 / 补充文字")
    .fill(
      "测试报道：一家企业宣布 AI 推理服务降价，需进一步核对原始公告和实际账单。",
    );
  await page.getByRole("button", { name: "Analyze →" }).click();
  await expect(page.getByRole("status")).toHaveText("COMPLETED", {
    timeout: 20000,
  });
  await expect(page.getByRole("region", { name: "分析结果" })).toBeVisible();
  await expect(
    page.getByText("Mock 演示结果 · 未经独立核实", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Model Runs · 调用审计" }),
  ).toBeVisible();
  await page.getByText("原文依据", { exact: false }).first().click();
  await expect(
    page.getByText("Source ", { exact: false }).first(),
  ).toBeVisible();
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  expect(overflow).toBe(false);
  if (testInfo.project.name === "mobile-chromium")
    await page.screenshot({
      path: path.resolve("../../docs/evidence/mobile.png"),
      fullPage: true,
    });
  if (testInfo.project.name === "desktop-chromium")
    await page.screenshot({
      path: path.resolve("../../docs/evidence/desktop.png"),
      fullPage: true,
    });
  await page
    .getByRole("link", { name: "待验证：报道中的变化是否持续 →" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Timeline · 判断演进" }),
  ).toBeVisible();
  await expect(page.getByText("CREATED", { exact: true })).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Timeline · 判断演进" }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
test("URL failure allows pasted-text continuation and static-only PWA", async ({
  page,
  request,
}) => {
  await page.goto("/inbox");
  await page.getByLabel("新闻 URL").fill("http://127.0.0.1/private");
  await page.getByRole("button", { name: "Analyze →" }).click();
  // A blocked URL must surface as one recoverable alert region: the reason code,
  // the blocked category and the paste-text fallback. The region is a
  // <section role="alert"> (heading + diagnostic + recovery actions), so assert
  // on the accessible role rather than on a paragraph tag.
  const recovery = page
    .getByRole("alert")
    .filter({ hasText: "网页提取失败" });
  await expect(recovery).toBeVisible();
  await expect(recovery).toContainText("UNSAFE_DESTINATION");
  await expect(recovery).toContainText("访问被阻止");
  await expect(recovery).toContainText("粘贴正文");
  // The private address was refused, so no private content may be captured.
  const captured = page.locator("details.captured");
  await expect(captured).toContainText("需要粘贴正文");
  await expect(captured).toContainText("这段输入没有正文文本。");
  await page
    .getByLabel("新闻正文 / 补充文字")
    .fill("补充新闻原文：这是一段用于验证 URL 降级后可以继续分析的测试文本。");
  await page.getByRole("button", { name: "Analyze →" }).click();
  await expect(page.getByRole("status")).toHaveText("COMPLETED", {
    timeout: 20000,
  });
  const manifest = await (await request.get("/manifest.webmanifest")).json();
  expect(manifest.display).toBe("standalone");
  expect(manifest.icons).toHaveLength(2);
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  const cached = await page.evaluate(async () => {
    const names = await caches.keys();
    const all = await Promise.all(
      names.map(async (n) =>
        (await (await caches.open(n)).keys()).map(
          (r) => new URL(r.url).pathname,
        ),
      ),
    );
    return all.flat();
  });
  expect(cached).toContain("/icon-192.png");
  expect(cached.some((x) => x.startsWith("/api/"))).toBe(false);
});
