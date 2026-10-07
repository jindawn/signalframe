package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.signalframe.ai.application.*;
import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.*;
import com.signalframe.shared.*;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

/**
 * Execution policy: bounded transport retry, controlled repair and one audit
 * record per attempt.
 */
class ModelAnalysisRetryTest {

  final JsonCodec json = new JsonCodec();
  final jakarta.validation.Validator validator =
    Validation.buildDefaultValidatorFactory().getValidator();

  final ModelProfile profile = new ModelProfile(
    "mock",
    "mock-v1",
    "",
    "AI_FAST_API_KEY",
    0.2,
    2048,
    20,
    true,
    false,
    true
  );

  ModelAnalysisService service(
    ModelGateway gateway,
    ModelRunRepository runs,
    ModelRetryPolicy policy
  ) {
    var router = new ModelRouter(
      new AiProperties(
        "analysis.fast",
        Map.of(),
        Map.of("analysis.fast", profile)
      ),
      validator,
      new RoutingProfilePolicy()
    );
    return new ModelAnalysisService(
      router,
      gateway,
      runs,
      new AnalysisResultValidator(json, validator),
      new PromptCatalog(),
      json,
      policy
    );
  }

  ModelResponse validResponse(NewsItem news) {
    return new MockModelGateway(json).call(
      new ModelRequest(
        UUID.randomUUID(),
        ModelPurpose.SYNTHESIS,
        "synthesis-v1",
        "",
        news,
        profile,
        false
      )
    );
  }

  static ModelResponse unparsableResponse() {
    return new ModelResponse(
      "{\"not\":\"an analysis\"}",
      new ModelUsage(null, null, null, null),
      "mock",
      "mock-v1"
    );
  }

