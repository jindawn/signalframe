package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.news.application.NewsService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Opt-in live probe of the stage-1 fact contract against the configured provider.
 *
 * <p>It runs the fact contract and nothing else: no stage 2, no assembly, no analysis
 * pipeline, no other job. The single-shot policy ({@code max-attempts=1},
 * {@code max-repair-attempts=0}) plus a provider-call ceiling of one make the
 * one-request bound structural rather than a hope.
 *
 * <p>Disabled unless {@code AI_STAGE1_LIVE_PROBE=true}, so a normal build and CI never
 * contact a provider. The credential is resolved from the environment by the existing
 * credential source; it is never read, printed or stored here.
 *
 * <pre>
 * AI_STAGE1_LIVE_PROBE=true AI_FAST_PROVIDER=openai-compatible \
 * AI_FAST_MODEL=&lt;model id&gt; AI_FAST_BASE_URL=&lt;api root&gt; AI_FAST_API_KEY=&lt;secret&gt; \
 * ./scripts/mvn.sh -B -f apps/api/pom.xml test -Dtest=Stage1LiveProbeTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AI_STAGE1_LIVE_PROBE", matches = "(?i)true")
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.NONE,
  properties = {
    "ai.two-stage.max-provider-calls=1",
    "ai.two-stage.facts.max-attempts=1",
    "ai.two-stage.facts.max-repair-attempts=0",
    "ai.two-stage.facts.max-tokens=8192",
    "ai.two-stage.facts.timeout=120",
    "ai.client.reasoning-effort.FACT_EXTRACTION=low",
  }
)
class Stage1LiveProbeTest {

  /** Short, non-sensitive, fact-shaped Chinese fixture, clearly marked as test data. */
  static final String NEWS_TEXT =
    "【测试数据·非真实新闻】某公司于本周二发布公告称，其与一家供应商签署的零部件采购协议将于本季度到期，" +
    "双方已就续约价格启动谈判。公告同时提到，现有协议覆盖三种规格的零部件，年采购金额约为两亿元。";

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  static String env(String name) {
    var value = System.getenv(name);
    return value == null ? "" : value.trim();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("ai.profiles.[analysis.fast].provider", () -> env("AI_FAST_PROVIDER"));
    registry.add("ai.profiles.[analysis.fast].model", () -> env("AI_FAST_MODEL"));
    registry.add("ai.profiles.[analysis.fast].base-url", () -> env("AI_FAST_BASE_URL"));
    registry.add("ai.routes.FACT_EXTRACTION", () -> "analysis.fast");
  }

  @Autowired
  TwoStageSynthesisService twoStage;

  @Autowired
  ModelRunRepository runs;

  @Autowired
  NewsService newsService;

  @Autowired
  JobRepository jobs;

  @Test
  void oneFactContractCallAgainstTheConfiguredProvider() {
    var news = newsService.create(
      new NewsInput(null, NEWS_TEXT, "stage 1 live probe (test data)")
    );
    var job = jobs.create(news.id(), "stage1-live-probe");
    var state = new SourceAssessmentStage().execute(
      PipelineState.initial(job.id(), "stage1-live-probe", news, Instant.now())
    );

    var probe = twoStage.factsOnly(state.jobId(), state.correlationId(), state);

    // ---- the measurements, printed so they can be reported verbatim ---------
    var audited = runs.recent(job.id());
    assertEquals(1, audited.size(), "one attempt, one audit row");
    var run = audited.getFirst();
    System.out.println("PROBE provider=" + run.provider() + " model=" + run.model());
    System.out.println(
      "PROBE status=" + run.status() + " errorType=" + run.errorType() +
      " latencyMs=" + run.latencyMs()
    );
    System.out.println(
      "PROBE inputTokens=" + token(run.inputTokens()) +
      " outputTokens=" + token(run.outputTokens()) +
      " totalTokens=" + token(run.totalTokens()) +
      " estimatedCost=" + token(run.estimatedCost())
    );
    System.out.println(
      "PROBE finishReason=UNKNOWN (the adapter does not surface it; an empty " +
      "completion would be reported as PROVIDER_RESPONSE_INVALID instead)"
    );
    System.out.println(
      "PROBE promptVersion=" + run.promptVersion() + " providerCalls=" + probe.providerCalls() +
      " maxTokens=" + probe.profile().maxTokens() + " timeout=" + probe.profile().timeout()
    );
    System.out.println("PROBE facts=" + probe.facts().size() + " unknowns=" + probe.unknowns().size());

    // ---- PROVIDER PASS -----------------------------------------------------
    assertEquals("SUCCEEDED", run.status(), "the provider call succeeded: " + run.errorType());
    assertNull(run.errorType());
    assertTrue(run.latencyMs() > 0, "latency is measured");
    assertTrue(run.latencyMs() <= 120_000, "inside the configured bound");
    assertEquals(probe.profile().provider(), run.provider());
    assertEquals(probe.profile().model(), run.model());
    assertEquals(1, probe.providerCalls(), "exactly one provider request");

    // ---- SCHEMA PASS -------------------------------------------------------
    assertFalse(probe.facts().isEmpty(), "the reply carried at least one fact");
    for (var fact : probe.facts()) {
      assertNotNull(fact.id(), "every fact has an id");
      assertFalse(fact.statement().isBlank(), "every fact has a statement");
      assertFalse(fact.sourceRefs().isEmpty(), "every fact has a source ref (EP-05)");
      System.out.println(
        "PROBE fact id=" + fact.id() +
        " verificationStatus=" + fact.verificationStatus() +
        " statement=" + fact.statement()
      );
    }

    // ---- PROVENANCE PASS ---------------------------------------------------
    for (var fact : probe.facts()) {
      for (var ref : fact.sourceRefs()) {
        assertEquals(
          news.source().id(),
          ref.sourceId(),
          "every ref points at the analyzed source (PR-01)"
        );
        assertTrue(ref.startOffset() >= 0 && ref.endOffset() <= NEWS_TEXT.length());
        assertEquals(
          NEWS_TEXT.substring(ref.startOffset(), ref.endOffset()),
          ref.quote(),
          "every quote is the exact UTF-16 span it claims (PR-07/PR-10)"
        );
        assertTrue(
          fact.statement().equals(ref.quote()),
          "the statement is one of its own quoted spans (STG-03)"
        );
        System.out.println(
          "PROBE ref sourceId=" + ref.sourceId() +
          " offsets=" + ref.startOffset() + "-" + ref.endOffset() +
          " quote=" + ref.quote()
        );
      }
    }

    System.out.println("PROBE PROVIDER=PASS SCHEMA=PASS PROVENANCE=PASS");
    System.out.println(
      "PROBE NOTE stage 1 only: stage 2 and the analysis pipeline were never entered"
    );
  }

  private static String token(Object value) {
    return value == null ? "UNKNOWN" : value.toString();
  }
}
