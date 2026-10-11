package com.signalframe.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.OpenAiStubServer;
import com.signalframe.ai.domain.*;
import com.signalframe.analysis.application.steps.*;
import com.signalframe.analysis.support.PipelineFixtures;
import com.signalframe.contract.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Offline rehearsal of the stage-1-only probe.
 *
 * <p>The live probe is opt-in, so the machinery it depends on is proved here against
 * a local stub: exactly one provider request, stage 2 never entered, the pipeline
 * never started, and one truthful {@code ModelRun} row. The probe and the rehearsal
 * call the same {@code factsOnly} entry point, so what passes here is what the live
 * run executes.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "ai.two-stage.max-provider-calls=1",
    "ai.two-stage.facts.max-attempts=1",
    "ai.two-stage.facts.max-repair-attempts=0",
    "ai.two-stage.facts.max-tokens=8192",
    "ai.two-stage.facts.timeout=120",
  }
)
class Stage1ProbeRehearsalTest {

  static final String SENTINEL_KEY = "sk-stage1-rehearsal-sentinel-8642";
  static final String STUB_MODEL = "stub-stage1-model";
  static final String NEWS_TEXT = "某公司宣布其专利许可协议将于本季度到期，双方已启动续约费率谈判。";

  /** The exact span the stub quotes: a real prefix of the analyzed text. */
  static final String QUOTED_SPAN = NEWS_TEXT.substring(0, NEWS_TEXT.indexOf("双方"));

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static final OpenAiStubServer stub;

  /** The reply to serve, primed by the test from the news it actually analyzes. */
  static final java.util.concurrent.atomic.AtomicReference<String> canned =
    new java.util.concurrent.atomic.AtomicReference<>();

  static {
    postgres.start();
    try {
      stub = new OpenAiStubServer(body -> {
        var next = canned.get();
        if (next == null) return OpenAiStubServer.Reply.status(
          503,
          OpenAiStubServer.error(503, "stub not primed")
        );
        return OpenAiStubServer.Reply.ok(
          OpenAiStubServer.completion(next, 900, 120)
        );
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A facts reply built from the news the probe analyzes. */
  static String factsReply(NewsItem news) {
    var text = QUOTED_SPAN;
    return (
      "{\"facts\":[{\"id\":\"aaaaaaaa-0000-4000-8000-000000000001\",\"statement\":" +
      PipelineFixtures.json().write(text) +
      ",\"reasoning\":\"the source states this\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() +
      "\",\"quote\":" +
      PipelineFixtures.json().write(text) +
      ",\"startOffset\":0,\"endOffset\":" +
      text.length() +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}"
    );
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("ai.profiles.[analysis.fast].provider", () -> "openai-compatible");
    registry.add("ai.profiles.[analysis.fast].model", () -> STUB_MODEL);
    registry.add("ai.profiles.[analysis.fast].base-url", stub::baseUrl);
    registry.add("ai.routes.FACT_EXTRACTION", () -> "analysis.fast");
  }

  @TestConfiguration
  static class ProbeStubs {

    @Bean
    @Primary
    ModelCredentialSource testCredentialSource() {
      return name -> Optional.of(SENTINEL_KEY);
    }
  }

  @Autowired
  TwoStageSynthesisService twoStage;

  @Autowired
  ModelRunRepository runs;

  @Autowired
  StageResponseReader reader;

  @Autowired
  com.signalframe.news.application.NewsService newsService;

  @Autowired
  com.signalframe.jobs.domain.JobRepository jobs;

  @BeforeEach
  void reset() {
    stub.clear();
    canned.set(null);
  }

  @Test
  void theProbeIssuesExactlyOneRequestAndNeverEntersStageTwo() {
    var news = newsService.create(new NewsInput(null, NEWS_TEXT, "stage1 rehearsal"));
    canned.set(factsReply(news));
    var job = jobs.create(news.id(), "stage1-rehearsal");
    var state = new SourceAssessmentStage().execute(
      PipelineState.initial(
        job.id(),
        "stage1-rehearsal",
        news,
        java.time.Instant.now()
      )
    );

    var probe = twoStage.factsOnly(state.jobId(), state.correlationId(), state);

    assertEquals(1, probe.providerCalls(), "exactly one provider request");
    assertEquals(1, stub.bodies().size(), "the stub saw exactly one request");
    assertEquals(1, probe.facts().size(), "one fact was validated");
    var ref = probe.facts().getFirst().sourceRefs().getFirst();
    assertEquals(
      NEWS_TEXT.substring(ref.startOffset(), ref.endOffset()),
      ref.quote(),
      "the quote is the exact UTF-16 span of the analyzed text"
    );
    assertEquals(news.source().id(), ref.sourceId());
    assertTrue(probe.unknowns().isEmpty());
    assertEquals("two-stage-facts-v1", probe.promptVersion());
    assertEquals(8192, probe.profile().maxTokens(), "the probe's own ceiling");
    assertEquals(120, probe.profile().timeout(), "the probe's own bound");

    // Only the facts stage ran: no second stage, no pipeline, one audit row.
    var audited = runs.recent(state.jobId());
    assertEquals(1, audited.size(), "one attempt, one audit row");
    assertEquals(ModelPurpose.FACT_EXTRACTION, audited.getFirst().purpose());
    assertEquals("two-stage-facts-v1", audited.getFirst().promptVersion());
    assertEquals("SUCCEEDED", audited.getFirst().status());
    assertTrue(audited.getFirst().latencyMs() >= 0);
    assertNull(audited.getFirst().estimatedCost(), "cost stays unknown");
    assertEquals(
      1020L,
      audited.getFirst().totalTokens().longValue(),
      "the stub reported 900 input + 120 output; usage is carried honestly"
    );

    // The request carried the stage-1 prompt, not a monolithic or stage-2 prompt.
    var sent = stub.bodies().getFirst();
    assertTrue(sent.contains("fact extraction stage"), sent.substring(0, 160));
    assertFalse(sent.contains("analysis stage of a research pipeline"));
    assertTrue(sent.contains("\"max_tokens\":8192"), "the probe ceiling reaches the wire");
    assertEquals("SignalFrame/0.1.0", stub.header(0, "User-Agent"));
  }

  /** The reader still validates the same reply offline, unchanged. */
  @Test
  void theRehearsalReplyPassesTheUnchangedFactsReader() {
    var news = newsService.create(new NewsInput(null, NEWS_TEXT, "stage1 reader"));
    var state = new SourceAssessmentStage().execute(
      PipelineState.initial(UUID.randomUUID(), "stage1-reader", news, java.time.Instant.now())
    );
    var text = QUOTED_SPAN;
    var raw =
      "{\"facts\":[{\"id\":\"aaaaaaaa-0000-4000-8000-000000000001\"," +
      "\"statement\":" + PipelineFixtures.json().write(text) +
      ",\"reasoning\":\"reported\",\"sourceRefs\":[{\"sourceId\":\"" +
      news.source().id() + "\",\"quote\":" + PipelineFixtures.json().write(text) +
      ",\"startOffset\":0,\"endOffset\":" + text.length() +
      "}],\"verificationStatus\":\"REPORTED\"}],\"unknowns\":[]}";

    var artifacts = reader.facts(raw, state);

    assertEquals(1, artifacts.value().size());
    assertFalse(artifacts.demo());
  }
}
