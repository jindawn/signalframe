"use client";
import * as Progress from "@radix-ui/react-progress";
import type { Schema } from "@/lib/api";
import {
  progress,
  stageState,
  statusSequence,
  statusText,
  terminal,
} from "@/lib/progress";

const marks: Record<string, string> = {
  done: "✓",
  active: "●",
  pending: "○",
};

/**
 * Durable job progress. The canonical status is rendered verbatim in a single
 * `role="status"` node; the Chinese label lives beside it so the contract value
 * stays visible and copyable for debugging.
 */
export function JobProgress({ job }: { job: Schema<"AnalysisJob"> }) {
  const value = progress(job);
  const meta = statusText[job.status];
  return (
    <section className="panel job-panel" aria-label="分析任务进度">
      <div className="row job-head">
        <div className="job-title">
          <h2>Analysis Job · 分析进度</h2>
          <p className="muted breakable">任务 {job.id}</p>
        </div>
        <span className="status-badge">
          <span role="status" className="status-code">
            {job.status}
          </span>
          <span className="status-label">{meta.label}</span>
        </span>
      </div>
      <p className="job-detail" aria-live="polite">
        {terminal(job.status) ? meta.detail : `${meta.label}：${meta.detail}`}
      </p>
      <Progress.Root
        className="progress"
        value={value}
        aria-label={`分析进度 ${value}%`}
      >
        <Progress.Indicator
          className="progress-fill"
          style={{ transform: `translateX(-${100 - value}%)` }}
        />
      </Progress.Root>
      <p className="progress-value muted">{value}%</p>
      <ol className="status-track">
        {statusSequence.map((stage) => {
          const state = stageState(job.status, stage);
          return (
            <li className={`stage stage-${state}`} key={stage}>
              <span className="stage-mark" aria-hidden="true">
                {marks[state]}
              </span>
              <span className="stage-name">{stage}</span>
              <span className="stage-label muted">
                {statusText[stage].label}
              </span>
            </li>
          );
        })}
      </ol>
      {job.events.length > 0 && (
        <details className="job-events">
          <summary>步骤日志 · {job.events.length} 条</summary>
          <ol className="pipeline">
            {job.events.map((event) => (
              <li key={event.sequence}>
                <span>{event.step}</span>
                <small>
                  {statusText[event.status].label} · {event.message}
                </small>
              </li>
            ))}
          </ol>
        </details>
      )}
    </section>
  );
}
