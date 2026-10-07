package com.signalframe.ai.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.application.observability.ModelObservabilityService;
import com.signalframe.ai.domain.observability.*;
import com.signalframe.contract.*;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Audit aggregation: unknown usage and cost stay unknown, rates and retries are
 * correct, and recorded identifiers are never merged across providers.
 */
class ModelObservabilityServiceTest {

  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  static ModelRun run(
    UUID jobId,
    String provider,
    String model,
    ModelPurpose purpose,
    String promptVersion,
    String status,
    String errorType,
    Long input,
    Long output,
    Long total,
    Double cost,
    long latencyMs,
    long offsetMs
  ) {
    var startedAt = T0.plusMillis(offsetMs);
    return new ModelRun(
      UUID.randomUUID(),
      jobId,
      provider,
      model,
      purpose,
      promptVersion,
      startedAt,
      startedAt.plusMillis(latencyMs),
      latencyMs,
      input,
      output,
      total,
      cost,
      status,
      errorType,
      "request-" + offsetMs
    );
  }

  static ModelObservabilityService service(List<ModelRun> runs) {
    return new ModelObservabilityService(new FakeQuery(runs));
  }

  static final class FakeQuery implements ModelObservabilityQuery {

    private final List<ModelRun> runs;

    FakeQuery(List<ModelRun> runs) {
      this.runs = List.copyOf(runs);
    }

    @Override
    public List<ModelRun> runs(ModelRunFilter filter, int limit) {
      return runs
        .stream()
        .filter(run -> matches(run, filter))
        .limit(limit)
        .toList();
    }

    private static boolean matches(ModelRun run, ModelRunFilter filter) {
      if (filter.from() != null && run.startedAt().isBefore(filter.from())) return false;
      if (filter.to() != null && !run.startedAt().isBefore(filter.to())) return false;
      if (filter.jobId() != null && !filter.jobId().equals(run.jobId())) return false;
      if (filter.provider() != null && !filter.provider().equals(run.provider())) return false;
      if (filter.model() != null && !filter.model().equals(run.model())) return false;
      return filter.purpose() == null || filter.purpose() == run.purpose();
    }
  }

