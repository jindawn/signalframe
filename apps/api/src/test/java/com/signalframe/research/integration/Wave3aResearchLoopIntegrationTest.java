package com.signalframe.research.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.research.domain.hypotheses.TransitionEventText;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Wave 3A gate: the complete research loop, driven end to end from a snapshot that
 * a <em>real analysis job</em> produced.
 *
 * <p>{@code Wave2BResearchLoopIntegrationTest} proves the loop's own invariants
 * against a hand-seeded snapshot. What no test proved before this class is the
 * seam that a sustained research loop actually depends on: news ingestion → the
 * real pipeline → the real {@code hypotheses} rows it persists → research events
 * on those rows → the timeline. Everything below therefore starts from
 * {@code POST /api/v1/news} and {@code POST /api/v1/news/{id}/analyze} and takes
 * the hypothesis id from the persisted analysis, so a research object the pipeline
 * writes in a shape the engine cannot read fails here and nowhere else.
 *
 * <p>The provider is the offline mock adapter (the default configuration), so the
 * fixture is deterministic and the test spends no money. Every write still goes
 * through the production controller, service, frozen port, JDBC repository, Flyway
 * V1→V4 schema and deterministic rubric on a real PostgreSQL 17.6 container.
 *
 * <p>Two properties are asserted that the seeded harness could not assert: the
 * offsets the pipeline stores are UTF-16 code units of the exact source text (the
 * fixture contains an astral character, which a code-point or byte based offset
 * would shift), and the hypothesis the pipeline persists starts at
 * {@code version = 0} with exactly one {@code CREATED} event, which is the state
 * the optimistic lock depends on.
 */
class Wave3aResearchLoopIntegrationTest extends Wave2BIntegrationSupport {

  /**
   * Deterministic fixture. The astral character (U+20BB7, two UTF-16 code units)
   * sits in the leading document marker on purpose: any offset computed in code
   * points or bytes instead of UTF-16 code units shifts every span that follows it.
   */
  static final String NEWS_TEXT =
    "文件编号𠮷A-2026。某公司发布公告称，其与一家海外芯片厂商的专利许可协议将于本季度到期，" +
    "双方已就续约费率启动谈判。该协议覆盖三项标准必要专利组合。" +
    "公告称，若续约费率上调超过10%，该公司预计将上调其终端产品售价。" +
    "另一家研究机构指出，谈判结果可能影响未来两年的行业授权成本。";

  /** A second, independent source; only independence can release rubric cap CAP-A. */
  static final String INDEPENDENT_TEXT =
    "独立通讯社报道：该公司的供应链文件显示，专利许可成本在其单位成本中的占比约为4%。";

  /** U+20BB7 as a UTF-16 surrogate pair; a char literal cannot hold an astral char. */
  static final String ASTRAL = "\uD842\uDFB7";

  /** Index of the astral character inside {@link #NEWS_TEXT}, in UTF-16 code units. */
  static final int ASTRAL_AT = NEWS_TEXT.indexOf(ASTRAL);

  // ---- the closed loop ---------------------------------------------------

