import { describe, it, expect } from "vitest";
import {
  INGESTION_REASON_CODES,
  PASTE_ACTION,
  UNKNOWN_DIAGNOSTIC,
  ingestionCategoryLabel,
  ingestionDiagnostic,
  parseIngestionReason,
  rawDiagnostic,
  unusedUrlNotice,
  type IngestionCategory,
  type IngestionReasonCode,
} from "./ingestion";

/** Same shape the backend persists: `CODE · reason · next step`. */
const persisted = (code: IngestionReasonCode) =>
  `${code} · 后端保存的安全说明。 · 请粘贴正文继续分析。`;

describe("ingestion reason parsing", () => {
  it("reads the leading code of a persisted failure message", () => {
    for (const code of INGESTION_REASON_CODES) {
      expect(parseIngestionReason(persisted(code))).toBe(code);
    }
  });

  it("never guesses a reason", () => {
    expect(parseIngestionReason(null)).toBeNull();
    expect(parseIngestionReason(undefined)).toBeNull();
    expect(parseIngestionReason("")).toBeNull();
    expect(parseIngestionReason("   ")).toBeNull();
    expect(
      parseIngestionReason("URL 抽取成功 · example.com · text/html"),
    ).toBeNull();
    expect(parseIngestionReason("FAILURE_UNKNOWN · 说明")).toBeNull();
    expect(parseIngestionReason("请粘贴正文继续分析。")).toBeNull();
  });
});

describe("reason to user-facing diagnostic", () => {
  it("maps EXTRACTION_FAILED to the dynamic-loading explanation", () => {
    const diagnostic = ingestionDiagnostic(persisted("EXTRACTION_FAILED"));
    expect(diagnostic.code).toBe("EXTRACTION_FAILED");
    expect(diagnostic.category).toBe("EXTRACTION_FAILED");
    expect(diagnostic.title).toBe("无法可靠提取该网页正文。");
    expect(diagnostic.detail).toContain("动态加载");
    expect(diagnostic.action).toBe("你可以粘贴正文继续分析。");
    expect(diagnostic.retryable).toBe(false);
  });

  it("maps UNSAFE_DESTINATION without exposing security internals", () => {
    const diagnostic = ingestionDiagnostic(persisted("UNSAFE_DESTINATION"));
    expect(diagnostic.title).toBe("该地址因安全限制无法访问。");
    expect(diagnostic.category).toBe("BLOCKED");
    expect(diagnostic.retryable).toBe(false);
    for (const text of Object.values(diagnostic)) {
      expect(String(text)).not.toMatch(
        /dns|ip|loopback|127\.0\.0\.1|10\.0\.0|exception|java\.|stack ?trace/i,
      );
    }
  });

  it("maps TIMEOUT to a retry-or-paste recovery", () => {
    const diagnostic = ingestionDiagnostic(persisted("TIMEOUT"));
    expect(diagnostic.title).toBe("网页响应超时。");
    expect(diagnostic.detail).toContain("重试");
    expect(diagnostic.retryable).toBe(true);
    expect(diagnostic.action).toBe(PASTE_ACTION);
  });

  it("gives every reason a category, a title and the paste fallback", () => {
    for (const code of INGESTION_REASON_CODES) {
      const diagnostic = ingestionDiagnostic(persisted(code));
      expect(diagnostic.code).toBe(code);
      expect(diagnostic.title.length).toBeGreaterThan(0);
      expect(diagnostic.detail.length).toBeGreaterThan(0);
      expect(diagnostic.action).toBe(PASTE_ACTION);
      expect(["BLOCKED", "UNSUPPORTED", "EXTRACTION_FAILED"]).toContain(
        diagnostic.category,
      );
    }
  });

  it("keeps block and unsupported reasons non-retryable", () => {
    const retryable = INGESTION_REASON_CODES.filter(
      (code) => ingestionDiagnostic(persisted(code)).retryable,
    );
    expect(retryable.sort()).toEqual(
      ["HTTP_ERROR", "NETWORK_FAILURE", "TIMEOUT", "TOO_MANY_REDIRECTS"].sort(),
    );
    for (const code of [
      "UNSAFE_DESTINATION",
      "ACCESS_BLOCKED",
      "RESPONSE_TOO_LARGE",
    ] as const) {
      expect(ingestionDiagnostic(persisted(code)).retryable).toBe(false);
    }
  });

  it("falls back to a safe generic diagnostic without a reason", () => {
    expect(ingestionDiagnostic(null)).toEqual(UNKNOWN_DIAGNOSTIC);
    expect(ingestionDiagnostic("URL 抽取成功 · example.com")).toEqual(
      UNKNOWN_DIAGNOSTIC,
    );
    expect(UNKNOWN_DIAGNOSTIC.code).toBeNull();
    expect(UNKNOWN_DIAGNOSTIC.action).toBe(PASTE_ACTION);
  });

  it("labels every failure family next to the raw code", () => {
    const categories: IngestionCategory[] = [
      "BLOCKED",
      "UNSUPPORTED",
      "EXTRACTION_FAILED",
    ];
    for (const category of categories) {
      expect(ingestionCategoryLabel[category].length).toBeGreaterThan(0);
    }
    expect(ingestionCategoryLabel.BLOCKED).toBe("访问被阻止");
    expect(ingestionCategoryLabel[UNKNOWN_DIAGNOSTIC.category]).toBe(
      "提取失败",
    );
  });
});

describe("unused URL notice", () => {
  it("explains a URL that was dropped in favour of pasted text", () => {
    const notice = unusedUrlNotice(
      "ACCESS_BLOCKED · URL 未使用：目标站点拒绝访问。 已保留你粘贴的正文并继续分析。",
    );
    expect(notice).toContain("ACCESS_BLOCKED");
    expect(notice).toContain("已改用你粘贴的正文继续分析");
  });

  it("stays silent for success and plain pastes", () => {
    expect(unusedUrlNotice(null)).toBeNull();
    expect(unusedUrlNotice("URL 抽取成功 · example.com")).toBeNull();
  });
});

describe("raw diagnostic rendering", () => {
  it("passes readable Chinese through and hides technical output", () => {
    expect(rawDiagnostic(persisted("TIMEOUT"))).toContain(
      "请粘贴正文继续分析。",
    );
    expect(rawDiagnostic("java.lang.IllegalStateException: boom")).toBeNull();
    expect(
      rawDiagnostic(
        "\tat com.signalframe.news.SafeHttpFetcher.fetch(SafeHttpFetcher.java:78)",
      ),
    ).toBeNull();
    expect(rawDiagnostic("Resource not found")).toBeNull();
    expect(rawDiagnostic(null)).toBeNull();
  });
});
