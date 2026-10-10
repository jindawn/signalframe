import { defineConfig, devices } from "@playwright/test";

/**
 * Smoke configuration.
 *
 * The smoke run must exercise THIS worktree's production build. The base URL
 * used to default to the shared dev port 3000 with no `webServer`, so
 * `npm run smoke` silently tested whatever server was already listening there —
 * including a dev server belonging to another checkout or an older revision.
 * Playwright now builds and starts this worktree's own production server on a
 * dedicated port and never reuses an existing one, so a stale process can no
 * longer satisfy the run. Set `WEB_BASE_URL` to target an externally managed
 * server instead (the `webServer` block is then skipped).
 *
 * `API_BASE_URL` is forwarded to the Next.js `/api` rewrite. The API under test
 * must be built from the current revision: the smoke assertions read the
 * ingestion reason code the API returns, so a stale API fails loudly instead of
 * silently degrading to generic copy.
 */
const externalBaseUrl = process.env.WEB_BASE_URL;
const webPort = Number(process.env.WEB_PORT ?? 3100);
const baseURL = externalBaseUrl ?? `http://127.0.0.1:${webPort}`;

export default defineConfig({
  testDir: "./tests",
  timeout: 30000,
  fullyParallel: false,
  use: {
    baseURL,
    trace: "retain-on-failure",
  },
  webServer: externalBaseUrl
    ? undefined
    : {
        command: `npm run build && npm run start -- --port ${webPort}`,
        url: `${baseURL}/inbox`,
        reuseExistingServer: false,
        timeout: 180_000,
        env: {
          API_BASE_URL: process.env.API_BASE_URL ?? "http://127.0.0.1:8080",
        },
      },
  projects: [
    { name: "desktop-chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile-chromium", use: { ...devices["Pixel 7"] } },
    { name: "mobile-webkit", use: { ...devices["iPhone 13"] } },
  ],
  reporter: [["list"]],
});
