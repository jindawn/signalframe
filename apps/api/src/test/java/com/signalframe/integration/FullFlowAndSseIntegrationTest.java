package com.signalframe.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * TASK-10: the integrated revision still reproduces the text → job → analysis →
 * timeline path, and the durable event stream survives a reconnect.
 *
 * <p>The browser suite proves the UI reopens the stream; this suite proves the
 * server-side contract the reconnect relies on: {@code Last-Event-ID} is a real
 * cursor, an already-delivered frame is never replayed, and the stream always
 * ends on a terminal frame instead of hanging.
 */
class FullFlowAndSseIntegrationTest extends IntegrationFlowSupport {

  private static final Duration BUDGET = Duration.ofSeconds(30);

  @Test
  void textToJobToAnalysisToTimelineIsReproducible() throws Exception {
    for (int run = 0; run < 2; run++) {
      var news = newsText(
        "复现性验证 " + run + "：公司宣布 AI 推理服务降价，原始公告仍需独立核实。"
      );
      var job = awaitTerminal(analyze(news.id()).id(), BUDGET);
      assertEquals(JobStatus.COMPLETED, job.status(), job.error());
      assertNotNull(job.analysisId());

      var sequences = job.events().stream().map(JobEvent::sequence).toList();
      for (int i = 1; i < sequences.size(); i++) assertTrue(
        sequences.get(i) > sequences.get(i - 1),
        "job event sequences must be strictly increasing: " + sequences
      );
      assertEquals(JobStatus.QUEUED, job.events().getFirst().status());
      assertEquals(JobStatus.COMPLETED, job.events().getLast().status());

      var analysis = body(
        get("/api/v1/analyses/" + job.analysisId()),
        Analysis.class
      );
      assertTrue(analysis.result().demo());
      assertFalse(analysis.result().confidenceAssessment().isProbability());
      assertEquals(ClaimType.FACT, analysis.result().facts().getFirst().type());
      assertEquals(
        news.source().id(),
        analysis
          .result()
          .facts()
          .getFirst()
          .sourceRefs()
          .getFirst()
          .sourceId(),
        "facts must cite the persisted source"
      );

      var hypothesisId = analysis.result().hypotheses().getFirst().id();
      var detail = body(
        get("/api/v1/hypotheses/" + hypothesisId),
        HypothesisDetail.class
      );
      assertEquals("CREATED", detail.timeline().getFirst().eventType());
      // The timeline is a stored read model: reading it twice must not rewrite it.
      HypothesisDetail reread = body(
        get("/api/v1/hypotheses/" + hypothesisId),
        HypothesisDetail.class
      );
      assertEquals(detail, reread, "the timeline must be stable across reads");

      assertTrue(
        get("/api/v1/news/" + news.id() + "/analyses")
          .body()
          .contains(analysis.id().toString())
      );
      var runs = get("/api/v1/model-runs?jobId=" + job.id()).body();
      assertTrue(runs.contains("SUCCEEDED"), runs);
      var lowered = runs.toLowerCase(Locale.ROOT);
      assertFalse(lowered.contains("apikey"), "audit must not carry credentials");
      assertFalse(lowered.contains("authorization"), runs);
      assertFalse(lowered.contains("bearer "), runs);
      assertFalse(lowered.contains("rawprompt"), runs);
    }
  }

  @Test
  void lastEventIdCursorReplaysOnlyUnseenEvents() throws Exception {
    var news = newsText("SSE 游标验证：重连的客户端不得重复收到已确认的事件。");
    var job = awaitTerminal(analyze(news.id()).id(), BUDGET);

    var all = readEvents(job.id(), null);
    assertEquals(
      job.events().size(),
      all.size(),
      "a fresh stream must deliver every persisted frame exactly once"
    );
    var allIds = ids(all);
    for (int i = 1; i < allIds.size(); i++) assertTrue(
      allIds.get(i) > allIds.get(i - 1),
      "frames must arrive in strictly increasing sequence order: " + allIds
    );
    assertEquals(
      job.events().stream().map(JobEvent::sequence).toList(),
      allIds,
      "the streamed frames must be exactly the persisted job's frames"
    );
    assertEquals(JobStatus.COMPLETED, event(all.getLast(), json).status());

    long cursor = allIds.get(allIds.size() / 2);
    var resumed = readEvents(job.id(), cursor);
    var resumedIds = ids(resumed);
    assertFalse(
      resumedIds.isEmpty(),
      "a reconnecting client must still receive the remaining frames"
    );
    for (long id : resumedIds) assertTrue(
      id > cursor,
      "frame " + id + " was replayed although the client already saw " + cursor
    );
    assertEquals(
      allIds.stream().filter(id -> id > cursor).toList(),
      resumedIds,
      "the reconnect must deliver exactly the frames after the cursor"
    );
    assertEquals(
      JobStatus.COMPLETED,
      event(resumed.getLast(), json).status(),
      "the resumed stream must still terminate on the terminal frame"
    );
  }

  @Test
  void reconnectDuringFlightEndsOnATerminalFrame() throws Exception {
    var news = newsText("中途重连：断线重连后必须收到终态事件，而不是永久挂起。");
    var started = analyze(news.id());
    var delivered = body(
      get("/api/v1/analysis-jobs/" + started.id()),
      AnalysisJob.class
    )
      .events()
      .getFirst()
      .sequence();

    // The cursor models a browser that already consumed the first frame (QUEUED)
    // and then lost the connection while the pipeline was still running.
    var frames = readEvents(started.id(), delivered);
    assertFalse(frames.isEmpty(), "the resumed stream must deliver the rest");
    var observed = ids(frames);
    for (long id : observed) assertTrue(
      id > delivered,
      "frame " + id + " must not be replayed after cursor " + delivered
    );
    assertEquals(
      observed.stream().sorted().toList(),
      observed,
      "frames must arrive in sequence order"
    );
    assertFalse(
      observed.contains(delivered),
      "the frame at the cursor must not be replayed"
    );

    var last = event(frames.getLast(), json);
    assertTrue(
      terminal(last.status()),
      "a stream must only close on a terminal frame, got " + last.status()
    );
    assertEquals(
      awaitTerminal(started.id(), BUDGET).status(),
      last.status(),
      "the stream terminal must agree with the persisted job"
    );
  }

  @Test
  void eventStreamForUnknownJobIs404() throws Exception {
    var response = get(
      "/api/v1/analysis-jobs/" + UUID.randomUUID() + "/events"
    );
    assertError(response, 404, "NOT_FOUND");
  }
}
