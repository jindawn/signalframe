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
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * TASK-04/TASK-09 acceptance: no credential or prompt text may reach the audit
 * record, the persisted payload or an error surfaced to the caller.
 */
class ModelAuditRedactionTest {

  static final String SENTINEL = "sk-sentinel-abcdef123456";

  final JsonCodec json = new JsonCodec();
  final jakarta.validation.Validator validator =
    Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void credentialsValueIsNeverRendered() {
    var credentials = new ModelCredentials(SENTINEL);
    assertEquals("ModelCredentials[REDACTED]", credentials.toString());
    assertEquals("ModelCredentials[REDACTED]", String.valueOf(credentials));
    assertFalse(credentials.toString().contains(SENTINEL));
  }

  @Test
  void failureDetailIsRedactedSingleLineAndBounded() {
    var credentials = new ModelCredentials(SENTINEL);
    var failure = ModelInvocationException.of(
      ModelFailure.AUTHENTICATION,
      "provider said\n  Authorization: Bearer " +
      SENTINEL +
      "\n and echoed " +
      SENTINEL,
      credentials
    );
    assertFalse(failure.getMessage().contains(SENTINEL));
    assertFalse(failure.detail().contains(SENTINEL));
    assertTrue(failure.getMessage().contains("[REDACTED]"));
    assertFalse(failure.getMessage().contains("\n"));

    var longFailure = ModelInvocationException.of(
      ModelFailure.UNKNOWN,
      "x".repeat(5_000)
    );
    assertTrue(longFailure.getMessage().length() < 600);
  }

  @Test
  void keyShapedTokensAreMaskedEvenWithoutAKnownCredential() {
    var failure = ModelInvocationException.of(
      ModelFailure.UNKNOWN,
      "upstream rejected token sk-live-0123456789abcdef"
    );
    assertFalse(failure.getMessage().contains("sk-live-0123456789abcdef"));
    assertTrue(failure.getMessage().contains("[REDACTED]"));
  }

  @Test
  void auditRecordCarriesEveryRequiredFieldAndNoPrompt() {
    var names = Arrays
      .stream(ModelRun.class.getRecordComponents())
      .map(RecordComponent::getName)
      .toList();
    assertTrue(
      names.containsAll(
        List.of(
          "provider",
          "model",
          "purpose",
          "promptVersion",
          "latencyMs",
          "inputTokens",
          "outputTokens",
          "totalTokens",
          "estimatedCost",
          "status",
          "errorType",
          "correlationId"
        )
      ),
      names.toString()
    );
    for (var forbidden : List.of(
      "prompt",
      "apiKey",
      "apiKeyEnv",
      "authorization",
      "request",
      "response",
      "content",
      "messages"
    )) assertFalse(
      names.contains(forbidden),
      "audit record must not expose " + forbidden
    );
  }

  @Test
  void credentialAndPromptNeverReachThePersistedAuditRecord() {
    var news = AiTestFixtures.news();
    var profile = AiTestFixtures.profile("mock", "mock-v1");
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenThrow(
      ModelInvocationException.of(
        ModelFailure.AUTHENTICATION,
        "provider echoed the credential " + SENTINEL,
        new ModelCredentials(SENTINEL)
      )
    );
    var runs = mock(ModelRunRepository.class);
    var router = new ModelRouter(
      new AiProperties(
        "analysis.fast",
        Map.of(),
        Map.of("analysis.fast", profile)
      ),
      validator,
      new RoutingProfilePolicy()
    );
    var service = new ModelAnalysisService(
      router,
      gateway,
      runs,
      new AnalysisResultValidator(json, validator),
      new PromptCatalog(),
      json,
      new ModelRetryPolicy(1, 0, Duration.ZERO)
    );

    var error = assertThrows(ApplicationException.class, () ->
      service.synthesize(UUID.randomUUID(), "corr", news, "")
    );

    var captor = org.mockito.ArgumentCaptor.forClass(ModelRun.class);
    verify(runs).save(captor.capture());
    String payload = json.write(captor.getValue());
    assertFalse(payload.contains(SENTINEL));
    assertFalse(payload.contains(news.source().text()));
    assertFalse(error.getMessage().contains(SENTINEL));
    assertFalse(String.valueOf(error).contains(SENTINEL));
  }
}
