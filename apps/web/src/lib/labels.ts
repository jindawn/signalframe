import type { Schema } from "./api";

/**
 * Human-readable vocabulary for contract enums.
 *
 * The contract enum value is always rendered next to the label so a reader can
 * see the canonical claim type without relying on color alone.
 */
export const claimLabel: Record<Schema<"ClaimType">, string> = {
  FACT: "事实",
  INFERENCE: "推断",
  HYPOTHESIS: "假设",
  PREDICTION: "预测",
};

export const claimGlyph: Record<Schema<"ClaimType">, string> = {
  FACT: "▣",
  INFERENCE: "◇",
  HYPOTHESIS: "◆",
  PREDICTION: "△",
};

export const directionArrow: Record<
  Schema<"Variable">["direction"],
  string
> = {
  UP: "↑",
  DOWN: "↓",
  UNCHANGED: "→",
  UNKNOWN: "?",
};

export const directionLabel: Record<
  Schema<"Variable">["direction"],
  string
> = {
  UP: "上升",
  DOWN: "下降",
  UNCHANGED: "基本不变",
  UNKNOWN: "方向未知",
};

export const stakeholderLabel: Record<
  Schema<"StakeholderImpact">["direction"],
  string
> = {
  BENEFITS: "受益方",
  HARMED: "受损方",
  MIXED: "利弊兼有",
  UNKNOWN: "不确定",
};

export const stakeholderOrder: Schema<"StakeholderImpact">["direction"][] = [
  "BENEFITS",
  "HARMED",
  "MIXED",
  "UNKNOWN",
];

export const hypothesisStatusLabel: Record<
  Schema<"Hypothesis">["status"],
  string
> = {
  OPEN: "开放",
  SUPPORTED: "已有支持",
  CHALLENGED: "受到挑战",
  REJECTED: "已否定",
  ARCHIVED: "已归档",
};

export const extractionLabel: Record<
  Schema<"Source">["extractionStatus"],
  string
> = {
  PASTED: "手动粘贴正文",
  EXTRACTED: "网页抽取正文",
  NEEDS_TEXT: "需要粘贴正文",
};

export const modelRunStatusLabel: Record<
  Schema<"ModelRun">["status"],
  string
> = {
  SUCCEEDED: "成功",
  FAILED: "失败",
};
