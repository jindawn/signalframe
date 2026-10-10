package com.signalframe.news.domain;

import java.util.Optional;

/**
 * Machine-readable failure category of a URL ingestion attempt.
 *
 * <p>Categories are stable codes: they are stable enough to log and to assert
 * in tests, and the persisted user message is built from them. Technical
 * exception detail (class names, stack traces, credentials) never reaches the
 * caller.
 *
 * <p>Wire format: the canonical {@code Source} contract can only carry a free
 * text {@code message}, so a failed attempt is always persisted as
 * {@code CODE · reason · next step} (see {@link #persistedMessage()}), where
 * {@code CODE} is exactly one of these enum names. Callers that need the reason
 * read the leading token with {@link #parseCode(String)} and must treat an
 * unknown or missing code as "reason unavailable" instead of guessing. Only the
 * reason code and the safe text are persisted; no URL, host, address or
 * exception detail is included.
 */
public enum IngestionFailure {
  INVALID_URL(IngestionOutcome.UNSUPPORTED, "URL 无效或不受支持。"),
  UNSAFE_DESTINATION(IngestionOutcome.BLOCKED, "该地址因安全限制无法访问。"),
  REDIRECT_TO_UNSAFE_DESTINATION(
    IngestionOutcome.BLOCKED,
    "页面跳转到了受限制的地址，出于安全原因已停止访问。"
  ),
  TOO_MANY_REDIRECTS(IngestionOutcome.EXTRACTION_FAILED, "页面跳转次数超过上限。"),
  TIMEOUT(IngestionOutcome.EXTRACTION_FAILED, "网页响应超时，未能在限定时间内获取网页。"),
  ACCESS_BLOCKED(
    IngestionOutcome.BLOCKED,
    "目标站点拒绝访问（可能需要登录或付费订阅）。"
  ),
  HTTP_ERROR(IngestionOutcome.EXTRACTION_FAILED, "目标站点返回错误状态。"),
  UNSUPPORTED_CONTENT_TYPE(
    IngestionOutcome.UNSUPPORTED,
    "网页内容类型不受支持（当前仅支持 HTML 与纯文本）。"
  ),
  RESPONSE_TOO_LARGE(IngestionOutcome.UNSUPPORTED, "网页体积超过抓取上限。"),
  NETWORK_FAILURE(IngestionOutcome.EXTRACTION_FAILED, "网络连接失败，未能获取网页。"),
  EXTRACTION_FAILED(
    IngestionOutcome.EXTRACTION_FAILED,
    "无法可靠提取该网页正文。页面可能采用动态加载，或当前提取器暂不支持。"
  );

  /** Field separator of the persisted {@code CODE · reason · next step} format. */
  public static final String SEPARATOR = " · ";

  /** User-facing instruction appended whenever the caller has to supply text. */
  public static final String PASTE_HINT = "请粘贴正文继续分析。";

  private final IngestionOutcome outcome;
  private final String userMessage;

  IngestionFailure(IngestionOutcome outcome, String userMessage) {
    this.outcome = outcome;
    this.userMessage = userMessage;
  }

  public IngestionOutcome outcome() {
    return outcome;
  }

  /** Stable code, e.g. {@code UNSAFE_DESTINATION}. */
  public String code() {
    return name();
  }

  /** Safe Chinese explanation; never contains request data or exception detail. */
  public String userMessage() {
    return userMessage;
  }

  /** Message persisted on the source, with an actionable next step. */
  public String persistedMessage() {
    return code() + SEPARATOR + userMessage + SEPARATOR + PASTE_HINT;
  }

  /**
   * Reads the leading reason code of a message produced by this enum.
   *
   * @return the matching category, or empty when the message carries no known
   *     code (success provenance, free-form text, legacy rows).
   */
  public static Optional<IngestionFailure> parseCode(String message) {
    if (message == null) return Optional.empty();
    String value = message.trim();
    if (value.isEmpty()) return Optional.empty();
    int end = value.indexOf(SEPARATOR);
    String candidate = (end < 0 ? value : value.substring(0, end)).trim();
    if (candidate.isEmpty()) return Optional.empty();
    for (IngestionFailure failure : values()) if (
      failure.name().equals(candidate)
    ) return Optional.of(failure);
    return Optional.empty();
  }
}
