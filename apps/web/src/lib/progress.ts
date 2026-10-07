import type { Schema } from "./api";

/** Pipeline step names emitted by the backend `AnalysisStep` sequence. */
export const steps = [
  "NormalizeInput",
  "ExtractFacts",
  "ClassifyDomain",
  "ScoreNews",
  "ExtractVariables",
  "AnalyzeMechanism",
  "AnalyzeStakeholders",
  "GenerateHypotheses",
  "GenerateCounterArguments",
  "GenerateFalsificationConditions",
  "GenerateVerificationPlan",
  "GenerateCorroboratingSignals",
  "SynthesizeAnalysis",
  "Persist",
];

export type JobStatus = Schema<"JobStatus">;

/** Contract status ordering; COMPLETED is the success terminal. */
export const statusSequence: JobStatus[] = [
  "QUEUED",
  "NORMALIZING",
  "EXTRACTING_FACTS",
  "ANALYZING",
  "GENERATING_HYPOTHESES",
  "VERIFYING",
  "SYNTHESIZING",
  "COMPLETED",
];

export const statusText: Record<JobStatus, { label: string; detail: string }> =
  {
    QUEUED: { label: "排队中", detail: "任务已创建，等待执行。" },
    NORMALIZING: { label: "标准化输入", detail: "清理并规范化输入文本。" },
    EXTRACTING_FACTS: {
      label: "抽取事实",
      detail: "定位可引用的原文事实。",
    },
    ANALYZING: {
      label: "分析中",
      detail: "识别领域、价值与核心变量。",
    },
    GENERATING_HYPOTHESES: {
      label: "生成假设",
      detail: "形成可证伪的假设与反方论证。",
    },
    VERIFYING: {
      label: "验证准备",
      detail: "生成证伪条件、验证计划与旁证。",
    },
    SYNTHESIZING: { label: "综合结论", detail: "汇总结构化分析并保存快照。" },
    COMPLETED: { label: "已完成", detail: "分析结果已保存。" },
    FAILED: { label: "失败", detail: "任务未完成，可以重试。" },
  };

export function progress(job: Schema<"AnalysisJob"> | null) {
  if (!job) return 0;
  if (job.status === "COMPLETED") return 100;
  const last = job.events.at(-1);
  return Math.max(
    0,
    Math.round(((steps.indexOf(last?.step ?? "") + 1) / steps.length) * 100),
  );
}

export function terminal(status: JobStatus) {
  return status === "COMPLETED" || status === "FAILED";
}

export type StageState = "done" | "active" | "pending";

/**
 * Position of a status within the pipeline. FAILED has no position: it can
 * happen at any stage and must never be rendered as forward progress.
 */
export function statusIndex(status: JobStatus) {
  return status === "FAILED" ? -1 : statusSequence.indexOf(status);
}

export function stageState(current: JobStatus, stage: JobStatus): StageState {
  const c = statusIndex(current);
  const s = statusIndex(stage);
  if (c < 0 || s < 0) return "pending";
  if (s < c) return "done";
  if (s === c) return current === "COMPLETED" ? "done" : "active";
  return "pending";
}

/** Human-facing note for the current status. */
export function statusDetail(status: JobStatus) {
  return statusText[status]?.detail ?? "正在处理。";
}