  @Test
  void theResearchLoopRunsOnARealAnalysisSnapshot() throws Exception {
    var analysed = analyseFixture();
    var news = analysed.news();
    var analysis = analysed.analysis();
    var result = analysis.result();

    // ---- 1: the snapshot the analysis job produced ------------------------
    assertTrue(result.demo(), "the offline provider must mark its output as demo");
    assertFalse(result.modifiesExistingHypotheses());
    var assessment = result.confidenceAssessment();
    assertFalse(assessment.isProbability());
    assertEquals(ConfidenceMethod.RUBRIC, assessment.method());
    assertEquals("0.1", assessment.rubricVersion());
    assertEquals("0.1", result.protocolVersion());
    assertNotNull(result.provenance());
    assertFalse(
      result.provenance().promptVersions().isEmpty(),
      "PR-06/PR-16: a snapshot records the prompt versions that produced it"
    );
    assertFalse(result.facts().isEmpty(), "an analysis with no facts supports nothing");

    // ---- 2: provenance is exact and in UTF-16 code units ------------------
    for (var fact : result.facts()) {
      assertEquals(ClaimType.FACT, fact.type());
      assertFalse(fact.sourceRefs().isEmpty(), "every FACT needs a source span");
      for (var ref : fact.sourceRefs()) {
        assertEquals(news.source().id(), ref.sourceId());
        assertTrue(ref.endOffset() > ref.startOffset());
        assertEquals(
          NEWS_TEXT.substring(ref.startOffset(), ref.endOffset()),
          ref.quote(),
          "PR-07: the quote must be the exact UTF-16 span of the source text"
        );
      }
    }
    var spans = result
      .facts()
      .stream()
      .flatMap(fact -> fact.sourceRefs().stream())
      .map(ref -> ref.startOffset() + "-" + ref.endOffset())
      .toList();
    // The fixture only discriminates UTF-16 from code points if it really holds a
    // surrogate pair, and a stored span only proves the offset unit if it covers
    // that pair: a code-point offset would place the span two units early, a byte
    // offset far too late.
    assertTrue(
      NEWS_TEXT.length() > NEWS_TEXT.codePointCount(0, NEWS_TEXT.length()),
      "the fixture must contain an astral character"
    );
    boolean coversAstral = result
      .facts()
      .stream()
      .flatMap(fact -> fact.sourceRefs().stream())
      .anyMatch(ref ->
        ref.startOffset() <= ASTRAL_AT && ref.endOffset() >= ASTRAL_AT + ASTRAL.length()
      );
    assertTrue(
      coversAstral,
      "a stored span must cover the astral character at " +
      ASTRAL_AT +
      "; spans=" +
      spans
    );

    // ---- 3: the hypothesis the pipeline persisted -------------------------
    assertFalse(result.hypotheses().isEmpty());
    var hypothesis = result.hypotheses().getFirst();
    assertEquals(HypothesisStatus.OPEN, hypothesis.status());
    assertFalse(hypothesis.falsificationConditions().isEmpty());

    var detail = body(
      request("GET", "/api/v1/hypotheses/" + hypothesis.id(), null),
      HypothesisDetail.class
    );
    assertEquals(0L, detail.version(), "a pipeline-written hypothesis starts unversioned");
    assertEquals(1, detail.timeline().size(), "exactly the CREATED event");
    assertEquals("CREATED", detail.timeline().getFirst().eventType());
    assertNull(detail.timeline().getFirst().previousConfidence());
    assertEquals(hypothesis.confidence(), detail.timeline().getFirst().confidence());
    assertTrue(detail.evidence().isEmpty(), "nothing observed yet");
    assertTrue(detail.predictions().isEmpty());

    var initialConfidence = hypothesis.confidence();
    var factId = result.facts().getFirst().id();

    // ---- 4: SUPPORTS evidence from an independent, real source ------------
    var independentSource = createSource(INDEPENDENT_TEXT, "independent wire");
    var supportResponse = request(
      "POST",
      evidencePath(hypothesis.id()),
      new EvidenceCreateRequest(
        independentSource,
        "SUPPORTS",
        60,
        "an independent record corroborates the licensing-cost exposure",
        List.of(factId),
        analysis.id()
      )
    );
    assertEquals(201, supportResponse.statusCode(), supportResponse.body());
    var support = body(supportResponse, Evidence.class);

    // The referenced source and fact must really exist (PR-09).
    assertEquals(independentSource, support.sourceId());
    assertEquals(analysis.id(), support.analysisId());
    assertEquals(List.of(factId), support.factRefs());
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM sources WHERE id=?",
        Integer.class,
        independentSource
      ),
      "the evidence source FK must resolve to a stored source"
    );
    assertEquals(
      analysis.id(),
      db.queryForObject(
        "SELECT analysis_id FROM evidence WHERE id=?",
        UUID.class,
        support.id()
      ),
      "V4 traceability to the producing analysis"
    );

    // ---- 5: confidence is recomputed by the rubric, never chosen ----------
    var afterSupport = hypothesisRow(hypothesis.id());
    assertEquals(1L, afterSupport.version());
    assertEquals(HypothesisStatus.STRENGTHENING, afterSupport.status());
    assertTrue(afterSupport.supportingEvidenceRefs().contains(support.id()));

    var supportEvent = events(hypothesis.id()).get(1);
    var supportFacts = TransitionEventText
      .read(supportEvent.reason())
      .orElseThrow(() -> new AssertionError("the transition event carries no trailer"));
    assertEquals("EVIDENCE_ADDED", supportFacts.cause());
    assertEquals(support.id().toString(), supportFacts.ref());
    assertEquals(0L, supportFacts.previousVersion());
    assertEquals(1L, supportFacts.version());
    assertEquals(initialConfidence, supportFacts.previousScore());
    assertEquals(afterSupport.confidence(), supportFacts.score());
    assertEquals("0.1", supportFacts.rubric());
    assertEquals("RUBRIC", supportFacts.method());
    assertTrue(
      supportFacts.moved().contains("D3_INDEPENDENT_CORROBORATION"),
      "adding an independent source must name the dimension that moved: " +
      supportFacts.moved()
    );
    assertRubricShaped(supportFacts, afterSupport.confidence());

    // ---- 6: the same rubric inputs must reproduce the same score ----------
    var repeatResponse = request(
      "POST",
      evidencePath(hypothesis.id()),
      new EvidenceCreateRequest(
        independentSource,
        "SUPPORTS",
        61,
        "a second record from the same independent source",
        List.of(),
        null
      )
    );
    assertEquals(201, repeatResponse.statusCode(), repeatResponse.body());
    var afterRepeat = hypothesisRow(hypothesis.id());
    assertEquals(2L, afterRepeat.version());
    assertEquals(
      afterSupport.confidence(),
      afterRepeat.confidence(),
      "a second item from the same source adds no independent corroboration, so the " +
      "rubric must return the identical score: no hand-written delta exists"
    );

    // ---- 7: CONTRADICTS evidence from the snapshot's own source -----------
    var contradictResponse = request(
      "POST",
      evidencePath(hypothesis.id()),
      new EvidenceCreateRequest(
        news.source().id(),
        "CONTRADICTS",
        70,
        "the issuer's own disclosure states the cost under a different definition",
        List.of(),
        null
      )
    );
    assertEquals(201, contradictResponse.statusCode(), contradictResponse.body());
    var contradict = body(contradictResponse, Evidence.class);

    var afterContradiction = hypothesisRow(hypothesis.id());
    assertEquals(3L, afterContradiction.version());
    assertEquals(HypothesisStatus.WEAKENING, afterContradiction.status());
    assertTrue(
      afterContradiction.contradictingEvidenceRefs().contains(contradict.id()),
      "supporting and contradicting evidence must stay distinguishable"
    );
    assertRubricShaped(
      TransitionEventText
        .read(events(hypothesis.id()).get(3).reason())
        .orElseThrow(),
      afterContradiction.confidence()
    );

    // ---- 8: an OPEN prediction with a deadline and criteria ---------------
    var prediction = createPrediction(hypothesis.id(), factId, Instant.now().plus(Duration.ofDays(90)));
    assertEquals("OPEN", prediction.status());
    assertEquals("PREDICTION", prediction.type());
    assertTrue(prediction.expectedBy().isAfter(Instant.now()));
    assertFalse(prediction.verificationCriteria().isBlank());
    assertFalse(prediction.whereToCheck().isBlank());
    var predictionStatement = prediction.statement();
    var predictionCriteria = prediction.verificationCriteria();

    // ---- 9: a passed deadline is reported, never resolved automatically ---
    var dueNow = body(request("GET", "/api/v1/predictions/due", null), DuePredictions.class);
    assertTrue(
      dueNow.predictions().stream().noneMatch(item -> item.id().equals(prediction.id())),
      "a prediction whose deadline has not passed is not due"
    );

    var elapsed = createPrediction(
      hypothesis.id(),
      factId,
      Instant.now().plus(Duration.ofHours(1))
    );
    // Model the passage of time on the indexed deadline column only. The stored
    // payload is deliberately not rewritten (PR-17), which is exactly why the due
    // query is column-driven.
    db.update(
      "UPDATE predictions SET expected_by=? WHERE id=?",
      Timestamp.from(Instant.now().minus(Duration.ofHours(2))),
      elapsed.id()
    );
    var due = body(request("GET", "/api/v1/predictions/due", null), DuePredictions.class);
    assertTrue(
      due.predictions().stream().anyMatch(item -> item.id().equals(elapsed.id())),
      "a passed OPEN deadline must be reported"
    );
    assertEquals("OPEN", predictionStatus(elapsed.id()));
    assertNull(predictionVerifiedAt(elapsed.id()));

    // ---- 10: verification, reported to the engine through the frozen port --
    var operationId = UUID.randomUUID();
    var verificationCommand = new PredictionVerificationCommand(
      operationId,
      VerificationOutcome.CONFIRMED,
      "the next filing reported 4 percent, inside the pre-registered boundary",
      null,
      null
    );
    var verificationResponse = request(
      "POST",
      verificationPath(prediction.id()),
      verificationCommand
    );
    assertEquals(200, verificationResponse.statusCode(), verificationResponse.body());
    var verification = body(verificationResponse, PredictionVerificationResult.class);
    assertTrue(verification.applied());
    assertEquals("CONFIRMED", verification.prediction().status());
    assertNotNull(verification.verificationId());
    assertNotNull(predictionVerifiedAt(prediction.id()));

    assertTrue(verification.hypothesisTransition().applied());
    assertEquals(3L, verification.hypothesisTransition().previousVersion());
    assertEquals(4L, verification.hypothesisTransition().version());
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      verification.hypothesisTransition().status(),
      "no automatic truth: a CONFIRMED prediction must not confirm a hypothesis its own " +
      "source still contradicts (EPISTEMIC_TYPES §2.3)"
    );
    assertNotEquals(
      HypothesisStatus.CONFIRMED,
      hypothesisRow(hypothesis.id()).status()
    );

    // ---- 11: the prediction text is immutable after the fact -------------
    var afterVerification = body(
      request("GET", "/api/v1/hypotheses/" + hypothesis.id(), null),
      HypothesisDetail.class
    );
    var storedPrediction = afterVerification
      .predictions()
      .stream()
      .filter(item -> item.id().equals(prediction.id()))
      .findFirst()
      .orElseThrow();
    assertEquals(predictionStatement, storedPrediction.statement());
    assertEquals(predictionCriteria, storedPrediction.verificationCriteria());
    assertEquals("CONFIRMED", storedPrediction.status());

    // ---- 12: the complete, append-only research history -------------------
    var timeline = body(
      request("GET", timelinePath(hypothesis.id()), null),
      HypothesisTimeline.class
    );
    assertEquals(4L, timeline.version());
    assertEquals(
      5,
      timeline.items().size(),
      "CREATED plus three evidence events plus the verification event; " +
      "the version is 4 because CREATED does not move the optimistic lock"
    );
    assertEquals(HypothesisStatus.STRENGTHENING, timeline.status());
    assertScoreInRange(timeline.confidence());
    // A status movement is reported as STATUS_CHANGED (freeze §1.2), so the timeline
    // shows that the hypothesis moved rather than only that a number moved. The
    // second evidence event changes neither status nor score and keeps its cause.
    assertEquals(
      List.of(
        "CREATED",
        "STATUS_CHANGED",
        "EVIDENCE_ADDED",
        "STATUS_CHANGED",
        "STATUS_CHANGED"
      ),
      timeline.items().stream().map(HypothesisEvent::eventType).toList()
    );
    assertEquals(
      List.of(
        HypothesisStatus.OPEN,
        HypothesisStatus.STRENGTHENING,
        HypothesisStatus.STRENGTHENING,
        HypothesisStatus.WEAKENING
      ),
      timeline
        .items()
        .subList(1, 5)
        .stream()
        .map(HypothesisEvent::previousStatus)
        .toList()
    );
    assertEquals(
      List.of(
        HypothesisStatus.STRENGTHENING,
        HypothesisStatus.STRENGTHENING,
        HypothesisStatus.WEAKENING,
        HypothesisStatus.STRENGTHENING
      ),
      timeline
        .items()
        .subList(1, 5)
        .stream()
        .map(HypothesisEvent::status)
        .toList(),
      "the final transition returns the hypothesis to STRENGTHENING, never to CONFIRMED, " +
      "while its own source still contradicts it"
    );

    long previousVersion = 0L;
    Integer previousConfidence = null;
    for (var item : timeline.items()) {
      assertEquals(hypothesis.id(), item.hypothesisId());
      var facts = TransitionEventText.read(item.reason()).orElse(null);
      if (facts == null) {
        assertNull(previousConfidence, "the CREATED event has no previous score");
        previousConfidence = item.confidence();
        continue;
      }
      assertEquals(previousVersion, facts.previousVersion(), "version chain");
      assertEquals(previousVersion + 1, facts.version(), "exactly one bump per transition");
      assertEquals(previousConfidence, item.previousConfidence(), "previous score stays traceable");
      assertEquals(facts.score(), item.confidence());
      previousVersion = facts.version();
      previousConfidence = item.confidence();
    }
    assertEquals(4L, previousVersion);

    // ---- 13: a replay is answered, never applied twice -------------------
    var beforeReplay = eventPayloads(hypothesis.id());
    var replay = request("POST", verificationPath(prediction.id()), verificationCommand);
    assertEquals(200, replay.statusCode(), replay.body());
    var replayed = body(replay, PredictionVerificationResult.class);
    assertFalse(replayed.applied(), "a replay is not a second application");
    assertEquals(verification.verificationId(), replayed.verificationId());
    assertEquals(
      verification.hypothesisTransition().eventId(),
      replayed.hypothesisTransition().eventId()
    );
    assertEquals(4L, hypothesisRow(hypothesis.id()).version());
    assertEquals(beforeReplay, eventPayloads(hypothesis.id()), "append-only history");

    // ---- 14: the audit trail of the real analysis -----------------------
    var runs = body(
      request("GET", "/api/v1/model-runs?jobId=" + analysed.job().id(), null),
      ModelRun[].class
    );
    assertFalse(runs.length == 0, "every attempt must be audited");
    assertTrue(Arrays.stream(runs).allMatch(run -> "SUCCEEDED".equals(run.status())));
    assertTrue(Arrays.stream(runs).anyMatch(run -> "synthesis-v2".equals(run.promptVersion())));
    var audit = json.write(runs);
    assertFalse(audit.contains(NEWS_TEXT), "source text must never be audited");
    assertFalse(audit.contains(INDEPENDENT_TEXT), "source text must never be audited");
    assertFalse(audit.toLowerCase(Locale.ROOT).contains("apikey"), "no credential in the audit");
  }

  // ---- optimistic concurrency and rollback on a real snapshot ------------

  /**
   * A stale optimistic-lock token on a pipeline-written hypothesis writes nothing:
   * no status change, no version bump, no event.
   *
   * <p>The token used is a genuinely stale one — the version a caller read before
   * the evidence write bumped it — rather than an obviously bogus number, so this
   * is the lost-update race the freeze §4.2 requires to surface as a {@code 409}.
   */
  @Test
  void aStaleExpectedVersionOnARealSnapshotWritesNothing() throws Exception {
    var analysed = analyseFixture();
    var hypothesisId = analysed.analysis().result().hypotheses().getFirst().id();
    var staleToken = hypothesisRow(hypothesisId).version();
    assertEquals(0L, staleToken, "a pipeline-written hypothesis starts at version 0");

    var evidence = recordEvidence(hypothesisId, "SUPPORTS", 60);
    var before = hypothesisRow(hypothesisId);
    assertEquals(1L, before.version(), "recording evidence moves the version once");

    assertError(
      request(
        "POST",
        "/api/v1/hypotheses/" + hypothesisId + "/transitions",
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          staleToken,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          "a new record bears on this hypothesis",
          evidence,
          null,
          null
        )
      ),
      409,
      "VERSION_CONFLICT"
    );

    var after = hypothesisRow(hypothesisId);
    assertEquals(before.version(), after.version());
    assertEquals(before.confidence(), after.confidence());
    assertEquals(before.status(), after.status());
    assertEquals(2, countEvents(hypothesisId), "CREATED plus the one evidence event");
  }

  /**
   * The transaction proof on the real path: a verification against a hypothesis
   * that refuses the transition must roll the written verification record back, so
   * a prediction is never left decided without the transition that explains it.
   */
  @Test
  void aRefusedTransitionRollsBackTheVerificationOnARealSnapshot() throws Exception {
    var analysed = analyseFixture();
    var hypothesisId = analysed.analysis().result().hypotheses().getFirst().id();
    var prediction = createPrediction(
      hypothesisId,
      analysed.analysis().result().facts().getFirst().id(),
      Instant.now().plus(Duration.ofDays(30))
    );

    // Drive the hypothesis terminal through the real engine, then verify a
    // prediction against it: the engine refuses, and the record must roll back.
    db.update(
      "UPDATE hypotheses SET payload = jsonb_set(payload, '{status}', '\"REJECTED\"') WHERE id=?",
      hypothesisId
    );
    var payloadBefore = hypothesisPayload(hypothesisId);

    assertError(
      request(
        "POST",
        verificationPath(prediction.id()),
        new PredictionVerificationCommand(
          UUID.randomUUID(),
          VerificationOutcome.CONFIRMED,
          "the filing met the pre-registered boundary",
          null,
          null
        )
      ),
      422,
      "UNPROCESSABLE_TRANSITION"
    );

    assertEquals("OPEN", predictionStatus(prediction.id()));
    assertNull(predictionVerifiedAt(prediction.id()));
    assertEquals(0L, hypothesisRow(hypothesisId).version());
    assertEquals(1, countEvents(hypothesisId));
    assertEquals(payloadBefore, hypothesisPayload(hypothesisId));
  }

  // ---- helpers -----------------------------------------------------------

  private record Analysed(NewsItem news, AnalysisJob job, Analysis analysis) {}

  /** News → durable analysis job → persisted snapshot, through the real API. */
  private Analysed analyseFixture() throws Exception {
    var created = request(
      "POST",
      "/api/v1/news",
      new NewsInput(null, NEWS_TEXT, "Wave 3A research loop")
    );
    assertEquals(201, created.statusCode(), created.body());
    var news = body(created, NewsItem.class);
    assertEquals(NEWS_TEXT, news.source().text());
    assertEquals("PASTED", news.source().extractionStatus());

    var started = request("POST", "/api/v1/news/" + news.id() + "/analyze", null);
    assertEquals(202, started.statusCode(), started.body());
    var job = body(started, AnalysisJob.class);

    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (
      job.status() != JobStatus.COMPLETED &&
      job.status() != JobStatus.FAILED &&
      System.nanoTime() < deadline
    ) {
      Thread.sleep(75);
      job = body(
        request("GET", "/api/v1/analysis-jobs/" + job.id(), null),
        AnalysisJob.class
      );
    }
    assertEquals(JobStatus.COMPLETED, job.status(), job.error());
    assertNotNull(job.analysisId());
    var analysis = body(
      request("GET", "/api/v1/analyses/" + job.analysisId(), null),
      Analysis.class
    );
    assertEquals(news.id(), analysis.newsId());
    return new Analysed(news, job, analysis);
  }

  /** A real, persisted second source; independence is what releases cap CAP-A. */
  private UUID createSource(String text, String title) throws Exception {
    var response = request("POST", "/api/v1/news", new NewsInput(null, text, title));
    assertEquals(201, response.statusCode(), response.body());
    return body(response, NewsItem.class).source().id();
  }

  /** Evidence recorded through the API under the hypothesis's current version. */
  private UUID recordEvidence(UUID hypothesisId, String stance, int strength)
    throws Exception {
    var response = request(
      "POST",
      evidencePath(hypothesisId),
      new EvidenceCreateRequest(
        createSource(INDEPENDENT_TEXT, "independent wire"),
        stance,
        strength,
        "an independent record bears on this hypothesis",
        List.of(),
        null
      )
    );
    assertEquals(201, response.statusCode(), response.body());
    return body(response, Evidence.class).id();
  }

  private Prediction createPrediction(UUID hypothesisId, UUID factId, Instant expectedBy)
    throws Exception {
    var response = request(
      "POST",
      "/api/v1/hypotheses/" + hypothesisId + "/predictions",
      new PredictionCreateRequest(
        "the next filing reports a licensing cost below 5 percent of unit cost",
        "the licensing cost share in the next quarterly filing",
        expectedBy,
        "confirmation requires a reported share below 5 percent; rejection requires 5 percent or above",
        "the issuer's investor relations filings page",
        List.of(factId)
      )
    );
    assertEquals(201, response.statusCode(), response.body());
    return body(response, Prediction.class);
  }

  /**
   * Every stored score must be a rubric projection: the five profile dimensions
   * are present, each dimension's points are exactly {@code weight × level / 5},
   * the total is never below the capped score, and the band matches the score.
   * Gate D (CF-03/CF-08) is what this stands in for without re-deriving the cap set
   * from the snapshot.
   */
  private static void assertRubricShaped(TransitionEventText.Facts facts, int storedScore) {
    assertNotNull(facts.rubric(), "a rubric score must record its version");
    assertEquals("RUBRIC", facts.method());
    assertEquals(5, facts.dimensions().size(), "the snapshot profile has five dimensions");
    int total = 0;
    for (var dimension : facts.dimensions()) {
      int weight = switch (dimension.id()) {
        case "D1_SOURCE_QUALITY", "D3_INDEPENDENT_CORROBORATION", "D4_MECHANISM_SUPPORT" -> 20;
        case "D2_EVIDENCE_DIRECTNESS" -> 25;
        case "D5_COUNTER_EVIDENCE_RESILIENCE" -> 15;
        default -> fail("unknown rubric dimension: " + dimension.id());
      };
      assertTrue(dimension.level() >= 0 && dimension.level() <= 5);
      assertEquals(
        weight * dimension.level() / 5,
        dimension.points(),
        "points must be the exact weighted level of " + dimension.id()
      );
      total += dimension.points();
    }
    assertEquals(storedScore, facts.score(), "the event must report the stored score");
    assertTrue(
      storedScore <= total,
      "caps may only lower a score: stored " + storedScore + " > raw " + total
    );
    assertScoreInRange(storedScore);
    assertEquals(bandOf(storedScore), facts.band());
  }

  private static void assertScoreInRange(int score) {
    assertTrue(score >= 0 && score <= 100, "confidence is a 0–100 judgment");
  }

  /** CONFIDENCE_MODEL_V0_1 §4, written out so a drift in either would fail here. */
  private static String bandOf(int score) {
    if (score <= 29) return "VERY_LOW";
    if (score <= 49) return "LOW";
    if (score <= 69) return "MEDIUM";
    if (score <= 84) return "HIGH";
    return "VERY_HIGH";
  }

  private String hypothesisPayload(UUID hypothesisId) {
    return db.queryForObject(
      "SELECT payload::text FROM hypotheses WHERE id=?",
      String.class,
      hypothesisId
    );
  }

  private static String timelinePath(UUID hypothesisId) {
    return "/api/v1/hypotheses/" + hypothesisId + "/timeline";
  }

  private static String evidencePath(UUID hypothesisId) {
    return "/api/v1/hypotheses/" + hypothesisId + "/evidence";
  }

  private static String verificationPath(UUID predictionId) {
    return "/api/v1/predictions/" + predictionId + "/verification";
  }

  /** Method-agnostic request; the inherited helpers always send a body. */
  private HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + path)
    ).timeout(Duration.ofSeconds(20));
    if (body != null) builder.header("Content-Type", "application/json");
    return http.send(
      builder
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.write(body))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }
}
