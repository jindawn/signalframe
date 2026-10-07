import { describe, it, expect } from "vitest";
import { progress, terminal } from "./progress";
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
});
