package com.signalframe.ai.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.application.observability.ModelObservabilityService;
import com.signalframe.ai.domain.ModelRunRepository;
import com.signalframe.ai.domain.observability.*;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Reads the real audit table through the application datasource. Verifies the
 * SQL projection, the null-usage semantics and that no ingested source text
 * leaks into a report.
 */
@SpringBootTest
class ModelObservabilityJdbcTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  JdbcTemplate db;

  @Autowired
  ModelRunRepository runs;

  @Autowired
  ModelObservabilityService observability;

  @Autowired
  JsonCodec json;

  static final String SOURCE_TEXT = "审计表不得收集这段原始新闻正文。";

  UUID newJob(String correlationId) {
    UUID sourceId = UUID.randomUUID();
    UUID newsId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    db.update(
      "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES(?,?,?,?,?::jsonb,now())",
      sourceId,
      null,
      SOURCE_TEXT,
      "PASTED",
      "{}"
    );
    db.update(
      "INSERT INTO news_items(id,source_id,payload,created_at) VALUES(?,?,?::jsonb,now())",
      newsId,
      sourceId,
      "{}"
    );
    db.update(
      "INSERT INTO analysis_jobs(id,news_id,status,analysis_id,error,correlation_id,created_at,updated_at) VALUES(?,?,?,?,?,?,now(),now())",
      jobId,
      newsId,
      "QUEUED",
      null,
      null,
      correlationId
    );
    return jobId;
  }

  static ModelRun run(
    UUID jobId,
    String provider,
    String model,
    String status,
    String errorType,
    Long input,
    Long output,
    Long total,
    Double cost,
    long latencyMs,
    long offsetMs
  ) {
    var startedAt = Instant.parse("2026-01-01T00:00:00Z").plusMillis(offsetMs);
    return new ModelRun(
      UUID.randomUUID(),
      jobId,
      provider,
      model,
      ModelPurpose.SYNTHESIS,
      "synthesis-v1",
      startedAt,
      startedAt.plusMillis(latencyMs),
      latencyMs,
      input,
      output,
      total,
      cost,
      status,
      errorType,
      "corr-" + offsetMs
    );
  }

  @Test
  void persistedRunsAreAggregatedPerRecordedIdentifier() {
    var jobId = newJob("obs-jdbc-1");
    runs.save(run(jobId, "offline-audit", "offline-model", "SUCCEEDED", null, 0L, 0L, 0L, 0.0, 40, 0));
    runs.save(run(jobId, "remote-audit", "remote-model", "SUCCEEDED", null, 120L, 30L, 150L, 0.75, 60, 10));

    var report = observability.report(
      ModelRunFilter.job(jobId),
      ModelRunGrouping.PROVIDER_MODEL
    );

    assertEquals(2, report.summary().runs());
    assertEquals(2, report.summary().succeeded());
    assertEquals(0, report.summary().retries());
    assertEquals(150L, report.summary().totalTokens().longValue());
    assertEquals(0.75, report.summary().estimatedCost());
    assertEquals(100, report.summary().latencyMsTotal());
    assertEquals(60, report.summary().latencyMsMax());
    assertEquals(2, report.groups().size());
    var remote = report
      .groups()
      .stream()
      .filter(group -> "remote-audit".equals(group.provider()))
      .findFirst()
      .orElseThrow();
    assertEquals("remote-model", remote.model());
    assertEquals(150L, remote.totalTokens().longValue());
    assertEquals(1.0, remote.successRate(), 1e-9);
  }

  @Test
  void unknownUsageAndCostRemainNullInTheDatabaseAggregate() {
    var jobId = newJob("obs-jdbc-2");
    runs.save(run(jobId, "remote-audit", "remote-model", "FAILED", "TIMEOUT", null, null, null, null, 900, 0));

    var summary = observability.summary(ModelRunFilter.job(jobId));

    assertEquals(1, summary.runs());
    assertNull(summary.inputTokens());
    assertNull(summary.outputTokens());
    assertNull(summary.totalTokens());
    assertNull(summary.estimatedCost());
    assertEquals(1, summary.unknownUsageRuns());
    assertEquals(1, summary.unknownCostRuns());
    assertEquals(1.0, summary.errorRate(), 1e-9);
  }

  @Test
  void filtersAreAppliedByTheDatabase() {
    var first = newJob("obs-jdbc-3a");
    var second = newJob("obs-jdbc-3b");
    runs.save(run(first, "filter-audit", "filter-model", "SUCCEEDED", null, 5L, 5L, 10L, 0.1, 10, 0));
    runs.save(run(second, "filter-audit", "filter-model", "FAILED", "RATE_LIMITED", null, null, null, null, 20, 60_000));

    assertEquals(1, observability.summary(ModelRunFilter.job(first)).runs());
    assertEquals(1, observability.summary(ModelRunFilter.job(second)).runs());
    assertEquals(
      2,
      observability.summary(ModelRunFilter.all().withProviderModel("filter-audit", "filter-model")).runs()
    );
    assertEquals(
      1,
      observability.summary(ModelRunFilter.since(Instant.parse("2026-01-01T00:00:30Z")).withProviderModel("filter-audit", "filter-model")).runs()
    );
    assertEquals(
      2,
      observability
        .summary(ModelRunFilter.all().withPurpose(ModelPurpose.SYNTHESIS).withProviderModel("filter-audit", "filter-model"))
        .runs()
    );
  }

  @Test
  void rowLimitBoundsTheFetchedAuditWindow() {
    var jobId = newJob("obs-jdbc-4");
    for (int i = 0; i < 3; i++) runs.save(
      run(jobId, "limit-audit", "limit-model", "SUCCEEDED", null, 1L, 1L, 2L, 0.0, 5, i)
    );
    var limited = new ModelObservabilityService(
      new com.signalframe.ai.application.observability.JdbcModelRunObservabilityQuery(
        db,
        json
      ),
      2
    );
    var report = limited.report(
      ModelRunFilter.job(jobId),
      ModelRunGrouping.PROVIDER_MODEL
    );
    assertEquals(2, report.summary().runs());
    assertTrue(report.truncated());
  }

  @Test
  void reportContainsNoIngestedSourceTextOrCredential() {
    var jobId = newJob("obs-jdbc-5");
    runs.save(run(jobId, "redaction-audit", "redaction-model", "FAILED", "AUTHENTICATION", null, null, null, null, 7, 0));

    var report = observability.report(
      ModelRunFilter.job(jobId),
      ModelRunGrouping.PROVIDER_MODEL
    );
    String serialized = json.write(report);

    assertFalse(
      serialized.contains(SOURCE_TEXT),
      "audit aggregates must never collect ingested source text"
    );
    assertFalse(serialized.toLowerCase().contains("apikey"));
    assertFalse(serialized.toLowerCase().contains("authorization"));
    assertEquals(1, report.summary().failed());
  }
}
