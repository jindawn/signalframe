import { describe, it, expect } from "vitest";
import {
  progress,
  terminal,
  stageState,
  statusDetail,
  statusSequence,
  statusText,
} from "./progress";
import type { Schema } from "./api";
describe("durable job progress", () => {
  it("uses persisted stages", () => {
    const job: Schema<"AnalysisJob"> = {
      id: "x",
      newsId: "y",
      status: "EXTRACTING_FACTS",
      analysisId: null,
      error: null,
      correlationId: "r",
      createdAt: "",
      updatedAt: "",
      events: [
        {
          sequence: 2,
          status: "EXTRACTING_FACTS",
          step: "ExtractFacts",
          message: "",
          at: "",
        },
      ],
    };
    expect(progress(job)).toBe(14);
    expect(progress({ ...job, status: "COMPLETED" })).toBe(100);
    expect(terminal("FAILED")).toBe(true);
    expect(terminal("VERIFYING")).toBe(false);
  });

  it("starts at zero before the first durable event", () => {
    const job: Schema<"AnalysisJob"> = {
      id: "x",
      newsId: "y",
      status: "QUEUED",
      analysisId: null,
      error: null,
      correlationId: "r",
      createdAt: "",
      updatedAt: "",
      events: [],
    };
    expect(progress(job)).toBe(0);
    expect(stageState(job.status, "QUEUED")).toBe("active");
    expect(stageState(job.status, "NORMALIZING")).toBe("pending");
  });

  it("marks earlier stages done and the current stage active", () => {
    expect(stageState("ANALYZING", "QUEUED")).toBe("done");
    expect(stageState("ANALYZING", "EXTRACTING_FACTS")).toBe("done");
    expect(stageState("ANALYZING", "ANALYZING")).toBe("active");
    expect(stageState("ANALYZING", "SYNTHESIZING")).toBe("pending");
  });

  it("does not treat FAILED as forward progress", () => {
    expect(stageState("FAILED", "QUEUED")).toBe("pending");
    expect(stageState("FAILED", "SYNTHESIZING")).toBe("pending");
    expect(stageState("FAILED", "FAILED")).toBe("pending");
  });

  it("keeps every contract status describable", () => {
    for (const status of [
      ...statusSequence,
      "FAILED" as const,
    ] satisfies Schema<"JobStatus">[]) {
      expect(statusText[status].label.length).toBeGreaterThan(0);
      expect(statusDetail(status).length).toBeGreaterThan(0);
    }
    expect(statusSequence).toHaveLength(8);
  });
});
