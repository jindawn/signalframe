import { test, expect } from "@playwright/test";

/**
 * TASK-08 acceptance: 320px–430px phone layouts, keyboard access, safe-area
 * navigation, valid PNG icons, and a service worker that caches static install
 * assets only (no API/private research cache, no push, no offline-first).
 *
 * Manual iOS check (cannot be automated here): on a real device open the
 * deployed URL in Safari → Share → 添加到主屏幕 → launch from the home screen
 * and confirm the standalone window, the icon, and that the bottom navigation
 * clears the home-indicator safe area.
 */

// Every test in this file runs at a representative phone width (390px), on
// desktop Chromium, mobile Chromium and mobile WebKit alike.
test.use({ viewport: { width: 390, height: 780 } });

const STATIC_ASSETS = [
  "/manifest.webmanifest",
  "/icon-180.png",
  "/icon-192.png",
  "/icon-512.png",
];

test("manifest and icons satisfy basic installability", async ({
  page,
  request,
}) => {
  const response = await request.get("/manifest.webmanifest");
  expect(response.status()).toBe(200);
  const manifest = await response.json();
  expect(manifest.name).toBeTruthy();
  expect(manifest.short_name).toBeTruthy();
  expect(manifest.start_url).toBe("/");
  expect(manifest.scope).toBe("/");
  expect(manifest.display).toBe("standalone");
  expect(manifest.background_color).toMatch(/^#[0-9a-fA-F]{6}$/);
  expect(manifest.theme_color).toMatch(/^#[0-9a-fA-F]{6}$/);
  expect(manifest.icons.length).toBeGreaterThanOrEqual(2);

  const icons = manifest.icons as {
    src: string;
    sizes: string;
    type: string;
    purpose?: string;
  }[];
  for (const icon of icons) {
    expect(icon.type).toBe("image/png");
    const asset = await request.get(icon.src);
    expect(asset.status()).toBe(200);
    const bytes = await asset.body();
    // PNG magic number, then IHDR width/height must match the declared size.
    expect(bytes.subarray(0, 8).toString("hex")).toBe("89504e470d0a1a0a");
    const [width, height] = icon.sizes.split("x").map(Number);
    expect(bytes.readUInt32BE(16)).toBe(width);
    expect(bytes.readUInt32BE(20)).toBe(height);
  }
  expect(icons.some((icon) => (icon.purpose ?? "").includes("maskable"))).toBe(
    true,
  );

  await page.goto("/inbox");
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
    "href",
    /manifest\.webmanifest$/,
  );
  await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute(
    "href",
    /icon-180\.png$/,
  );
});

test("service worker caches static install assets only", async ({
  page,
  request,
}) => {
  const source = await (await request.get("/sw.js")).text();
  // No push, no background sync, no offline-first navigation fallback.
  expect(source).not.toMatch(/addEventListener\(\s*["'](push|sync|periodicsync)["']/);
  expect(source).not.toContain("NavigationRoute");
  // The research API is explicitly excluded from service worker control.
  expect(source).toContain('url.pathname.startsWith("/api/")');

  const readCache = () =>
    page.evaluate(async () => {
      const names = await caches.keys();
      const entries = await Promise.all(
        names.map(async (name) =>
          (await (await caches.open(name)).keys()).map(
            (cached) => new URL(cached.url).pathname,
          ),
        ),
      );
      return entries.flat();
    });

  await page.goto("/inbox");
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await expect.poll(readCache).toContain("/icon-192.png");

  const cached = await readCache();
  expect(cached.some((path) => path.startsWith("/api/"))).toBe(false);
  for (const path of cached) expect(STATIC_ASSETS).toContain(path);
});

test("390px phone layout stays single column with usable navigation", async ({
  page,
}) => {
  await page.goto("/inbox");
  await expect(page.getByRole("heading", { name: "Inbox" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "主导航" })).toBeHidden();

  const nav = page.getByRole("navigation", { name: "手机导航" });
  await expect(nav).toBeVisible();
  for (const label of ["Inbox", "Hypotheses", "Topics", "More"]) {
    await expect(nav.getByRole("link", { name: label })).toBeVisible();
  }

  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth + 1,
    ),
  ).toBe(false);

  const { navHeight, mainPaddingBottom } = await page.evaluate(() => {
    const navElement = document.querySelector(".bottom-nav");
    const mainElement = document.querySelector("#main");
    return {
      navHeight: navElement?.getBoundingClientRect().height ?? 0,
      mainPaddingBottom: mainElement
        ? Number.parseFloat(getComputedStyle(mainElement).paddingBottom)
        : 0,
    };
  });
  expect(navHeight).toBeGreaterThan(0);
  expect(mainPaddingBottom).toBeGreaterThanOrEqual(navHeight);

  const firstTarget = await nav
    .getByRole("link", { name: "Inbox" })
    .boundingBox();
  expect(firstTarget?.height ?? 0).toBeGreaterThanOrEqual(44);

  // A long unbroken URL must not widen the page.
  await page
    .getByLabel("新闻正文 / 补充文字")
    .fill(`https://example.com/${"a".repeat(300)}`);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth + 1,
    ),
  ).toBe(false);
});

test("keyboard access reaches the shell and the capture form", async ({
  page,
}) => {
  await page.goto("/inbox");
  await expect(page.locator("#main")).toHaveAttribute("tabindex", "-1");

  const skip = page.getByRole("link", { name: "跳到主要内容" });
  await skip.focus();
  await expect(skip).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/#main$/);

  await page.getByLabel("新闻 URL").focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("新闻正文 / 补充文字")).toBeFocused();

  const analyze = page.getByRole("button", { name: "Analyze →" });
  await expect(analyze).toBeDisabled();
  await page.keyboard.type("键盘导航测试文本");
  await expect(analyze).toBeEnabled();
  await analyze.focus();
  await expect(analyze).toBeFocused();
});