  @Test
  void unknownUsageAndCostStayUnknownInsteadOfBecomingZero() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "openai-compatible", "remote-model", ModelPurpose.SYNTHESIS, "synthesis-v1", "FAILED", "TIMEOUT", null, null, null, null, 900, 0)
      )
    );

    var summary = service.summary(ModelRunFilter.all());
    assertEquals(1, summary.runs());
    assertEquals(1, summary.failed());
    assertNull(summary.inputTokens());
    assertNull(summary.outputTokens());
    assertNull(summary.totalTokens());
    assertNull(summary.estimatedCost());
    assertEquals(1, summary.unknownUsageRuns());
    assertEquals(1, summary.unknownCostRuns());
  }

  @Test
  void offlineRunsReportExplicitZeroCostAndUsage() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 120, 0)
      )
    );

    var summary = service.summary(ModelRunFilter.all());
    assertEquals(0L, summary.totalTokens().longValue());
    assertEquals(0.0, summary.estimatedCost());
    assertEquals(0, summary.unknownUsageRuns());
    assertEquals(0, summary.unknownCostRuns());
    assertEquals(1.0, summary.successRate(), 1e-9);
    assertEquals(0.0, summary.errorRate(), 1e-9);
  }

  @Test
  void partialTotalsSumKnownValuesAndAreFlagged() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "openai-compatible", "remote-model", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 10L, 5L, 15L, 0.5, 100, 0),
        run(job, "openai-compatible", "remote-model", ModelPurpose.SYNTHESIS, "synthesis-v1", "FAILED", "TIMEOUT", null, null, null, null, 300, 10)
      )
    );

    var summary = service.summary(ModelRunFilter.all());
    assertEquals(2, summary.runs());
    assertEquals(10L, summary.inputTokens().longValue());
    assertEquals(5L, summary.outputTokens().longValue());
    assertEquals(15L, summary.totalTokens().longValue());
    assertEquals(0.5, summary.estimatedCost());
    assertEquals(1, summary.unknownUsageRuns());
    assertEquals(1, summary.unknownCostRuns());
  }

  @Test
  void retriesAreCountedPerAttemptChain() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "openai-compatible", "remote-model", ModelPurpose.SYNTHESIS, "synthesis-v1", "FAILED", "PROVIDER_UNAVAILABLE", null, null, null, null, 50, 0),
        run(job, "openai-compatible", "remote-model", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 10L, 5L, 15L, 0.2, 70, 20)
      )
    );

    var summary = service.summary(ModelRunFilter.all());
    assertEquals(2, summary.runs());
    assertEquals(1, summary.succeeded());
    assertEquals(1, summary.failed());
    assertEquals(1, summary.retries());
    assertEquals(1, summary.jobs());
    assertEquals(0.5, summary.successRate(), 1e-9);
    assertEquals(0.5, summary.errorRate(), 1e-9);
  }

  @Test
  void separateAttemptsOfDifferentJobsAreNotRetries() {
    var service = service(
      List.of(
        run(UUID.randomUUID(), "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, 0),
        run(UUID.randomUUID(), "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, 1)
      )
    );
    assertEquals(0, service.summary(ModelRunFilter.all()).retries());
    assertEquals(2, service.summary(ModelRunFilter.all()).jobs());
  }

  @Test
  void switchingModelIsNotCountedAsARetry() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "openai-compatible", "remote-model-a", ModelPurpose.SYNTHESIS, "synthesis-v1", "FAILED", "TIMEOUT", null, null, null, null, 10, 0),
        run(job, "openai-compatible", "remote-model-b", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 5L, 5L, 10L, 0.1, 10, 20)
      )
    );
    assertEquals(0, service.summary(ModelRunFilter.all()).retries());
  }

  @Test
  void recordedProviderAndModelIdentifiersAreNeverMerged() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, 0),
        run(job, "openai-compatible", "remote-model", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 100L, 50L, 150L, 0.9, 20, 5)
      )
    );

    var groups = service.aggregates(
      ModelRunFilter.all(),
      ModelRunGrouping.PROVIDER_MODEL
    );
    assertEquals(2, groups.size());
    var offline = groups
      .stream()
      .filter(g -> "mock".equals(g.provider()))
      .findFirst()
      .orElseThrow();
    var remote = groups
      .stream()
      .filter(g -> "openai-compatible".equals(g.provider()))
      .findFirst()
      .orElseThrow();
    assertEquals("mock-v1", offline.model());
    assertEquals(0.0, offline.estimatedCost());
    assertEquals("remote-model", remote.model());
    assertEquals(150L, remote.totalTokens().longValue());
    assertEquals(0.9, remote.estimatedCost());
    assertNotEquals(offline.group(), remote.group());
  }

  @Test
  void groupingByPurposeAndPromptVersionUsesStableKeys() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, 0),
        run(job, "mock", "mock-v1", ModelPurpose.CLASSIFICATION, "classification-v1", "FAILED", "OUTPUT_INVALID", null, null, null, null, 10, 1)
      )
    );

    var byPurpose = service.aggregates(
      ModelRunFilter.all(),
      ModelRunGrouping.PURPOSE
    );
    assertEquals(
      List.of("CLASSIFICATION", "SYNTHESIS"),
      byPurpose.stream().map(ModelRunAggregate::group).toList()
    );
    var byPrompt = service.aggregates(
      ModelRunFilter.all(),
      ModelRunGrouping.PROMPT_VERSION
    );
    assertEquals(
      List.of("classification-v1", "synthesis-v1"),
      byPrompt.stream().map(ModelRunAggregate::group).toList()
    );
  }

  @Test
  void jobFilterAndJobGroupingCorrelate() {
    var target = UUID.randomUUID();
    var other = UUID.randomUUID();
    var all = List.of(
      run(target, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, 0),
      run(other, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, 1)
    );
    var service = service(all);

    var filtered = service.report(
      ModelRunFilter.job(target),
      ModelRunGrouping.JOB
    );
    assertEquals(1, filtered.summary().runs());
    assertEquals(target, filtered.groups().getFirst().jobId());
    assertEquals(target.toString(), filtered.groups().getFirst().group());
    assertEquals(1, filtered.summary().jobs());

    assertEquals(
      2,
      service.summary(ModelRunFilter.all().withProviderModel("mock", "mock-v1")).runs()
    );
    assertEquals(
      0,
      service.summary(ModelRunFilter.all().withProviderModel("other", "other-model")).runs()
    );
    assertEquals(
      2,
      service
        .summary(ModelRunFilter.all().withPurpose(ModelPurpose.SYNTHESIS))
        .runs()
    );
  }

  @Test
  void latencyTotalsAndAveragesAreRecorded() {
    var job = UUID.randomUUID();
    var service = service(
      List.of(
        run(job, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 100, 0),
        run(job, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 300, 10)
      )
    );
    var summary = service.summary(ModelRunFilter.all());
    assertEquals(400, summary.latencyMsTotal());
    assertEquals(300, summary.latencyMsMax());
    assertEquals(200, summary.averageLatencyMs());
  }

  @Test
  void emptySelectionProducesNullTotalsAndNoGroups() {
    var service = service(List.of());
    var report = service.report(
      ModelRunFilter.all(),
      ModelRunGrouping.PROVIDER_MODEL
    );
    assertEquals(0, report.summary().runs());
    assertNull(report.summary().totalTokens());
    assertNull(report.summary().estimatedCost());
    assertEquals(0.0, report.summary().successRate(), 1e-9);
    assertTrue(report.groups().isEmpty());
    assertFalse(report.truncated());
    assertEquals(ModelObservabilityService.DEFAULT_ROW_LIMIT, report.rowLimit());
  }

  @Test
  void reportIsTruncatedFlaggedWhenTheRowLimitIsReached() {
    var job = UUID.randomUUID();
    var runs = new ArrayList<ModelRun>();
    for (int i = 0; i < 5; i++) runs.add(
      run(job, "mock", "mock-v1", ModelPurpose.SYNTHESIS, "synthesis-v1", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 10, i)
    );
    var service = new ModelObservabilityService(new FakeQuery(runs), 5);
    var report = service.report(
      ModelRunFilter.all(),
      ModelRunGrouping.PROVIDER_MODEL
    );
    assertEquals(5, report.summary().runs());
    assertTrue(report.truncated());
  }

  @Test
  void aggregateSurfaceExposesIdentifiersAndMetricsOnly() {
    for (var type : List.of(
      ModelRunAggregate.class,
      ModelRunSummary.class,
      ModelObservabilityReport.class
    )) {
      var names = Arrays
        .stream(type.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
      for (var forbidden : List.of(
        "prompt",
        "apiKey",
        "apiKeyEnv",
        "authorization",
        "content",
        "messages",
        "body"
      )) assertFalse(
        names.contains(forbidden),
        type.getSimpleName() + " must not expose " + forbidden
      );
    }
  }
}
