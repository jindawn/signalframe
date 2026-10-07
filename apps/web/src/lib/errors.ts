import type { Schema } from "./api";

/** Fallback shown when nothing readable is available. Never a stack trace. */
export const FALLBACK = "请求失败，请稍后重试。";
export const OFFLINE = "无法连接服务，请确认后端已启动，然后重试。";
export const UNREADABLE = "服务返回了无法识别的响应，请稍后重试。";

/** Contract error codes mapped to actionable, user-facing text. */
const CODE_TEXT: Record<string, string> = {
  INPUT_REQUIRED: "请至少输入一个 URL 或一段新闻正文。",
  INVALID_URL: "URL 需要以 http:// 或 https:// 开头。",
  NEEDS_TEXT: "无法可靠提取该网页，可粘贴正文继续分析。",
  INPUT_TOO_LARGE: "输入内容过长，请分段分析。",
  QUEUE_FULL: "分析队列已满，请稍后重试。",
  INVALID_REQUEST: "输入格式不正确，请检查后重试。",
  NOT_FOUND: "找不到该记录，可能尚未保存或已被删除。",
  INTERNAL_ERROR: "服务暂时不可用，请稍后重试。",
};

const NETWORK = /failed to fetch|networkerror|load failed|network request failed|econnrefused|err_connection/i;
const UNPARSABLE = /not json|unexpected token|json parse|invalid json/i;
const TECHNICAL =
  /(exception|stack ?trace|traceback|\bat [\w$.]+\(|<!doctype|<html|org\.springframework|java\.lang\.|caused by)/i;
const CJK = /[\u3400-\u9fff\uf900-\ufaff]/;

/**
 * True when a backend message can be shown to a user as-is: short, free of
 * technical markers and written for humans (Chinese in this product).
 */
export function isReadable(text?: string | null): boolean {
  if (!text) return false;
  const value = text.trim();
  if (!value || value.length > 400) return false;
  if (TECHNICAL.test(value)) return false;
  return CJK.test(value);
}

/** Message for a contract `ApiError` returned by the API. */
export function apiFailure(
  error?: Schema<"ApiError"> | null,
  fallback = FALLBACK,
): string {
  if (!error) return fallback;
  return CODE_TEXT[error.code] ?? (isReadable(error.message) ? error.message.trim() : fallback);
}

/** Thrown by `throwIfFailed`; carries the contract code without leaking it. */
export class ApiFailure extends Error {
  readonly code: string;
  constructor(error: Schema<"ApiError">) {
    super(apiFailure(error));
    this.name = "ApiFailure";
    this.code = error.code;
  }
}

/**
 * Converts an openapi-fetch result into data or a user-facing throw, so callers
 * never hand-roll a duplicate error path.
 */
export function throwIfFailed<T>(result: {
  data?: T;
  error?: Schema<"ApiError">;
}): T {
  if (result.error) throw new ApiFailure(result.error);
  if (result.data === undefined) throw new Error("EMPTY_RESPONSE");
  return result.data;
}

/**
 * Message for a thrown value (network failure, parser failure, `unwrap`).
 * Technical detail is replaced, never surfaced.
 */
export function safeError(error: unknown, fallback = FALLBACK): string {
  const raw =
    error instanceof Error
      ? error.message
      : typeof error === "string"
        ? error
        : "";
  if (NETWORK.test(raw)) return OFFLINE;
  if (UNPARSABLE.test(raw)) return UNREADABLE;
  if (isReadable(raw)) return raw.trim();
  return fallback;
}
