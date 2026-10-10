#!/usr/bin/env python3
"""Out-of-process Wave 2B research-loop smoke against a packaged API.

Drives the real HTTP surface of the built jar: ingest determinism-safe text,
run the mock analysis, then create evidence, a prediction and a verification, and
finally read the hypothesis timeline back and check the version/event chain.
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8091"


def call(method, path, payload=None, expect=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"} if data else {},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        status = error.code
    if expect is not None and status != expect:
        raise SystemExit(f"FAIL {method} {path}: expected {expect}, got {status}: {body}")
    return status, (json.loads(body) if body else None)


def step(label, detail=""):
    print(f"  [ok] {label}{(' — ' + detail) if detail else ''}")


text = (
    "测试报道：一家企业宣布 AI 推理服务降价 30%，并警告供应链受限。"
    "公司同时披露单位毛利下降，原因是算力投入成本上升。"
)

print("Wave 2B out-of-process research loop")
status, news = call(
    "POST",
    "/api/v1/news",
    {"text": text, "title": "wave2b integration loop fixture"},
    expect=201,
)
news_id = news["id"]
step("POST /api/v1/news", f"news={news_id}")

status, job = call("POST", f"/api/v1/news/{news_id}/analyze", None, expect=202)
job_id = job["id"]
step("POST /api/v1/news/{id}/analyze", f"job={job_id}")

analysis_id = None
for _ in range(120):
    status, job = call("GET", f"/api/v1/analysis-jobs/{job_id}", expect=200)
    if job["status"] in ("COMPLETED", "FAILED"):
        analysis_id = job.get("analysisId")
        break
    time.sleep(0.5)
if job["status"] != "COMPLETED":
    raise SystemExit(f"FAIL analysis job ended {job['status']}: {job.get('error')}")
step("analysis job COMPLETED", f"analysis={analysis_id}")

status, hypotheses = call("GET", "/api/v1/hypotheses", expect=200)
mine = [h for h in hypotheses if h.get("id")]
if not mine:
    raise SystemExit("FAIL no hypothesis was produced")
hypothesis = mine[0]
hypothesis_id = hypothesis["id"]
step("GET /api/v1/hypotheses", f"{len(hypotheses)} hypothesis(es)")

status, timeline = call("GET", f"/api/v1/hypotheses/{hypothesis_id}/timeline", expect=200)
if timeline["version"] != 0 or len(timeline["items"]) != 1:
    raise SystemExit(f"FAIL initial timeline {timeline}")
step(
    "GET /api/v1/hypotheses/{id}/timeline",
    f"version=0 status={timeline['status']} items=1",
)

status, news_detail = call("GET", f"/api/v1/news/{news_id}", expect=200)
source_id = news_detail["source"]["id"]
step("GET /api/v1/news/{id}", f"source={source_id}")

status, evidence = call(
    "POST",
    f"/api/v1/hypotheses/{hypothesis_id}/evidence",
    {
        "sourceId": source_id,
        "stance": "SUPPORTS",
        "strength": 60,
        "reason": "an independent record corroborates the reported movement",
    },
    expect=201,
)
step("POST /evidence", f"evidence={evidence['id']} stance={evidence['stance']}")

status, predictions = call(
    "POST",
    f"/api/v1/hypotheses/{hypothesis_id}/predictions",
    {
        "statement": "the next filing reports a margin below the prior level",
        "observable": "the reported unit margin in the next filing",
        "expectedBy": "2027-01-01T00:00:00Z",
        "verificationCriteria": "confirmation requires a lower reported margin than the prior filing; rejection requires the same or higher",
        "whereToCheck": "the issuer's investor relations filings page",
        "basisFactRefs": [],
    },
    expect=201,
)
prediction_id = predictions["id"]
step("POST /predictions", f"prediction={prediction_id} status={predictions['status']}")

status, result = call(
    "POST",
    f"/api/v1/predictions/{prediction_id}/verification",
    {
        "operationId": str(uuid.uuid4()),
        "result": "CONFIRMED",
        "reason": "the filing reported a lower margin, inside the pre-registered boundary",
    },
    expect=200,
)
step(
    "POST /verification",
    f"applied={result['applied']} prediction={result['prediction']['status']} "
    f"hypothesis={result['hypothesisTransition']['status']}",
)

status, timeline = call("GET", f"/api/v1/hypotheses/{hypothesis_id}/timeline", expect=200)
if timeline["version"] != 2 or len(timeline["items"]) != 3:
    raise SystemExit(f"FAIL final timeline {timeline['version']} items={len(timeline['items'])}")
versions = []
for item in timeline["items"]:
    reason = item["reason"]
    marker = " | transition: "
    if marker not in reason:
        continue
    # The server joins the trailer with "; " and trims on read
    # (TransitionEventText), so strip both sides of every pair here too.
    trailer = {}
    for pair in reason.split(marker)[1].split(";"):
        if "=" in pair:
            key, value = pair.split("=", 1)
            trailer[key.strip()] = value.strip()
    versions.append((int(trailer["previousVersion"]), int(trailer["version"])))
if versions != [(0, 1), (1, 2)]:
    raise SystemExit(f"FAIL version chain {versions}")
step("final timeline", f"version=2 items=3 chain={versions}")

print("PASS: out-of-process research loop completed on the packaged API")
