package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.analysis.support.PipelineFixtures.RecordingRuns;
import com.signalframe.analysis.support.PipelineFixtures.ScriptedGateway;
import com.signalframe.contract.ModelPurpose;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The bounded model-call loop of one protocol stage.
 *
 * <p>Covers malformed output, transport retry, a single repair round, the
 * per-attempt ModelRun audit and the vendor-neutral failure classification that
 * the job report uses.
 */
class StageModelClientTest {

  private final RecordingRuns runs = new RecordingRuns();
  private final AtomicInteger failWithInvalid = new AtomicInteger();

  private StageModelClient client(ModelGateway gateway, ModelRetryPolicy policy) {
    return new StageModelClient(PipelineFixtures.router(), gateway, runs, policy);
  }

  private StageModelClient.StageCall call(String stage, String version) {
    return new StageModelClient.StageCall(
      stage,
      UUID.randomUUID(),
      "test-correlation",
      PipelineFixtures.news(),
      ModelPurpose.FACT_EXTRACTION,
      version,
      "stage prompt"
    );
  }

  private static StageModelClient.StageParser<String> jsonParser() {
    return raw -> {
      if (!raw.trim().startsWith("{")) throw StageOutputException.malformed(
        "response is not a JSON object"
      );
      return raw;
    };
  }

  @Test
  void malformedOutputIsRepairedOnceThenFailsWithEveryAttemptAudited() {
    var gateway = new ScriptedGateway(request -> "this is not json");
    var client = client(gateway, new ModelRetryPolicy(2, 1, Duration.ZERO));

    var failure = assertThrows(StageFailure.class, () ->
      client.invoke(call("FactExtraction", "fact-extraction-v1"), jsonParser())
    );

    assertEquals(StageFailure.Kind.MALFORMED_OUTPUT, failure.kind());
    assertEquals("FactExtraction", failure.stage());
    assertEquals(2, gateway.requests().size(), "one call plus one repair round");
    assertEquals(2, runs.all().size(), "every attempt is audited on its own");
    assertTrue(
      runs.all().stream().allMatch(r -> "FAILED".equals(r.status()))
    );
    assertEquals("OUTPUT_INVALID", runs.all().getFirst().errorType());
    assertEquals("fact-extraction-v1", runs.all().getFirst().promptVersion());
    assertEquals(ModelPurpose.FACT_EXTRACTION, runs.all().getFirst().purpose());
    assertEquals("test-correlation", runs.all().getFirst().correlationId());
    // The repair prompt carries the hint, never the rejected raw output.
    assertTrue(
      gateway
        .requests()
        .get(1)
        .prompt()
        .contains("Previous output was rejected by protocol validation")
    );
    assertFalse(
      gateway.requests().get(1).prompt().contains("this is not json")
    );
    assertTrue(gateway.requests().get(1).repair());
  }

  @Test
  void aSingleRepairRoundRecoversFromOneBadResponse() {
    var gateway = new ScriptedGateway(request ->
      failWithInvalid.getAndIncrement() == 0 ? "not json" : "{\"ok\":true}"
    );
    var client = client(gateway, new ModelRetryPolicy(2, 1, Duration.ZERO));

    var parsed = client.invoke(
      call("FactExtraction", "fact-extraction-v1"),
      jsonParser()
    );

    assertEquals("{\"ok\":true}", parsed);
    assertEquals(2, gateway.requests().size());
    assertEquals(2, runs.all().size());
    assertEquals("FAILED", runs.all().getFirst().status());
    assertEquals("SUCCEEDED", runs.all().getLast().status());
    assertNull(runs.all().getLast().errorType());
  }

  @Test
  void timeoutIsRetriedWithinTheBoundAndClassifiedAsTimeout() {
    var gateway = new ScriptedGateway(request -> {
      throw ModelInvocationException.of(ModelFailure.TIMEOUT, "no answer in 3s");
    });
    var client = client(gateway, new ModelRetryPolicy(2, 0, Duration.ZERO));

    var failure = assertThrows(StageFailure.class, () ->
      client.invoke(call("VariableAnalysis", "variable-analysis-v1"), jsonParser())
    );

    assertEquals(StageFailure.Kind.TIMEOUT, failure.kind());
    assertEquals(2, gateway.requests().size(), "retryable failure is retried once");
    assertEquals(2, runs.all().size());
    assertTrue(
      runs.all().stream().allMatch(r -> "TIMEOUT".equals(r.errorType()))
    );
    assertTrue(
      runs.all().stream().allMatch(r -> "FAILED".equals(r.status()))
    );
  }

  @Test
  void nonRetryableProviderFailureIsNotRetried() {
    var gateway = new ScriptedGateway(request -> {
      throw ModelInvocationException.of(
        ModelFailure.AUTHENTICATION,
        "credential rejected"
      );
    });
    var client = client(gateway, new ModelRetryPolicy(3, 1, Duration.ZERO));

    var failure = assertThrows(StageFailure.class, () ->
      client.invoke(call("FactExtraction", "fact-extraction-v1"), jsonParser())
    );

    assertEquals(StageFailure.Kind.PROVIDER_FAILURE, failure.kind());
    assertEquals(1, gateway.requests().size());
    assertEquals(1, runs.all().size());
    assertFalse(
      failure.getMessage().contains("credential rejected") &&
      failure.jobMessage().contains("credential rejected")
    );
  }

  @Test
  void anUnclassifiedAdapterFailureIsReportedAsAProviderFailure() {
    var gateway = new ScriptedGateway(request -> {
      throw new IllegalStateException("vendor-specific explosion");
    });
    var client = client(gateway, new ModelRetryPolicy(2, 0, Duration.ZERO));

    var failure = assertThrows(StageFailure.class, () ->
      client.invoke(call("Synthesis", "synthesis-v1"), jsonParser())
    );

    assertEquals(StageFailure.Kind.PROVIDER_FAILURE, failure.kind());
    assertEquals("UNKNOWN", runs.all().getFirst().errorType());
  }

  @Test
  void jobMessagesAreClassifiedOperatorCopyWithoutProviderDetails() {
    var network = new StageFailure(
      "FactExtraction",
      StageFailure.Kind.PROVIDER_FAILURE,
      "PROVIDER_UNAVAILABLE: 503 from sk-test-sentinel-0987654321"
    );
    assertFalse(network.jobMessage().contains("sk-"));
    assertFalse(network.jobMessage().contains("PROVIDER_UNAVAILABLE"));
    assertTrue(network.jobMessage().contains("FactExtraction"));

    var timeout = new StageFailure(
      "Synthesis",
      StageFailure.Kind.TIMEOUT,
      "TIMEOUT"
    );
    assertTrue(timeout.jobMessage().contains("超时"));
    var missing = new StageFailure(
      "EpistemicGate",
      StageFailure.Kind.MISSING_ARTIFACT,
      "no facts"
    );
    assertTrue(missing.jobMessage().contains("EpistemicGate"));
    var invalid = new StageFailure(
      "Projection",
      StageFailure.Kind.VALIDATION_FAILED,
      "Gate D"
    );
    assertFalse(invalid.jobMessage().isBlank());
  }
}
