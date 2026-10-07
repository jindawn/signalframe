package com.signalframe.news.domain;

/**
 * Machine-readable failure category of a URL ingestion attempt.
 *
 * <p>Categories are stable codes: they are stable enough to log and to assert
 * in tests, and the persisted user message is built from them. Technical
 * exception detail (class names, stack traces, credentials) never reaches the
 * caller.
 */
public enum IngestionFailure {
  INVALID_URL(IngestionOutcome.UNSUPPORTED, "URL 无效或不受支持。"),
  UNSAFE_DESTINATION(IngestionOutcome.BLOCKED, "目标地址位于本机或私有网络，出于安全原因已拒绝访问。"),
  REDIRECT_TO_UNSAFE_DESTINATION(IngestionOutcome.BLOCKED, "跳转目标位于本机或私有网络，已拒绝访问。"),
  TOO_MANY_REDIRECTS(IngestionOutcome.EXTRACTION_FAILED, "页面跳转次数超过上限。"),
  TIMEOUT(IngestionOutcome.EXTRACTION_FAILED, "获取网页超时。"),
  ACCESS_BLOCKED(IngestionOutcome.BLOCKED, "目标站点拒绝访问（可能需要登录或付费订阅）。"),
  HTTP_ERROR(IngestionOutcome.EXTRACTION_FAILED, "目标站点返回错误状态。"),
  UNSUPPORTED_CONTENT_TYPE(IngestionOutcome.UNSUPPORTED, "网页内容类型不受支持（当前仅支持 HTML 与纯文本）。"),
  RESPONSE_TOO_LARGE(IngestionOutcome.UNSUPPORTED, "网页体积超过抓取上限。"),
  NETWORK_FAILURE(IngestionOutcome.EXTRACTION_FAILED, "网络连接失败。"),
  EXTRACTION_FAILED(IngestionOutcome.EXTRACTION_FAILED, "未能从网页中提取有效正文（可能是动态渲染或付费墙页面）。");

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
    return code() + " · " + userMessage + " · " + PASTE_HINT;
  }
}
