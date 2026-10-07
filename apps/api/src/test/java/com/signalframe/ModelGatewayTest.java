package com.signalframe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.signalframe.ai.application.*;
import com.signalframe.ai.domain.*;
import com.signalframe.contract.*;
import com.signalframe.infrastructure.ai.providers.MockModelGateway;
import com.signalframe.shared.*;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class ModelGatewayTest {

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

  NewsItem news() {
    var now = Instant.now();
    return new NewsItem(
      UUID.randomUUID(),
      "新闻",
      new Source(
        UUID.randomUUID(),
        null,
        "一份原始新闻报告。",
        "PASTED",
        null,
        now
      ),
      DomainType.OTHER,
      now
    );
  }

  @Test
  void provenanceAndClaimTypeCannotBeForged() {
    var n = news();
    var request = new ModelRequest(
      UUID.randomUUID(),
      ModelPurpose.SYNTHESIS,
      "v1",
      "",
      n,
      profile,
      false
    );
    String output = new MockModelGateway(json).call(request).content();
    var parser = new AnalysisResultValidator(json, validator);
    assertTrue(parser.parse(output, n).demo());
    assertThrows(IllegalArgumentException.class, () ->
      parser.parse(output.replace("一份原始新闻报告。", "伪造的新闻内容。"), n)
    );
    assertThrows(IllegalArgumentException.class, () ->
      parser.parse(output.replace("\"confidence\":40", "\"confidence\":101"), n)
    );
    assertThrows(IllegalArgumentException.class, () ->
      parser.parse(
        output.replace("\"type\":\"FACT\"", "\"type\":\"INFERENCE\""),
        n
      )
    );
    assertThrows(IllegalArgumentException.class, () ->
      parser.parse(
        output.replace("\"status\":\"OPEN\"", "\"status\":\"INVALID\""),
        n
      )
    );
  }

  @Test
  void repairsOnceAndAuditsBothAttempts() {
    var n = news();
    var gateway = mock(ModelGateway.class);
    var runs = mock(ModelRunRepository.class);
    var mockResult = new MockModelGateway(json).call(
      new ModelRequest(
        UUID.randomUUID(),
        ModelPurpose.SYNTHESIS,
        "v1",
        "",
        n,
        profile,
        false
      )
    );
    when(gateway.call(any())).thenReturn(
      new ModelResponse(
        "{}",
        new ModelUsage(null, null, null, null),
        "mock",
        "mock-v1"
      ),
      mockResult
    );
    var router = new ModelRouter(
      new AiProperties(
        "analysis.fast",
        Map.of(),
        Map.of("analysis.fast", profile)
      ),
      validator,
      new com.signalframe.infrastructure.ai.providers.RoutingProfilePolicy()
    );
    var service = new ModelAnalysisService(
      router,
      gateway,
      runs,
      new AnalysisResultValidator(json, validator),
      new PromptCatalog(),
      json
    );
    assertTrue(service.synthesize(UUID.randomUUID(), "request", n, "").demo());
    verify(gateway, times(2)).call(any());
    var captor = org.mockito.ArgumentCaptor.forClass(ModelRun.class);
    verify(runs, times(2)).save(captor.capture());
    assertEquals(
      List.of("FAILED", "SUCCEEDED"),
      captor.getAllValues().stream().map(ModelRun::status).toList()
    );
  }

  @Test
  void exhaustedRepairReturnsCleanError() {
    var gateway = mock(ModelGateway.class);
    when(gateway.call(any())).thenReturn(
      new ModelResponse(
        "{}",
        new ModelUsage(null, null, null, null),
        "mock",
        "mock-v1"
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
      new com.signalframe.infrastructure.ai.providers.RoutingProfilePolicy()
    );
    var service = new ModelAnalysisService(
      router,
      gateway,
      runs,
      new AnalysisResultValidator(json, validator),
      new PromptCatalog(),
      json
    );
    assertEquals(
      "MODEL_OUTPUT_FAILED",
      assertThrows(ApplicationException.class, () ->
        service.synthesize(UUID.randomUUID(), "req", news(), "")
      ).code()
    );
    verify(runs, times(2)).save(any());
  }
}
