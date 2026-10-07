import { describe, it, expect } from "vitest";
import {
  claimGlyph,
  claimLabel,
  directionArrow,
  directionLabel,
  extractionLabel,
  hypothesisStatusLabel,
  modelRunStatusLabel,
  stakeholderLabel,
  stakeholderOrder,
} from "./labels";

describe("contract vocabulary labels", () => {
  it("labels every claim type with a non-color cue", () => {
    for (const type of [
      "FACT",
      "INFERENCE",
      "HYPOTHESIS",
      "PREDICTION",
    ] as const) {
      expect(claimLabel[type].length).toBeGreaterThan(0);
      expect(claimGlyph[type].length).toBeGreaterThan(0);
    }
    expect(claimLabel.FACT).toBe("事实");
    expect(claimLabel.INFERENCE).toBe("推断");
  });

  it("labels variable direction and stakeholder stance", () => {
    expect(directionArrow.UP).not.toBe(directionArrow.DOWN);
    expect(directionLabel.UNKNOWN).toContain("未知");
    expect(stakeholderOrder[0]).toBe("BENEFITS");
    expect(stakeholderLabel.HARMED).toBe("受损方");
    expect(stakeholderOrder.map((d) => stakeholderLabel[d])).toEqual([
      "受益方",
      "受损方",
      "利弊兼有",
      "不确定",
    ]);
  });

  it("labels source, hypothesis and model-run state", () => {
    expect(extractionLabel.NEEDS_TEXT).toContain("正文");
    expect(hypothesisStatusLabel.OPEN).toBe("开放");
    expect(modelRunStatusLabel.FAILED).toBe("失败");
  });
});
