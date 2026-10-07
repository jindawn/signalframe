import { describe, it, expect } from "vitest";
import {
  apiFailure,
  isReadable,
  safeError,
  throwIfFailed,
  ApiFailure,
  FALLBACK,
  OFFLINE,
  UNREADABLE,
} from "./errors";

describe("user-facing errors", () => {
  it("maps contract codes to actionable text", () => {
    expect(
      apiFailure({ code: "NEEDS_TEXT", message: "x", requestId: "r" }),
    ).toContain("粘贴正文");
    expect(apiFailure({ code: "INVALID_URL", message: "", requestId: "r" })).toContain(
      "http",
    );
    expect(apiFailure({ code: "QUEUE_FULL", message: "", requestId: "r" })).toContain(
      "稍后重试",
    );
  });

  it("passes through readable backend messages", () => {
    expect(
      apiFailure({ code: "OTHER", message: "请粘贴正文继续分析。", requestId: "r" }),
    ).toBe("请粘贴正文继续分析。");
  });

  it("never surfaces stack traces, java classes or raw English internals", () => {
    expect(isReadable("java.lang.IllegalStateException: boom")).toBe(false);
    expect(isReadable("Resource not found")).toBe(false);
    expect(isReadable("\tat com.signalframe.Foo.bar(Foo.java:12)")).toBe(false);
    expect(isReadable("x".repeat(500))).toBe(false);
    expect(
      safeError(new Error("org.springframework.web.HttpRequestMethodNotSupportedException")),
    ).toBe(FALLBACK);
    expect(
      apiFailure({ code: "NOT_FOUND", message: "Resource not found", requestId: "r" }),
    ).toContain("找不到");
    expect(apiFailure(undefined)).toBe(FALLBACK);
  });

  it("explains network and unparsable responses plainly", () => {
    expect(safeError(new TypeError("Failed to fetch"))).toBe(OFFLINE);
    expect(safeError(new Error("Response is not JSON"))).toBe(UNREADABLE);
    expect(safeError(new Error("无法连接 API，请确认后端已启动。"))).toContain(
      "后端",
    );
    expect(safeError(undefined)).toBe(FALLBACK);
  });

  it("turns openapi-fetch results into data or a readable failure", () => {
    expect(throwIfFailed({ data: { id: "ok" } })).toEqual({ id: "ok" });
    const failed = () =>
      throwIfFailed({
        error: { code: "QUEUE_FULL", message: "queue", requestId: "r" },
      });
    expect(failed).toThrow(ApiFailure);
    try {
      failed();
    } catch (e) {
      expect((e as ApiFailure).code).toBe("QUEUE_FULL");
      expect(safeError(e)).toContain("稍后重试");
    }
    expect(() => throwIfFailed({})).toThrow();
    expect(safeError(new Error("EMPTY_RESPONSE"))).toBe(FALLBACK);
  });
});
