package com.signalframe.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.AnalysisJobService;
import com.signalframe.contract.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * TASK-10: ingestion failure and restart recovery are honest, persisted states —
 * not request errors that leave no trace and not jobs that hang forever.
 *
 * <p>Assertions read PostgreSQL directly as well as over HTTP: the acceptance
 * criterion is that the state is <em>persisted</em>, which a response body alone
 * cannot prove.
 */
class FailureAndRecoveryIntegrationTest extends IntegrationFlowSupport {

  private static final Duration BUDGET = Duration.ofSeconds(30);

  /** RFC 2606 reserved TLD: name resolution always fails, so no fetch is attempted. */
  private static final String UNREACHABLE =
    "http://signalframe-unreachable.invalid/article";

  @Autowired
  AnalysisJobService jobService;

  @Test
  void unreachableHostIsPersistedAsNeedsTextWithAReasonCodeAndNoJob()
    throws Exception {
    var created = post("/api/v1/news", new NewsInput(UNREACHABLE, null, null));
    assertEquals(201, created.statusCode(), created.body());
    var news = body(created, NewsItem.class);

    assertEquals("NEEDS_TEXT", news.source().extractionStatus());
    assertTrue(
      news.source().message().startsWith("NETWORK_FAILURE"),
      "the diagnostic must lead with the stable reason code: " +
      news.source().message()
    );
    assertTrue(
      sourceText(news.source().id()).isBlank(),
      "a failed extraction must not persist an empty-body placeholder"
    );
    assertFalse(
      news.source().message().contains("signalframe-unreachable.invalid"),
      "the persisted diagnostic must not echo the URL or host: " +
      news.source().message()
    );

    var analyze = post("/api/v1/news/" + news.id() + "/analyze", null);
    assertError(analyze, 409, "NEEDS_TEXT");
    assertEquals(
      0,
      countRows("SELECT count(*) FROM analysis_jobs WHERE news_id=?", news.id()),
      "a refused analyze must not leave a job behind"
    );
  }

  @Test
  void pastedTextKeepsTheFailureCategoryAndStaysAnalyzable() throws Exception {
    var pasted = "补充正文：该企业宣布推理服务降价，原始公告仍需独立核实。";
    var created = post("/api/v1/news", new NewsInput(UNREACHABLE, pasted, null));
    assertEquals(201, created.statusCode(), created.body());
    var news = body(created, NewsItem.class);

    assertEquals("PASTED", news.source().extractionStatus());
    assertTrue(
      news.source().message().startsWith("NETWORK_FAILURE"),
      "the URL failure category must survive the paste: " +
      news.source().message()
    );
    assertEquals(pasted, news.source().text());
    assertEquals(pasted, sourceText(news.source().id()));

    var job = awaitTerminal(analyze(news.id()).id(), BUDGET);
    assertEquals(JobStatus.COMPLETED, job.status(), job.error());
    var analysis = body(
      get("/api/v1/analyses/" + job.analysisId()),
      Analysis.class
    );
    assertEquals(
      news.source().id(),
      analysis.result().facts().getFirst().sourceRefs().getFirst().sourceId(),
      "the pasted source must be the provenance of the analysis"
    );
  }

  @Test
  void blockedPrivateDestinationCapturesNoContentAndRefusesAnalysis()
    throws Exception {
    var created = post(
      "/api/v1/news",
      new NewsInput("http://127.0.0.1/private", null, null)
    );
    assertEquals(201, created.statusCode(), created.body());
    var news = body(created, NewsItem.class);

    assertEquals("NEEDS_TEXT", news.source().extractionStatus());
    assertTrue(
      news.source().message().startsWith("UNSAFE_DESTINATION"),
      news.source().message()
    );
    assertTrue(
      sourceText(news.source().id()).isBlank(),
      "a blocked destination must never contribute content"
    );
    assertError(
      post("/api/v1/news/" + news.id() + "/analyze", null),
      409,
      "NEEDS_TEXT"
    );
  }

  @Test
  void restartRecoveryFailsInFlightJobsAndKeepsFinishedWork() throws Exception {
    var finishedNews = newsText("重启恢复：已完成的分析必须保持完好。");
    var finished = awaitTerminal(analyze(finishedNews.id()).id(), BUDGET);
    assertNotNull(finished.analysisId());

    var queued = jobs.create(
      newsText("重启恢复：排队中的任务必须显式失败。").id(),
      "task10-recovery-queued"
    );
    var running = jobs.create(
      newsText("重启恢复：执行中的任务必须显式失败。").id(),
      "task10-recovery-running"
    );
    assertTrue(jobs.claim(running.id()), "the job must be claimable while QUEUED");

    // The exact path a real restart takes: the ApplicationReadyEvent listener.
    jobService.recover();

    for (var id : List.of(queued.id(), running.id())) {
      var job = body(get("/api/v1/analysis-jobs/" + id), AnalysisJob.class);
      assertEquals(JobStatus.FAILED, job.status(), job.error());
      assertEquals("服务重启中断了任务，请重新分析。", job.error());
      var last = job.events().getLast();
      assertEquals(JobStatus.FAILED, last.status());
      assertEquals("Recovery", last.step());

      // A browser reconnecting to an interrupted job must be released, not hung.
      var frames = readEvents(id, null);
      assertFalse(frames.isEmpty(), "the recovered job must stream its history");
      assertEquals(JobStatus.FAILED, event(frames.getLast(), json).status());
    }

    var stillFinished = body(
      get("/api/v1/analysis-jobs/" + finished.id()),
      AnalysisJob.class
    );
    assertEquals(JobStatus.COMPLETED, stillFinished.status());
    assertNull(stillFinished.error(), "recovery must not touch a completed job");
    assertEquals(finished.analysisId(), stillFinished.analysisId());
    assertEquals(
      1,
      countRows("SELECT count(*) FROM analyses WHERE job_id=?", finished.id()),
      "recovery must not delete a persisted analysis"
    );
  }
}
