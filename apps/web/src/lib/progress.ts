import type { Schema } from "./api";
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
export function progress(job: Schema<"AnalysisJob"> | null) {
  if (!job) return 0;
  if (job.status === "COMPLETED") return 100;
  const last = job.events.at(-1);
  return Math.max(
    0,
    Math.round(((steps.indexOf(last?.step ?? "") + 1) / steps.length) * 100),
  );
}
export function terminal(status: Schema<"JobStatus">) {
  return status === "COMPLETED" || status === "FAILED";
}