  @Test
  void retryableFailureIsRetriedAndAuditedPerAttempt() {
    var news = AiTestFixtures.news();
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any()))
      .thenThrow(ModelInvocationException.of(ModelFailure.TIMEOUT, "stub timed out"))
      .thenReturn(validResponse(news));
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(2, 1, Duration.ZERO));

    assertTrue(
      service.synthesize(UUID.randomUUID(), "corr-retry", news, "").demo()
    );

    verify(gateway, times(2)).call(any());
    var saved = captureRuns(runs, 2);
    assertEquals(
      List.of("FAILED", "SUCCEEDED"),
      saved.stream().map(ModelRun::status).toList()
    );
    assertEquals("TIMEOUT", saved.getFirst().errorType());
    assertNull(saved.get(1).errorType());
    var first = saved.getFirst();
    assertEquals("corr-retry", first.correlationId());
    assertEquals("synthesis-v1", first.promptVersion());
    assertEquals(ModelPurpose.SYNTHESIS, first.purpose());
    assertEquals("mock", first.provider());
    assertEquals("mock-v1", first.model());
    assertNotNull(first.latencyMs());
    assertTrue(first.latencyMs() >= 0);
    assertNull(first.inputTokens(), "failed attempt must not invent usage");
    assertEquals(
      0L,
      saved.get(1).totalTokens().longValue(),
      "offline adapter reports explicit zero usage and cost"
    );
    assertEquals(0.0, saved.get(1).estimatedCost());
  }

  @Test
  void nonRetryableFailureStopsAfterOneAttempt() {
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenThrow(
      ModelInvocationException.of(ModelFailure.AUTHENTICATION, "missing key")
    );
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(3, 1, Duration.ZERO));

    var error = assertThrows(ApplicationException.class, () ->
      service.synthesize(UUID.randomUUID(), "corr-auth", AiTestFixtures.news(), "")
    );

    assertEquals("AUTHENTICATION", error.code());
    assertEquals(502, error.status());
    verify(gateway, times(1)).call(any());
    var saved = captureRuns(runs, 1);
    assertEquals("AUTHENTICATION", saved.getFirst().errorType());
  }

  @Test
  void retryStopsAtTheConfiguredBound() {
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenThrow(
      ModelInvocationException.of(
        ModelFailure.PROVIDER_UNAVAILABLE,
        "provider down"
      )
    );
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(3, 1, Duration.ZERO));

    var error = assertThrows(ApplicationException.class, () ->
      service.synthesize(UUID.randomUUID(), "corr-bound", AiTestFixtures.news(), "")
    );

    assertEquals("PROVIDER_UNAVAILABLE", error.code());
    assertEquals(503, error.status());
    verify(gateway, times(3)).call(any());
    assertEquals(3, captureRuns(runs, 3).size());
  }

  @Test
  void unclassifiedRuntimeFailureIsAuditedOnceAndReportedCleanly() {
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenThrow(new IllegalStateException("bug"));
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(2, 1, Duration.ZERO));

    var error = assertThrows(ApplicationException.class, () ->
      service.synthesize(UUID.randomUUID(), "corr-bug", AiTestFixtures.news(), "")
    );

    assertEquals("MODEL_ERROR", error.code());
    verify(gateway, times(1)).call(any());
    assertEquals("UNKNOWN", captureRuns(runs, 1).getFirst().errorType());
  }

  @Test
  void malformedOutputIsRepairedUnderControlThenAccepted() {
    var news = AiTestFixtures.news();
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenReturn(
      unparsableResponse(),
      validResponse(news)
    );
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(2, 1, Duration.ZERO));

    assertTrue(service.synthesize(UUID.randomUUID(), "corr-repair", news, "").demo());

    var requests = ArgumentCaptor.forClass(ModelRequest.class);
    verify(gateway, times(2)).call(requests.capture());
    assertFalse(requests.getAllValues().getFirst().repair());
    assertTrue(requests.getAllValues().get(1).repair());
    assertTrue(
      requests.getAllValues().get(1).prompt().contains("Return corrected JSON only")
    );
    var saved = captureRuns(runs, 2);
    assertEquals("OUTPUT_INVALID", saved.getFirst().errorType());
    assertEquals("SUCCEEDED", saved.get(1).status());
  }

  @Test
  void malformedOutputFailsAfterRepairIsExhausted() {
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenReturn(unparsableResponse());
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(2, 1, Duration.ZERO));

    var error = assertThrows(ApplicationException.class, () ->
      service.synthesize(UUID.randomUUID(), "corr-exhausted", AiTestFixtures.news(), "")
    );

    assertEquals("MODEL_OUTPUT_FAILED", error.code());
    assertEquals(503, error.status());
    verify(gateway, times(2)).call(any());
    var saved = captureRuns(runs, 2);
    assertEquals(
      List.of("OUTPUT_INVALID", "OUTPUT_INVALID"),
      saved.stream().map(ModelRun::errorType).toList()
    );
  }

  @Test
  void repairCanBeDisabled() {
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenReturn(unparsableResponse());
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(2, 0, Duration.ZERO));

    var error = assertThrows(ApplicationException.class, () ->
      service.synthesize(UUID.randomUUID(), "corr-norepair", AiTestFixtures.news(), "")
    );

    assertEquals("MODEL_OUTPUT_FAILED", error.code());
    verify(gateway, times(1)).call(any());
    assertEquals(1, captureRuns(runs, 1).size());
  }

  @Test
  void provenanceViolationIsAlsoRepairedNotAccepted() {
    var news = AiTestFixtures.news();
    var forged = validResponse(news).content().replace(
      "一份原始新闻报告，用于验证模型运行时。",
      "伪造的新闻内容。"
    );
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenReturn(
      new ModelResponse(
        forged,
        new ModelUsage(null, null, null, null),
        "mock",
        "mock-v1"
      ),
      validResponse(news)
    );
    var runs = mock(ModelRunRepository.class);
    var service = service(gateway, runs, new ModelRetryPolicy(2, 1, Duration.ZERO));

    assertTrue(service.synthesize(UUID.randomUUID(), "corr-forged", news, "").demo());
    var saved = captureRuns(runs, 2);
    assertEquals("OUTPUT_INVALID", saved.getFirst().errorType());
    assertEquals("SUCCEEDED", saved.get(1).status());
  }

  @Test
  void retryPolicyRejectsUnusableBounds() {
    assertThrows(IllegalArgumentException.class, () ->
      new ModelRetryPolicy(0, 1, Duration.ZERO)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new ModelRetryPolicy(1, -1, Duration.ZERO)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new ModelRetryPolicy(1, 1, null)
    );
  }

  static List<ModelRun> captureRuns(ModelRunRepository runs, int times) {
    var captor = ArgumentCaptor.forClass(ModelRun.class);
    verify(runs, times(times)).save(captor.capture());
    return captor.getAllValues();
  }
}
