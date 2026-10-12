#!/usr/bin/env node
/**
 * API smoke for the integrated revision (TASK-10 owned artifact).
 *
 * Exercises the durable text → job → analysis → provenance → event-stream path
 * against a RUNNING API, the same way `scripts/smoke-web.sh` exercises the
 * browser path. Unlike the browser smoke, this needs no Chromium and no built
 * frontend, so it can gate a backend-only change.
 *
 * Usage:
 *   node scripts/smoke-api.mjs [--base-url http://127.0.0.1:8080]
 *
 * The base URL also comes from API_BASE_URL. The script prints one line per
 * check and a JSON evidence summary, and exits non-zero on the first failure.
 * It never prints response bodies that could contain operator data beyond the
 * identifiers it records.
 */

const flag = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1]
    ? process.argv[index + 1]
    : fallback;
};

const baseUrl = (
  process.env.API_BASE_URL ?? flag("--base-url", "http://127.0.0.1:8080")
).replace(/\/+$/, "");

const evidence = {
  baseUrl,
  startedAt: new Date().toISOString(),
  steps: [],
};

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function step(name, detail = {}) {
  evidence.steps.push({ name, ...detail });
  console.log(`ok   ${name}`);
}

async function request(path, init = {}) {
  const response = await fetch(baseUrl + path, {
    ...init,
    headers: { "X-Request-ID": "smoke-api", ...(init.headers ?? {}) },
    signal: init.signal ?? AbortSignal.timeout(30000),
  });
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = null;
  }
  return { response, payload, text };
}

async function pollJob(jobId) {
  const deadline = Date.now() + 60000;
  let job = (await request(`/api/v1/analysis-jobs/${jobId}`)).payload;
  while (!["COMPLETED", "FAILED"].includes(job?.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    job = (await request(`/api/v1/analysis-jobs/${jobId}`)).payload;
  }
  assert(job, `job ${jobId} was never readable`);
  return job;
}

function parseFrames(raw) {
  return raw
    .replace(/\r\n/g, "\n")
    .split("\n\n")
    .map((block) => {
      const frame = { id: null, name: null, data: null };
      for (const line of block.split("\n")) {
        if (line.startsWith("id:")) frame.id = line.slice(3).trim();
        else if (line.startsWith("event:")) frame.name = line.slice(6).trim();
        else if (line.startsWith("data:")) frame.data = line.slice(5).trim();
      }
      return frame;
    })
    .filter((frame) => frame.data);
}

async function main() {
  const health = await request("/actuator/health");
  assert(
    health.response.status === 200,
    `API is not healthy at ${baseUrl}: ${health.response.status}`,
  );
  step("api is reachable", { baseUrl });

  const text =
    "冒烟测试：某公司宣布 AI 推理服务降价，原始公告与账单仍需独立核实。";
  const created = await request("/api/v1/news", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  assert(
    created.response.status === 201,
    `POST /api/v1/news failed: ${created.response.status}`,
  );
  const news = created.payload;
  assert(
    news?.source?.extractionStatus === "PASTED",
    `pasted text must persist as PASTED, got ${news?.source?.extractionStatus}`,
  );
  step("persisted pasted source", {
    newsId: news.id,
    extractionStatus: news.source.extractionStatus,
  });

  const started = await request(`/api/v1/news/${news.id}/analyze`, {
    method: "POST",
  });
  assert(
    started.response.status === 202,
    `POST /analyze failed: ${started.response.status}`,
  );
  const job = await pollJob(started.payload.id);
  assert(
    job.status === "COMPLETED",
    `job ended ${job.status}: ${job.error ?? "no error recorded"}`,
  );
  assert(
    typeof job.analysisId === "string" && job.analysisId.length > 0,
    "a completed job must carry an analysisId",
  );
  // job_events.sequence is a global bigserial, so per-job frames are ordered but
  // do not start at 1: assert monotonicity, not arithmetic from zero.
  job.events.forEach((event, index) => {
    if (index === 0) return;
    assert(
      event.sequence > job.events[index - 1].sequence,
      "job event sequences must be strictly increasing",
    );
  });
  step("durable job completed", { jobId: job.id, events: job.events.length });

  const analysis = await request(`/api/v1/analyses/${job.analysisId}`);
  assert(
    analysis.response.status === 200,
    `GET /analyses failed: ${analysis.response.status}`,
  );
  assert(
    analysis.payload.result.demo === true,
    "the smoke profile is expected to run the mock provider",
  );
  assert(
    analysis.payload.result.facts[0].sourceRefs[0].sourceId ===
      news.source.id,
    "facts must cite the persisted source",
  );
  assert(
    analysis.payload.result.confidenceAssessment.isProbability === false,
    "confidence is a 0-100 judgment, not a probability",
  );
  step("analysis carries provenance", {
    analysisId: job.analysisId,
    facts: analysis.payload.result.facts.length,
  });

  // A reconnecting client: the cursor is the first persisted frame, already
  // delivered. The stream must replay only the unseen frames and must still
  // close on the terminal frame.
  const cursor = job.events[0].sequence;
  const stream = await fetch(
    baseUrl + `/api/v1/analysis-jobs/${job.id}/events`,
    {
      headers: {
        Accept: "text/event-stream",
        "Last-Event-ID": String(cursor),
      },
      signal: AbortSignal.timeout(60000),
    },
  );
  assert(
    stream.status === 200,
    `GET /events failed: ${stream.status}`,
  );
  const frames = parseFrames(await stream.text());
  assert(frames.length > 0, "the event stream must replay the job history");
  for (const frame of frames)
    assert(
      Number(frame.id) > cursor,
      `frame ${frame.id} was replayed although the cursor was ${cursor}`,
    );
  const lastFrame = JSON.parse(frames.at(-1).data);
  assert(
    lastFrame.status === "COMPLETED",
    `the stream must end on a terminal frame, got ${lastFrame.status}`,
  );
  step("sse replay honours Last-Event-ID", {
    cursor,
    frames: frames.length,
    terminal: lastFrame.status,
  });

  const runs = await request(`/api/v1/model-runs?jobId=${job.id}`);
  assert(
    runs.response.status === 200,
    `GET /model-runs failed: ${runs.response.status}`,
  );
  assert(
    runs.text.includes("SUCCEEDED"),
    "the audit trail must record the successful model run",
  );
  const lowered = runs.text.toLowerCase();
  for (const forbidden of ["apikey", "authorization", "bearer "])
    assert(
      !lowered.includes(forbidden),
      `the audit trail must not carry "${forbidden}"`,
    );
  step("audit trail is credential-free", {
    modelRuns: Array.isArray(runs.payload) ? runs.payload.length : null,
  });

  evidence.completedAt = new Date().toISOString();
  evidence.passed = true;
  console.log("");
  console.log(JSON.stringify(evidence, null, 2));
}

main().catch((error) => {
  evidence.completedAt = new Date().toISOString();
  evidence.passed = false;
  evidence.error = error.message;
  console.error(`fail ${error.message}`);
  console.error(JSON.stringify(evidence, null, 2));
  process.exitCode = 1;
});
