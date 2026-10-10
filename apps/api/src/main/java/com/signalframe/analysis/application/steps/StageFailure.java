package com.signalframe.analysis.application.steps;

/**
 * A bounded, classified pipeline-stage failure.
 *
 * <p>The protocol requires the pipeline to tell provider failures, timeouts,
 * malformed output, schema violations, missing mandatory artifacts and
 * unsupported stage results apart, and to fail the job explicitly instead of
 * persisting a snapshot that only looks complete (ANALYSIS_PROTOCOL_V0_1 PR-18,
 * §4). The classification is vendor-neutral: the message never contains a
 * provider name, a credential, a raw prompt or raw model output.
 */
public final class StageFailure extends RuntimeException {

  public enum Kind {
    /** The provider could not serve the call (transport, quota, auth, config). */
    PROVIDER_FAILURE,
    /** The provider did not answer inside the configured bound. */
    TIMEOUT,
    /** The response was not decodable as the stage's typed artifact. */
    MALFORMED_OUTPUT,
    /** The response decoded but violated the stage schema or epistemic rules. */
    SCHEMA_INVALID,
    /** A mandatory upstream artifact is absent, so the protocol forbids output. */
    MISSING_ARTIFACT,
    /** The stage produced a result the protocol cannot accept (EP-01/PR-05). */
    UNSUPPORTED_RESULT,
    /** Gate B/C/D/E rejected the assembled snapshot. */
    VALIDATION_FAILED,
  }

  private final String stage;
  private final Kind kind;
  private final String detail;

  public StageFailure(String stage, Kind kind, String detail) {
    super(kind.name() + " in " + stage + ": " + redact(detail));
    this.stage = stage;
    this.kind = kind;
    this.detail = redact(detail);
  }

  public String stage() {
    return stage;
  }

  public Kind kind() {
    return kind;
  }

  public String detail() {
    return detail;
  }

  /** Operator-facing, protocol-level explanation; safe to persist on the job. */
  public String jobMessage() {
    String boundary = "（阶段 " + stage + "）";
    return switch (kind) {
      case TIMEOUT -> "分析失败：模型响应超时" + boundary + "。请稍后重试。";
      case PROVIDER_FAILURE -> "分析失败：模型服务不可用" + boundary + "。请稍后重试。";
      case MALFORMED_OUTPUT, SCHEMA_INVALID -> "分析失败：模型输出未通过协议校验" +
      boundary +
      "。";
      case MISSING_ARTIFACT -> "分析失败：缺少协议要求的必需分析产物" + boundary + "。";
      case UNSUPPORTED_RESULT, VALIDATION_FAILED -> "分析失败：分析结果未通过证据校验" +
      boundary +
      "。";
    };
  }

  private static String redact(String detail) {
    if (detail == null || detail.isBlank()) return "unspecified";
    String compact = detail.replaceAll("\\s+", " ").trim();
    if (compact.length() > 200) compact = compact.substring(0, 200) + "...";
    return compact.replaceAll(
      "(?i)\\b(sk|api[_-]?key|access[_-]?token|token|secret)[-_:][A-Za-z0-9._\\-]{8,}",
      "[REDACTED]"
    );
  }
}
