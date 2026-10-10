import { isReadable } from "./errors";

/**
 * URL-ingestion failure diagnostics.
 *
 * The canonical `Source` contract has no reason-code field, so the backend
 * persists `CODE · reason · next step` in `Source.message` (see
 * `com.signalframe.news.domain.IngestionFailure`). This module reads that
 * leading code back and turns it into human, actionable text. Keep the code list
 * in sync with the backend enum; an unknown or missing code degrades to a
 * generic explanation — it is never echoed raw and never guessed at.
 */
export const INGESTION_REASON_CODES = [
  "INVALID_URL",
  "UNSAFE_DESTINATION",
  "REDIRECT_TO_UNSAFE_DESTINATION",
  "TOO_MANY_REDIRECTS",
  "TIMEOUT",
  "ACCESS_BLOCKED",
  "HTTP_ERROR",
  "UNSUPPORTED_CONTENT_TYPE",
  "RESPONSE_TOO_LARGE",
  "NETWORK_FAILURE",
  "EXTRACTION_FAILED",
] as const;

export type IngestionReasonCode = (typeof INGESTION_REASON_CODES)[number];

/** Coarse family of a failure, mirroring the backend `IngestionOutcome`. */
export type IngestionCategory = "BLOCKED" | "UNSUPPORTED" | "EXTRACTION_FAILED";

/**
 * Human label for the coarse family, always shown next to the raw code so the
 * state stays readable without relying on color.
 */
export const ingestionCategoryLabel: Record<IngestionCategory, string> = {
  BLOCKED: "访问被阻止",
  UNSUPPORTED: "内容不受支持",
  EXTRACTION_FAILED: "提取失败",
};

export type IngestionDiagnostic = {
  /** Machine-readable reason, or null when the backend did not provide one. */
  code: IngestionReasonCode | null;
  category: IngestionCategory;
  /** What happened, in one sentence. */
  title: string;
  /** Supporting detail; never internal or security implementation data. */
  detail: string;
  /** Recovery step that is always available. */
  action: string;
  /** True when re-submitting the same URL may help. */
  retryable: boolean;
};

/** Recovery step offered for every failure: the paste-text fallback. */
export const PASTE_ACTION = "你可以粘贴正文继续分析。";

type DiagnosticCopy = Omit<IngestionDiagnostic, "code">;

const DIAGNOSTICS: Record<IngestionReasonCode, DiagnosticCopy> = {
  EXTRACTION_FAILED: {
    category: "EXTRACTION_FAILED",
    retryable: false,
    title: "无法可靠提取该网页正文。",
    detail: "页面可能采用动态加载或当前提取器暂不支持。",
    action: PASTE_ACTION,
  },
  UNSAFE_DESTINATION: {
    category: "BLOCKED",
    retryable: false,
    title: "该地址因安全限制无法访问。",
    detail: "系统安全策略拒绝了这次请求，不会访问受限地址。",
    action: PASTE_ACTION,
  },
  REDIRECT_TO_UNSAFE_DESTINATION: {
    category: "BLOCKED",
    retryable: false,
    title: "页面跳转到了受限制的地址。",
    detail: "出于安全原因，跳转已被终止，未继续访问。",
    action: PASTE_ACTION,
  },
  ACCESS_BLOCKED: {
    category: "BLOCKED",
    retryable: false,
    title: "目标站点拒绝访问。",
    detail: "页面可能需要登录、订阅，或对自动访问有限制。",
    action: PASTE_ACTION,
  },
  TIMEOUT: {
    category: "EXTRACTION_FAILED",
    retryable: true,
    title: "网页响应超时。",
    detail: "可以重试，或直接粘贴正文。",
    action: PASTE_ACTION,
  },
  NETWORK_FAILURE: {
    category: "EXTRACTION_FAILED",
    retryable: true,
    title: "网络连接失败，未能获取网页。",
    detail: "请检查网络后重试。",
    action: PASTE_ACTION,
  },
  HTTP_ERROR: {
    category: "EXTRACTION_FAILED",
    retryable: true,
    title: "目标站点返回错误状态。",
    detail: "页面可能暂时不可用，可以稍后重试。",
    action: PASTE_ACTION,
  },
  TOO_MANY_REDIRECTS: {
    category: "EXTRACTION_FAILED",
    retryable: true,
    title: "页面跳转次数超过上限。",
    detail: "可以重试，或改用原网页的直达链接。",
    action: PASTE_ACTION,
  },
  UNSUPPORTED_CONTENT_TYPE: {
    category: "UNSUPPORTED",
    retryable: false,
    title: "该地址返回的内容类型不受支持。",
    detail: "当前仅支持 HTML 与纯文本页面。",
    action: PASTE_ACTION,
  },
  RESPONSE_TOO_LARGE: {
    category: "UNSUPPORTED",
    retryable: false,
    title: "网页体积超过抓取上限。",
    detail: "过大的页面不会被下载。",
    action: PASTE_ACTION,
  },
  INVALID_URL: {
    category: "UNSUPPORTED",
    retryable: false,
    title: "URL 无效或不受支持。",
    detail: "请使用以 http:// 或 https:// 开头的公开网页地址。",
    action: PASTE_ACTION,
  },
};

/** Used when the backend supplied no readable reason; still actionable. */
export const UNKNOWN_DIAGNOSTIC: IngestionDiagnostic = {
  code: null,
  category: "EXTRACTION_FAILED",
  retryable: false,
  title: "未能从该网页提取正文。",
  detail: "页面可能采用动态加载，或当前提取器暂不支持。",
  action: PASTE_ACTION,
};

/** Reads the stable leading reason code, or null when there is none. */
export function parseIngestionReason(
  message?: string | null,
): IngestionReasonCode | null {
  if (!message) return null;
  const [head] = message.trim().split("·");
  const candidate = head.trim();
  return (INGESTION_REASON_CODES as readonly string[]).includes(candidate)
    ? (candidate as IngestionReasonCode)
    : null;
}

/** Human diagnostic for a persisted `Source.message`; never throws. */
export function ingestionDiagnostic(
  message?: string | null,
): IngestionDiagnostic {
  const code = parseIngestionReason(message);
  return code ? { code, ...DIAGNOSTICS[code] } : UNKNOWN_DIAGNOSTIC;
}

/**
 * Notice for a URL that was not used because pasted text was analyzed instead.
 * Returns null for an extraction success or a plain paste.
 */
export function unusedUrlNotice(message?: string | null): string | null {
  const code = parseIngestionReason(message);
  if (!code) return null;
  const diagnostic = ingestionDiagnostic(message);
  return `URL 未使用（${code}）：${diagnostic.title}已改用你粘贴的正文继续分析。`;
}

/**
 * Raw backend message, returned only when it is provably safe to render
 * (readable Chinese, short, no technical markers). Otherwise null.
 */
export function rawDiagnostic(message?: string | null): string | null {
  if (!isReadable(message)) return null;
  return message!.trim();
}
