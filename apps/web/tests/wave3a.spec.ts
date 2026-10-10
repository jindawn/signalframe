import { test, expect } from "@playwright/test";
import path from "node:path";

/**
 * Wave 3A web/mobile gate.
 *
 * The foundation smoke proves the loop closes. This spec proves the *result* is
 * actually readable research: every protocol section the snapshot carries is
 * rendered on desktop and on a phone, long Chinese spans never cause horizontal
 * overflow, raw JSON is never dumped at the reader, epistemic labels stay
 * separate, UNKNOWN is disclosed instead of being dressed up as a conclusion, and
 * the effective LIVE/MOCK mode is shown without ever echoing a credential value.
 *
 * It targets whatever API `API_BASE_URL` points at, which must be the API built
 * from this revision.
 */

const NEWS =
  "文件编号𠮷A-2026。某公司发布公告称，其与一家海外芯片厂商的专利许可协议将于本季度到期，" +
  "双方已就续约费率启动谈判。公告称，若续约费率上调超过10%，该公司预计将上调其终端产品售价。" +
  "另一家研究机构指出，谈判结果可能影响未来两年的行业授权成本。";

/** Every section AnalysisView must render; the task names nine of them explicitly. */
const SECTIONS = [
  "Summary · 摘要",
  "Facts · 来源事实",
  "Key Variables · 关键变量",
  "Mechanism · 影响机制",
  "Stakeholders · 利益相关者",
  "First-order Effects · 一级影响",
  "Second-order Effects · 二级影响",
  "Hypotheses · 主要假设",
  "Alternative Explanations · 替代解释",
  "Counter Arguments · 反方论证",
  "Falsification · 可证伪条件",
  "Corroborating Signals · 旁证",
  "Verification Plan · 验证计划",
  "Unknowns · 未知事项",
  "Model Runs · 调用审计",
];

/** The placeholder the API under test is started with; it is not a real secret. */
const PLACEHOLDER_KEY = "ollama";

test.describe.configure({ mode: "serial" });

test("a real analysis result renders as readable research, not JSON", async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));

  await page.goto("/inbox");
  await page
    .getByLabel("新闻正文 / 补充文字")
    .fill(NEWS);
  await page.getByRole("button", { name: "Analyze →" }).click();
  await expect(page.getByRole("status")).toHaveText("COMPLETED", {
    timeout: 30000,
  });
  await expect(page.getByRole("region", { name: "分析结果" })).toBeVisible();

  for (const title of SECTIONS) {
    // Substring matching, not exact: the falsification section's CSS glyph is part
    // of that heading's accessible name, so its exact name is "⚑ Falsification · …".
    await expect(
      page.getByRole("heading", { name: title }),
      `${title} must be rendered`,
    ).toBeVisible();
  }

  // Epistemic types stay separate and provenance is reachable.
  await expect(page.getByText("FACT · 事实", { exact: false }).first()).toBeVisible();
  await expect(
    page.getByText("HYPOTHESIS · 假设", { exact: false }).first(),
  ).toBeVisible();
  await expect(page.getByText("原文依据", { exact: false }).first()).toBeVisible();

  // Offline output discloses itself, and confidence is never a probability.
  await expect(
    page.getByText("Mock 演示结果 · 未经独立核实", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByText("置信度是判断尺度，不是统计概率", { exact: false }),
  ).toBeVisible();

  // The reader gets prose, never the wire format.
  const text = await page.locator("body").innerText();
  for (const marker of [
    '"sourceRefs"',
    '"confidenceAssessment"',
    '"isProbability"',
    '"factRefs"',
    '"falsificationConditions"',
  ]) {
    expect(text, `${marker} must never be dumped into the page`).not.toContain(
      marker,
    );
  }

  // Long Chinese spans must not create a horizontal scrollbar.
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth > window.innerWidth,
    body: document.body.scrollWidth > window.innerWidth,
  }));
  expect(overflow.document).toBe(false);
  expect(overflow.body).toBe(false);

  if (testInfo.project.name === "desktop-chromium") {
    await page.screenshot({
      path: path.resolve("../../docs/evidence/wave3a-desktop.png"),
      fullPage: true,
    });
  }
  if (testInfo.project.name === "mobile-chromium") {
    await page.screenshot({
      path: path.resolve("../../docs/evidence/wave3a-mobile.png"),
      fullPage: true,
    });
  }
  expect(errors).toEqual([]);
});

test("LIVE and MOCK are distinguished and the credential value never reaches the page", async ({
  page,
  request,
}) => {
  const profile = (provider: string, model: string, baseUrl: string) => ({
    provider,
    model,
    baseUrl,
    apiKeyEnv: "AI_FAST_API_KEY",
    temperature: 0.2,
    maxTokens: 4096,
    timeout: 45,
    structuredOutput: true,
    toolCalling: false,
    enabled: true,
  });
  // The DEMO deliberately does not touch `analysis.fast`: that profile serves
  // SYNTHESIS, and another Playwright project may be running an analysis at the
  // same moment, so switching it to a real provider would turn a rendering test
  // into a live model call. `analysis.deep` is not on the SYNTHESIS route, so the
  // page can be shown a LIVE profile while the analysis path stays MOCK.
  const mockProfile = profile("mock", "mock-v1", "https://api.deepseek.com");
  const liveProfile = profile(
    "openai-compatible",
    "gemma3:1b",
    "http://127.0.0.1:11434/v1",
  );
  const deep = (data: object) =>
    request.put("/api/v1/settings/model-profiles/analysis.deep", { data });

  try {
    await deep(mockProfile);
    await request.put("/api/v1/settings/model-profiles/analysis.fast", {
      data: mockProfile,
    });
    await page.goto("/settings");
    await expect(
      page.getByRole("heading", { name: "Model Settings" }),
    ).toBeVisible();
    await expect(page.getByText("MOCK", { exact: true }).first()).toBeVisible();

    // One profile whose credential resolves reports LIVE while the other stays
    // MOCK: the mode is per profile, and the page must not collapse them.
    const updated = await deep(liveProfile);
    expect(updated.status()).toBe(200);
    await page.reload();
    await expect(page.getByText("LIVE", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("MOCK", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("gemma3:1b", { exact: true })).toBeVisible();

    // The settings page reports the variable *name*, never a key value.
    const body = await page.locator("body").innerText();
    expect(body).toContain("AI_FAST_API_KEY");
    expect(body).not.toContain(PLACEHOLDER_KEY);
  } finally {
    // Profile PUT is process-global; never leave a live provider behind.
    await deep(mockProfile);
  }
});
