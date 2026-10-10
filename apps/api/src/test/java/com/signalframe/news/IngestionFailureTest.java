package com.signalframe.news;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.news.domain.IngestionFailure;
import com.signalframe.news.domain.IngestionOutcome;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Reason-code contract: every failure category must be machine-readable, safe to
 * show and self-describing, so the UI can turn it into a human recovery step
 * without the backend leaking technical detail.
 */
class IngestionFailureTest {

  /** Categories the UI must be able to distinguish (see TASK-02 diagnostics). */
  private static final Set<IngestionFailure> REQUIRED = EnumSet.of(
    IngestionFailure.UNSAFE_DESTINATION,
    IngestionFailure.EXTRACTION_FAILED,
    IngestionFailure.TIMEOUT,
    IngestionFailure.NETWORK_FAILURE,
    IngestionFailure.RESPONSE_TOO_LARGE
  );

  /** The three coarse contract-level families the reasons map onto. */
  private static final Set<IngestionOutcome> REQUIRED_OUTCOMES = EnumSet.of(
    IngestionOutcome.BLOCKED,
    IngestionOutcome.UNSUPPORTED,
    IngestionOutcome.EXTRACTION_FAILED
  );

  private static final Pattern TECHNICAL = Pattern.compile(
    "(exception|stack ?trace|traceback|\\bat [\\w$.]+\\(|java\\.|javax\\.|org\\.springframework|" +
    "https?://|null|\\d{1,3}(\\.\\d{1,3}){3})",
    Pattern.CASE_INSENSITIVE
  );

  @ParameterizedTest
  @EnumSource(IngestionFailure.class)
  void everyReasonIsMachineryReadableSafeAndActionable(IngestionFailure failure) {
    assertNotNull(failure.outcome(), failure.code());
    assertFalse(failure.userMessage().isBlank(), failure.code());

    String persisted = failure.persistedMessage();
    assertTrue(
      persisted.startsWith(failure.code() + IngestionFailure.SEPARATOR),
      "message must lead with the stable code: " + persisted
    );
    assertTrue(persisted.contains(IngestionFailure.PASTE_HINT), persisted);
    assertEquals(failure, IngestionFailure.parseCode(persisted).orElseThrow());
  }

  @ParameterizedTest
  @EnumSource(IngestionFailure.class)
  void everyReasonAvoidsTechnicalDetailAndRequestData(IngestionFailure failure) {
    for (String text : List.of(failure.code(), failure.userMessage(), failure.persistedMessage())) {
      assertFalse(
        TECHNICAL.matcher(text).find(),
        "technical detail leaked for " + failure.code() + ": " + text
      );
    }
  }

  @Test
  void distinguishesAllRequiredCategories() {
    assertTrue(
      EnumSet.allOf(IngestionFailure.class).containsAll(REQUIRED),
      "required failure categories must exist as stable codes"
    );
    for (IngestionOutcome outcome : REQUIRED_OUTCOMES) {
      assertTrue(
        EnumSet.allOf(IngestionFailure.class).stream().anyMatch(f -> f.outcome() == outcome),
        "no reason maps onto outcome " + outcome
      );
    }
    assertEquals(IngestionOutcome.BLOCKED, IngestionFailure.UNSAFE_DESTINATION.outcome());
    assertEquals(IngestionOutcome.BLOCKED, IngestionFailure.ACCESS_BLOCKED.outcome());
    assertEquals(IngestionOutcome.UNSUPPORTED, IngestionFailure.RESPONSE_TOO_LARGE.outcome());
    assertEquals(
      IngestionOutcome.UNSUPPORTED,
      IngestionFailure.UNSUPPORTED_CONTENT_TYPE.outcome()
    );
    assertEquals(IngestionOutcome.EXTRACTION_FAILED, IngestionFailure.TIMEOUT.outcome());
    assertEquals(
      IngestionOutcome.EXTRACTION_FAILED,
      IngestionFailure.NETWORK_FAILURE.outcome()
    );
  }

  @Test
  void userFacingCopyStaysExplainable() {
    assertEquals(
      "该地址因安全限制无法访问。",
      IngestionFailure.UNSAFE_DESTINATION.userMessage()
    );
    assertTrue(
      IngestionFailure.EXTRACTION_FAILED.userMessage().contains("动态加载"),
      "dynamic-rendering explanation is expected"
    );
    assertTrue(
      IngestionFailure.TIMEOUT.userMessage().contains("超时"),
      "timeout explanation is expected"
    );
  }

  @Test
  void parseCodeReadsTheLeadingTokenOnly() {
    assertEquals(
      IngestionFailure.TIMEOUT,
      IngestionFailure.parseCode(
        "TIMEOUT · 网页响应超时，未能在限定时间内获取网页。 · 请粘贴正文继续分析。"
      ).orElseThrow()
    );
    assertEquals(
      IngestionFailure.ACCESS_BLOCKED,
      IngestionFailure.parseCode(
        IngestionFailure.ACCESS_BLOCKED.code() + " · URL 未使用：目标站点拒绝访问。 已保留你粘贴的正文并继续分析。"
      ).orElseThrow()
    );
  }

  @Test
  void parseCodeNeverGuesses() {
    assertTrue(IngestionFailure.parseCode(null).isEmpty());
    assertTrue(IngestionFailure.parseCode("").isEmpty());
    assertTrue(IngestionFailure.parseCode("   ").isEmpty());
    assertTrue(
      IngestionFailure.parseCode("URL 抽取成功 · example.com · text/html").isEmpty(),
      "success provenance carries no failure reason"
    );
    assertTrue(
      IngestionFailure.parseCode("FAILURE_UNKNOWN · something else").isEmpty()
    );
    assertTrue(IngestionFailure.parseCode("请粘贴正文继续分析。").isEmpty());
  }
}
