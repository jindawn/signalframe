import { test, expect } from "@playwright/test";

/**
 * TASK-10 integrated-revision browser coverage.
 *
 * These specs complement `foundation.spec.ts` (which covers the happy path and
 * the blocked-URL fallback) with the three failure/continuity paths the
 * acceptance criteria call out: a real extraction failure that recovers through
 * pasted text, a page reload that restores the durable job and reopens its event
 * stream, and an overloaded queue that is reported as a retryable error.
 *
 * Every assertion that concerns persistence reads the real API. The one
 * exception is the queue-overload surface, which fulfils the analyze request with
 * the exact 503 `QUEUE_FULL` contract body: saturating the real 2-worker queue
 * from a browser is not deterministic, so the server-side semantics are pinned
 * by `QueueOverloadIntegrationTest` and the UI's handling of that response is
 * pinned here.
 */

test("unreachable URL extraction recovers through pasted text", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (event) => errors.push(event.message));
  await page.goto("/inbox");
  await page
    .getByLabel("新闻 URL")
    .fill("http://signalframe-unreachable.invalid/article");
  await page.getByRole("button", { name: "Analyze →" }).click();

  const recovery = page
    .getByRole("alert")
    .filter({ hasText: "网页提取失败" });
  await expect(recovery).toBeVisible({ timeout: 30000 });
  await expect(recovery).toContainText("NETWORK_FAILURE");
  await expect(recovery).toContainText("粘贴正文");

  // Nothing was extracted, so no content may be presented as if it had been.
  const captured = page.locator("details.captured");
  await expect(captured).toContainText("这段输入没有正文文本。");

  const pasted = "补充正文：用于验证抽取失败后仍可继续分析的测试文本。";
  await page.getByLabel("新闻正文 / 补充文字").fill(pasted);
  await page.getByRole("button", { name: "Analyze →" }).click();
  await expect(page.getByRole("status")).toHaveText("COMPLETED", {
    timeout: 40000,
  });
  await expect(page.getByRole("region", { name: "分析结果" })).toBeVisible();
  await expect(page.locator("details.captured")).toContainText(pasted);
  expect(errors).toEqual([]);
});

test("a reload mid-analysis restores the durable job and reopens the stream", async ({
  page,
}) => {
  const streamRequests: string[] = [];
  page.on("request", (request) => {
    if (/\/api\/v1\/analysis-jobs\/[^/]+\/events$/.test(request.url()))
      streamRequests.push(request.url());
  });

  await page.goto("/inbox");
  await page
    .getByLabel("新闻正文 / 补充文字")
    .fill("重连测试：页面刷新后必须恢复未完成的分析任务。");
  await page.getByRole("button", { name: "Analyze →" }).click();

  // The job id is registered as active before the analysis finishes; that
  // registration is what makes recovery across a refresh possible.
  await expect
    .poll(() => streamRequests.length, { timeout: 20000 })
    .toBeGreaterThanOrEqual(1);
  const jobId = await page.evaluate(() =>
    window.localStorage.getItem("signalframe.activeJob"),
  );
  expect(jobId).toBeTruthy();

  // Reload with the job pinned as active: this is exactly the state the app
  // restores from after a refresh or a dropped event stream.
  await page.evaluate(
    (id) => window.localStorage.setItem("signalframe.activeJob", id as string),
    jobId,
  );
  await page.reload();

  await expect(
    page.getByRole("heading", { name: "Inbox", exact: true }),
  ).toBeVisible();
  await expect
    .poll(() => streamRequests.length, { timeout: 20000 })
    .toBeGreaterThanOrEqual(2);

  // The restored page reconnects to the same durable job and finishes it.
  await expect(page.getByRole("status")).toHaveText("COMPLETED", {
    timeout: 40000,
  });
  await expect(page.getByRole("region", { name: "分析结果" })).toBeVisible();
});

test.describe("queue overload surface", () => {
  /**
   * The app registers a service worker, and on WebKit the analyze POST reached
   * the real API despite `page.route`: a worker-issued fetch is not routed. The
   * subject here is the UI's handling of the contract error, not offline
   * behaviour (pwa.spec.ts owns that), so the worker is blocked to keep the
   * stubbed response deterministic on all three projects.
   */
  test.use({ serviceWorkers: "block" });

  test("queue overload is reported as a retryable failure", async ({ page }) => {
    let stubbed = 0;
    await page.goto("/inbox");
    await page.route("**/api/v1/news/*/analyze", (route) => {
      stubbed += 1;
      return route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: "QUEUE_FULL",
          message: "分析队列已满，请稍后重试。",
          requestId: "task10-overload",
        }),
      });
    });

    await page.getByLabel("新闻正文 / 补充文字").fill("队列过载 UI 测试正文");
    await page.getByRole("button", { name: "Analyze →" }).click();

    // Guard the guard: if interception silently stops matching, fail here rather
    // than reporting a UI bug that is really a test-harness bug.
    await expect.poll(() => stubbed).toBeGreaterThanOrEqual(1);
    await expect(
      page.getByRole("alert").filter({ hasText: "分析队列已满" }),
    ).toBeVisible();
    // The failure is recoverable: the form is usable again and no job panel was
    // left behind claiming progress.
    await expect(page.getByRole("button", { name: "Analyze →" })).toBeEnabled();
    await expect(page.getByRole("status")).toHaveCount(0);
  });
});
