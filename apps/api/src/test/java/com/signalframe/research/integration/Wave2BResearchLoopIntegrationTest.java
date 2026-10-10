package com.signalframe.research.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.DuePredictions;
import com.signalframe.contract.Evidence;
import com.signalframe.contract.EvidenceCreateRequest;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTimeline;
import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.Prediction;
import com.signalframe.contract.PredictionCreateRequest;
import com.signalframe.contract.PredictionVerificationCommand;
import com.signalframe.contract.PredictionVerificationResult;
import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.domain.hypotheses.TransitionEventText;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The Wave 2B end-to-end acceptance surface: one deterministic news fixture driven
 * through the complete research loop, plus the negative cases that must leave no
 * inconsistent data behind.
 *
 * <p>Every step uses the production beans — the real controller, the real
 * {@code EvidencePredictionService}, the real {@code HypothesisTransitionService}
 * behind the frozen port, the real JDBC repositories, the real Flyway V1→V4 schema
 * and the real deterministic rubric. Nothing is mocked, so a disagreement between
 * TASK-06 and TASK-07 shows up here and nowhere else.
 */
class Wave2BResearchLoopIntegrationTest extends Wave2BIntegrationSupport {

  // ---- the closed loop ---------------------------------------------------

  /**
   * Steps 1–12 of the acceptance loop: create a hypothesis, read its initial
   * confidence and history, add SUPPORTS evidence, recompute confidence and record
   * an event, add CONTRADICTS evidence, record the new judgement, create an OPEN
   * prediction, find the due ones, verify it, move the hypothesis through the port,
   * read the whole timeline and check every reference and version.
   */
  @Test
  void theFullResearchLoopStaysConsistent() throws Exception {
    var seeded = seed();

    // 1–2: the hypothesis exists with one CREATED event and the seeded score.
    var initial = body(get(timelinePath(seeded)), HypothesisTimeline.class);
    assertEquals(seeded.hypothesisId(), initial.hypothesisId());
    assertEquals(0L, initial.version());
    assertEquals(HypothesisStatus.OPEN, initial.status());
    assertEquals(ResearchLoopFixtures.INITIAL_CONFIDENCE, initial.confidence());
    assertEquals(1, initial.items().size());
    assertEquals("CREATED", initial.items().get(0).eventType());

    // 3–4: an independent SUPPORTS item releases CAP-A and the rubric recomputes.
    var supportResponse = post(
      evidencePath(seeded),
      new EvidenceCreateRequest(
        seedIndependentSource("Independent Ledger"),
        "SUPPORTS",
        60,
        "an independent supplier filing corroborates the input-cost movement",
        List.of(),
        null
      )
    );
    assertEquals(201, supportResponse.statusCode(), supportResponse.body());
    var support = body(supportResponse, Evidence.class);
    assertEquals(seeded.hypothesisId(), support.hypothesisId());
    assertNotEquals(seeded.sourceId(), support.sourceId(), "the evidence is independent");

    var afterSupport = hypothesisRow(seeded.hypothesisId());
    assertEquals(ResearchLoopFixtures.CORROBORATED_CONFIDENCE, afterSupport.confidence());
    assertEquals(1L, afterSupport.version());
    assertEquals(HypothesisStatus.STRENGTHENING, afterSupport.status());
    assertTrue(afterSupport.supportingEvidenceRefs().contains(support.id()));

    var supportFacts = TransitionEventText
      .read(events(seeded.hypothesisId()).get(1).reason())
      .orElseThrow(() -> new AssertionError("the transition event carries no trailer"));
    assertEquals("EVIDENCE_ADDED", supportFacts.cause());
    assertEquals(support.id().toString(), supportFacts.ref());
    assertTrue(supportFacts.moved().contains("D3_INDEPENDENT_CORROBORATION"));
    assertEquals("0.1", supportFacts.rubric());

    // 5–6: a CONTRADICTS item from the snapshot's own source. The stance decides the
    // status; the number is whatever the rubric says and is never adjusted by hand.
    var contradictResponse = post(
      evidencePath(seeded),
      new EvidenceCreateRequest(
        seeded.sourceId(),
        "CONTRADICTS",
        70,
        "the issuer's own disclosure restates the margin under a different definition",
        List.of(),
        null
      )
    );
    assertEquals(201, contradictResponse.statusCode(), contradictResponse.body());
    var contradict = body(contradictResponse, Evidence.class);

    var afterContradiction = hypothesisRow(seeded.hypothesisId());
    assertEquals(2L, afterContradiction.version());
    assertEquals(HypothesisStatus.WEAKENING, afterContradiction.status());
    assertTrue(
      afterContradiction.contradictingEvidenceRefs().contains(contradict.id()),
      "supporting and contradicting evidence must stay distinguishable"
    );
    assertEquals(
      ResearchLoopFixtures.CORROBORATED_CONFIDENCE,
      afterContradiction.confidence(),
      "no hand-written delta: the number is the rubric's own output"
    );

    // 7: an OPEN prediction.
    var prediction = createPrediction(seeded);
    assertEquals("OPEN", prediction.status());

    // 8: a passed deadline is reported, never resolved automatically.
    var dueId = seedDueOpenPrediction(seeded, Instant.now().minus(Duration.ofHours(2)));
    var due = body(get("/api/v1/predictions/due"), DuePredictions.class);
    assertTrue(
      due.predictions().stream().anyMatch(item -> item.id().equals(dueId)),
      "a passed OPEN deadline must be reported"
    );
    assertTrue(
      due.predictions().stream().noneMatch(item -> item.id().equals(prediction.id())),
      "a prediction whose deadline has not passed is not due"
    );
    assertEquals(
      "OPEN",
      predictionStatus(dueId),
      "a passed deadline is never turned into a failure automatically"
    );
    assertNull(predictionVerifiedAt(dueId));

    // 9–10: record the verification and notify the engine through the frozen port.
    var operationId = UUID.randomUUID();
    var verificationResponse = post(
      verificationPath(prediction.id()),
      new PredictionVerificationCommand(
        operationId,
        VerificationOutcome.CONFIRMED,
        "the next filing reported 27 percent, below the pre-registered 30 percent boundary",
        null,
        null
      )
    );
    assertEquals(200, verificationResponse.statusCode(), verificationResponse.body());
    var verification = body(
      verificationResponse,
      PredictionVerificationResult.class
    );
    assertTrue(verification.applied());
    assertEquals("CONFIRMED", verification.prediction().status());
    assertNotNull(verification.verificationId());
    assertNotNull(predictionVerifiedAt(prediction.id()));

    assertTrue(verification.hypothesisTransition().applied());
    assertEquals(operationId, verification.hypothesisTransition().operationId());
    assertEquals(3L, verification.hypothesisTransition().version());
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      verification.hypothesisTransition().status(),
      "a CONFIRMED prediction must not confirm a hypothesis contradicting evidence still stands against"
    );
    assertNotEquals(
      HypothesisStatus.CONFIRMED,
      hypothesisRow(seeded.hypothesisId()).status()
    );

    // 11–12: the complete timeline, with every version pair and confidence chained.
    var timeline = body(get(timelinePath(seeded)), HypothesisTimeline.class);
    assertEquals(3L, timeline.version());
    assertEquals(4, timeline.items().size());
    assertEquals(HypothesisStatus.STRENGTHENING, timeline.status());

    long previousVersion = 0L;
    Integer previousConfidence = null;
    for (var item : timeline.items()) {
      assertEquals(seeded.hypothesisId(), item.hypothesisId());
      var facts = TransitionEventText.read(item.reason()).orElse(null);
      if (facts == null) {
        assertNull(previousConfidence, "the CREATED event has no previous score");
        previousConfidence = item.confidence();
        continue;
      }
      assertEquals(previousVersion, facts.previousVersion(), "version chain");
      assertEquals(previousVersion + 1, facts.version(), "exactly one bump per transition");
      assertEquals(
        previousConfidence,
        item.previousConfidence(),
        "the previous score must stay traceable"
      );
      assertEquals(facts.previousScore(), item.previousConfidence());
      assertEquals(facts.score(), item.confidence());
      previousVersion = facts.version();
      previousConfidence = item.confidence();
    }
    assertEquals(3L, previousVersion);
    assertEquals(
      ResearchLoopFixtures.CORROBORATED_CONFIDENCE,
      previousConfidence
    );
  }

  /**
   * A hypothesis may only reach {@code CONFIRMED} through a real verification
   * record, and the clean path (nothing contradicting it) is the one that does.
   */
  @Test
  void aHypothesisIsConfirmedOnlyByAVerificationRecord() throws Exception {
    var seeded = seed();
    var prediction = createPrediction(seeded);

    // Asserting CONFIRMED without a verification record is refused.
    var refusal = post(
      transitionsPath(seeded),
      new HypothesisTransitionCommand(
        UUID.randomUUID(),
        0L,
        HypothesisTransitionCause.PREDICTION_VERIFIED,
        "confirm it because the prediction was met",
        null,
        prediction.id(),
        VerificationOutcome.CONFIRMED
      )
    );
    assertError(refusal, 422, "UNPROCESSABLE_TRANSITION");
    assertEquals(0L, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(1, countEvents(seeded.hypothesisId()));

    var verification = post(
      verificationPath(prediction.id()),
      new PredictionVerificationCommand(
        UUID.randomUUID(),
        VerificationOutcome.CONFIRMED,
        "the filing reported 27 percent against the pre-registered 30 percent boundary",
        null,
        null
      )
    );
    assertEquals(200, verification.statusCode(), verification.body());
    var result = body(verification, PredictionVerificationResult.class);
    assertEquals(HypothesisStatus.CONFIRMED, result.hypothesisTransition().status());
    assertEquals(HypothesisStatus.CONFIRMED, hypothesisRow(seeded.hypothesisId()).status());
    assertEquals("CONFIRMED", predictionStatus(prediction.id()));
  }

  /** All four resolved outcomes, each on a freshly seeded hypothesis. */
  @Test
  void everyVerificationOutcomeIsRecorded() throws Exception {
    assertVerifiedOutcome(
      VerificationOutcome.CONFIRMED,
      "CONFIRMED",
      HypothesisStatus.CONFIRMED
    );
    assertVerifiedOutcome(
      VerificationOutcome.PARTIAL,
      "PARTIAL",
      HypothesisStatus.OPEN
    );
    assertVerifiedOutcome(
      VerificationOutcome.REJECTED,
      "REJECTED",
      HypothesisStatus.WEAKENING
    );
    assertVerifiedOutcome(
      VerificationOutcome.UNRESOLVED,
      "UNRESOLVED",
      HypothesisStatus.UNRESOLVED
    );
  }

  // ---- idempotency and concurrency ---------------------------------------

  /** The same key and the same request must be a replay with no second event. */
  @Test
  void replayingAVerificationReturnsTheRecordedResult() throws Exception {
    var seeded = seed();
    var prediction = createPrediction(seeded);
    var operationId = UUID.randomUUID();
    var command = new PredictionVerificationCommand(
      operationId,
      VerificationOutcome.CONFIRMED,
      "the filing reported 27 percent against the pre-registered 30 percent boundary",
      null,
      null
    );

    var first = post(verificationPath(prediction.id()), command);
    assertEquals(200, first.statusCode(), first.body());
    var firstResult = body(first, PredictionVerificationResult.class);
    assertTrue(firstResult.applied());
    long versionAfterFirst = hypothesisRow(seeded.hypothesisId()).version();
    int eventsAfterFirst = countEvents(seeded.hypothesisId());

    var second = post(verificationPath(prediction.id()), command);
    assertEquals(200, second.statusCode(), second.body());
    var secondResult = body(second, PredictionVerificationResult.class);
    assertFalse(secondResult.applied(), "a replay is not a second application");
    assertEquals(
      firstResult.verificationId(),
      secondResult.verificationId(),
      "a replay returns the same verification id"
    );
    assertEquals(
      firstResult.hypothesisTransition().eventId(),
      secondResult.hypothesisTransition().eventId(),
      "a replay returns the originally recorded transition"
    );
    assertEquals(versionAfterFirst, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(eventsAfterFirst, countEvents(seeded.hypothesisId()));
  }

  /** A different request under the same key is a conflict, never a replay. */
  @Test
  void reusingAnOperationIdForADifferentRequestIsAConflict() throws Exception {
    var seeded = seed();
    var prediction = createPrediction(seeded);
    var operationId = UUID.randomUUID();
    var first = post(
      verificationPath(prediction.id()),
      new PredictionVerificationCommand(
        operationId,
        VerificationOutcome.CONFIRMED,
        "the filing reported 27 percent against the pre-registered 30 percent boundary",
        null,
        null
      )
    );
    assertEquals(200, first.statusCode(), first.body());
    long version = hypothesisRow(seeded.hypothesisId()).version();
    int events = countEvents(seeded.hypothesisId());

    // Same key, different reason.
    assertError(
      post(
        verificationPath(prediction.id()),
        new PredictionVerificationCommand(
          operationId,
          VerificationOutcome.CONFIRMED,
          "a completely different justification for the same decision",
          null,
          null
        )
      ),
      409,
      "IDEMPOTENCY_MISMATCH"
    );

    // Same key, same reason, different outcome: still a different request.
    assertError(
      post(
        verificationPath(prediction.id()),
        new PredictionVerificationCommand(
          operationId,
          VerificationOutcome.REJECTED,
          "the filing reported 27 percent against the pre-registered 30 percent boundary",
          null,
          null
        )
      ),
      409,
      "IDEMPOTENCY_MISMATCH"
    );

    assertEquals(version, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(events, countEvents(seeded.hypothesisId()));
  }

  /**
   * A verification that also cites the evidence it was decided against must still be
   * replayable. The recorded event cites the prediction, so a fingerprint built
   * from the caller's command shape would report a legitimate replay as a mismatch
   * and never return the recorded result.
   */
  @Test
  void aVerificationThatCitesEvidenceIsStillReplayable() throws Exception {
    var seeded = seed();
    var prediction = createPrediction(seeded);
    var evidenceId = seedEvidenceRow(
      seeded,
      seedIndependentSource("Independent Ledger"),
      "SUPPORTS",
      60
    );
    var command = new PredictionVerificationCommand(
      UUID.randomUUID(),
      VerificationOutcome.CONFIRMED,
      "the filing reported 27 percent against the pre-registered 30 percent boundary",
      evidenceId,
      null
    );

    var first = post(verificationPath(prediction.id()), command);
    assertEquals(200, first.statusCode(), first.body());
    var firstResult = body(first, PredictionVerificationResult.class);
    assertTrue(firstResult.applied());

    var second = post(verificationPath(prediction.id()), command);
    assertEquals(200, second.statusCode(), second.body());
    var secondResult = body(second, PredictionVerificationResult.class);
    assertFalse(secondResult.applied());
    assertEquals(firstResult.verificationId(), secondResult.verificationId());
    assertEquals(
      firstResult.hypothesisTransition().eventId(),
      secondResult.hypothesisTransition().eventId()
    );
    assertEquals(2, countEvents(seeded.hypothesisId()));
  }

  /** A stale optimistic-lock token writes nothing at all. */
  @Test
  void aStaleExpectedVersionWritesNothing() throws Exception {    var seeded = seed();
    var evidenceId = seedEvidenceRow(
      seeded,
      seedIndependentSource("Independent Ledger"),
      "SUPPORTS",
      60
    );

    assertError(
      post(
        transitionsPath(seeded),
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          99L,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          "a new record bears on this hypothesis",
          evidenceId,
          null,
          null
        )
      ),
      409,
      "VERSION_CONFLICT"
    );
    assertEquals(0L, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(1, countEvents(seeded.hypothesisId()));
    assertEquals(
      ResearchLoopFixtures.INITIAL_CONFIDENCE,
      hypothesisRow(seeded.hypothesisId()).confidence()
    );
  }

  /** Two submissions of the same version: exactly one winner, one clean 409. */
  @Test
  void concurrentTransitionsProduceExactlyOneWinner() throws Exception {
    var seeded = seed();
    var sourceId = seedIndependentSource("Independent Ledger");
    var firstEvidence = seedEvidenceRow(seeded, sourceId, "SUPPORTS", 60);
    var secondEvidence = seedEvidenceRow(seeded, sourceId, "SUPPORTS", 61);

    var barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<HttpResponse<String>> first = () -> {
        barrier.await(20, TimeUnit.SECONDS);
        return post(
          transitionsPath(seeded),
          new HypothesisTransitionCommand(
            UUID.randomUUID(),
            0L,
            HypothesisTransitionCause.EVIDENCE_ADDED,
            "the first concurrent record bears on this hypothesis",
            firstEvidence,
            null,
            null
          )
        );
      };
      Callable<HttpResponse<String>> second = () -> {
        barrier.await(20, TimeUnit.SECONDS);
        return post(
          transitionsPath(seeded),
          new HypothesisTransitionCommand(
            UUID.randomUUID(),
            0L,
            HypothesisTransitionCause.EVIDENCE_ADDED,
            "the second concurrent record bears on this hypothesis",
            secondEvidence,
            null,
            null
          )
        );
      };
      Future<HttpResponse<String>> firstFuture = pool.submit(first);
      Future<HttpResponse<String>> secondFuture = pool.submit(second);
      var responses = List.of(
        firstFuture.get(30, TimeUnit.SECONDS),
        secondFuture.get(30, TimeUnit.SECONDS)
      );

      long accepted = responses
        .stream()
        .filter(response -> response.statusCode() == 200)
        .count();
      long rejected = responses
        .stream()
        .filter(response -> response.statusCode() == 409)
        .count();
      assertEquals(
        1,
        accepted,
        "exactly one concurrent transition may win: " + responses
      );
      assertEquals(
        1,
        rejected,
        "the loser must observe a version conflict: " + responses
      );
      assertEquals(1L, hypothesisRow(seeded.hypothesisId()).version());
      assertEquals(2, countEvents(seeded.hypothesisId()));
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- negatives: nothing inconsistent may be written ---------------------

  /**
   * A traceable evidence item: source FK, producing analysis, in-snapshot fact
   * references and a stance, with the canonical refs on the hypothesis. A described
   * corroborating signal is not evidence and must never appear as a row.
   */
  @Test
  void evidenceReferencesAreTraceable() throws Exception {
    var seeded = seed();
    assertEquals(
      0,
      countEvidence(seeded.hypothesisId()),
      "a NOT_OBSERVED corroborating signal in the snapshot is not an observed evidence row"
    );

    var sourceId = seedIndependentSource("Independent Ledger");
    var response = post(
      evidencePath(seeded),
      new EvidenceCreateRequest(
        sourceId,
        "SUPPORTS",
        60,
        "an independent filing corroborates the input-cost movement",
        List.of(seeded.factId()),
        seeded.analysisId()
      )
    );
    assertEquals(201, response.statusCode(), response.body());
    var stored = body(response, Evidence.class);

    assertEquals(seeded.hypothesisId(), stored.hypothesisId());
    assertEquals(sourceId, stored.sourceId(), "the source FK is the provenance");
    assertEquals(
      seeded.analysisId(),
      stored.analysisId(),
      "V4 traceability to the producing analysis"
    );
    assertEquals(
      List.of(seeded.factId()),
      stored.factRefs(),
      "the cited facts stay traceable"
    );
    assertEquals("SUPPORTS", stored.stance());
    assertEquals(60, stored.strength());
    assertEquals(
      seeded.analysisId(),
      db.queryForObject(
        "SELECT analysis_id FROM evidence WHERE id=?",
        UUID.class,
        stored.id()
      )
    );

    var row = hypothesisRow(seeded.hypothesisId());
    assertTrue(row.supportingEvidenceRefs().contains(stored.id()));
    assertTrue(row.contradictingEvidenceRefs().isEmpty());
  }

  /** Unknown, malformed and unresolvable inputs leave the database untouched. */
  @Test
  void evidenceIntegrityRejectsUnknownAndMalformedInput() throws Exception {
    var seeded = seed();

    // An unknown source: the FK is verified before anything is written.
    assertError(
      post(
        evidencePath(seeded),
        new EvidenceCreateRequest(
          UUID.randomUUID(),
          "SUPPORTS",
          50,
          "an unknown source",
          List.of(),
          null
        )
      ),
      404,
      "NOT_FOUND"
    );
    // An unknown hypothesis.
    assertError(
      post(
        evidencePath(UUID.randomUUID()),
        new EvidenceCreateRequest(
          seeded.sourceId(),
          "SUPPORTS",
          50,
          "an unknown hypothesis",
          List.of(),
          null
        )
      ),
      404,
      "NOT_FOUND"
    );
    // Strength outside the frozen 0–100 range.
    assertError(
      post(
        evidencePath(seeded),
        new EvidenceCreateRequest(
          seeded.sourceId(),
          "SUPPORTS",
          101,
          "an out-of-range strength",
          List.of(),
          null
        )
      ),
      400,
      "INVALID_REQUEST"
    );
    // A blank reason.
    assertError(
      post(
        evidencePath(seeded),
        new EvidenceCreateRequest(
          seeded.sourceId(),
          "SUPPORTS",
          50,
          "   ",
          List.of(),
          null
        )
      ),
      400,
      "INVALID_REQUEST"
    );
    // A stance outside the frozen vocabulary.
    assertError(
      post(
        evidencePath(seeded),
        new EvidenceCreateRequest(
          seeded.sourceId(),
          "MAYBE",
          50,
          "an invalid stance",
          List.of(),
          null
        )
      ),
      400,
      "INVALID_REQUEST"
    );
    // A fact reference that does not resolve inside the snapshot (PR-09).
    assertError(
      post(
        evidencePath(seeded),
        new EvidenceCreateRequest(
          seeded.sourceId(),
          "SUPPORTS",
          50,
          "an unresolvable fact reference",
          List.of(UUID.randomUUID()),
          null
        )
      ),
      422,
      "UNPROCESSABLE_TRANSITION"
    );

    assertEquals(0, countEvidence(seeded.hypothesisId()));
    assertEquals(0L, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(1, countEvents(seeded.hypothesisId()));

    // Prediction creation follows the same rules.
    assertError(
      post(
        predictionsPath(seeded),
        new PredictionCreateRequest(
          "the next filing reports a margin below 30 percent",
          "the reported unit margin",
          Instant.now().minus(Duration.ofDays(1)),
          "confirmation requires below 30 percent; rejection requires 30 percent or above",
          "the issuer's filings page",
          List.of()
        )
      ),
      422,
      "UNPROCESSABLE_TRANSITION"
    );
    assertError(
      post(
        predictionsPath(seeded),
        new PredictionCreateRequest(
          "the next filing reports a margin below 30 percent",
          "the reported unit margin",
          Instant.now().plus(Duration.ofDays(30)),
          "the next filing reports a margin below 30 percent",
          "the issuer's filings page",
          List.of()
        )
      ),
      422,
      "UNPROCESSABLE_TRANSITION"
    );

    // Verification of an unknown prediction, and a blank verification reason.
    assertError(
      post(
        verificationPath(UUID.randomUUID()),
        new PredictionVerificationCommand(
          UUID.randomUUID(),
          VerificationOutcome.CONFIRMED,
          "an unknown prediction",
          null,
          null
        )
      ),
      404,
      "NOT_FOUND"
    );
    var realPrediction = createPrediction(seeded);
    assertError(
      post(
        verificationPath(realPrediction.id()),
        new PredictionVerificationCommand(
          UUID.randomUUID(),
          VerificationOutcome.CONFIRMED,
          "  ",
          null,
          null
        )
      ),
      400,
      "INVALID_REQUEST"
    );
    assertEquals("OPEN", predictionStatus(realPrediction.id()));
  }

  /** A terminal hypothesis refuses further transitions without writing. */
  @Test
  void aTerminalHypothesisRefusesFurtherTransitions() throws Exception {
    var seeded = seedWithStatus(HypothesisStatus.REJECTED);
    var evidenceId = seedEvidenceRow(
      seeded,
      seedIndependentSource("Independent Ledger"),
      "SUPPORTS",
      60
    );

    assertError(
      post(
        transitionsPath(seeded),
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          0L,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          "new supporting material after a met falsification condition",
          evidenceId,
          null,
          null
        )
      ),
      422,
      "UNPROCESSABLE_TRANSITION"
    );
    assertEquals(0L, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(1, countEvents(seeded.hypothesisId()));
    assertEquals(HypothesisStatus.REJECTED, hypothesisRow(seeded.hypothesisId()).status());
  }

  /**
   * The transaction proof: the verification record is written before the engine is
   * notified, so a refused transition must roll that write back too.
   */
  @Test
  void aRefusedTransitionRollsBackTheVerificationRecord() throws Exception {
    var seeded = seedWithStatus(HypothesisStatus.REJECTED);
    var prediction = createPrediction(seeded);

    assertError(
      post(
        verificationPath(prediction.id()),
        new PredictionVerificationCommand(
          UUID.randomUUID(),
          VerificationOutcome.CONFIRMED,
          "the filing reported 27 percent against the pre-registered boundary",
          null,
          null
        )
      ),
      422,
      "UNPROCESSABLE_TRANSITION"
    );

    assertEquals(
      "OPEN",
      predictionStatus(prediction.id()),
      "the recorded outcome must roll back with the refused transition"
    );
    assertNull(predictionVerifiedAt(prediction.id()));
    assertEquals(0L, hypothesisRow(seeded.hypothesisId()).version());
    assertEquals(1, countEvents(seeded.hypothesisId()));
  }

  // ---- compatibility -----------------------------------------------------

  /** A prose previous rendering stays readable and the history is never rewritten. */
  @Test
  void aLegacyRenderingStaysReadableAndTheTimelineStaysAppendOnly()
    throws Exception {
    var seeded = seedLegacy();
    var before = new ArrayList<>(eventPayloads(seeded.hypothesisId()));

    var response = post(
      evidencePath(seeded),
      new EvidenceCreateRequest(
        seedIndependentSource("Independent Ledger"),
        "SUPPORTS",
        60,
        "an independent supplier filing corroborates the input-cost movement",
        List.of(),
        null
      )
    );
    assertEquals(201, response.statusCode(), response.body());

    var row = hypothesisRow(seeded.hypothesisId());
    assertEquals(1L, row.version());
    assertEquals(ResearchLoopFixtures.CORROBORATED_CONFIDENCE, row.confidence());

    var facts = TransitionEventText
      .read(events(seeded.hypothesisId()).get(1).reason())
      .orElseThrow();
    assertEquals(
        5,
        facts.moved().size(),
        "a prose previous rendering cannot name a single moved dimension, so every re-derived one is named"
    );

    var after = eventPayloads(seeded.hypothesisId());
    assertEquals(before.size() + 1, after.size(), "append-only");
    assertEquals(
      before,
      after.subList(0, before.size()),
      "stored events are never rewritten"
    );
  }

  /** CF-06: an unreadable snapshot must fail closed, not invent a level. */
  @Test
  void anUnscorableSnapshotFailsClosed() throws Exception {
    var seeded = seedUnscorable();

    var response = post(
      evidencePath(seeded),
      new EvidenceCreateRequest(
        seedIndependentSource("Independent Ledger"),
        "SUPPORTS",
        60,
        "an independent supplier filing corroborates the input-cost movement",
        List.of(),
        null
      )
    );
    assertEquals(201, response.statusCode(), response.body());

    var row = hypothesisRow(seeded.hypothesisId());
    assertEquals(1L, row.version());
    assertEquals(
      ResearchLoopFixtures.INITIAL_CONFIDENCE,
      row.confidence(),
      "CF-06 carries the stored number unchanged and marks it advisory"
    );

    var facts = TransitionEventText
      .read(events(seeded.hypothesisId()).get(1).reason())
      .orElseThrow();
    assertEquals("MODEL_JUDGMENT", facts.method());
    assertNull(facts.rubric(), "a fail-closed score has no rubric version");
    assertTrue(facts.dimensions().isEmpty(), "no dimension may be invented");
  }

  // ---- helpers -----------------------------------------------------------

  private void assertVerifiedOutcome(
    VerificationOutcome outcome,
    String expectedPredictionStatus,
    HypothesisStatus expectedHypothesisStatus
  ) throws Exception {
    var seeded = seed();
    var prediction = createPrediction(seeded);
    var response = post(
      verificationPath(prediction.id()),
      new PredictionVerificationCommand(
        UUID.randomUUID(),
        outcome,
        "the filing was checked against the pre-registered decision boundary",
        null,
        null
      )
    );
    assertEquals(200, response.statusCode(), response.body());

    var result = body(response, PredictionVerificationResult.class);
    assertTrue(result.applied(), outcome + " must apply on its first submission");
    assertEquals(expectedPredictionStatus, result.prediction().status());
    assertEquals(expectedPredictionStatus, predictionStatus(prediction.id()));
    assertNotNull(
      predictionVerifiedAt(prediction.id()),
      outcome + " must record when it was decided"
    );
    assertEquals(expectedHypothesisStatus, result.hypothesisTransition().status());
    assertEquals(
      expectedHypothesisStatus,
      hypothesisRow(seeded.hypothesisId()).status()
    );
    assertEquals(1L, result.hypothesisTransition().version());
  }

  private Prediction createPrediction(Seeded seeded) throws Exception {
    var response = post(
      predictionsPath(seeded),
      new PredictionCreateRequest(
        "the next quarterly filing reports a unit margin below 30 percent",
        "the reported unit margin in the next quarterly filing",
        Instant.now().plus(Duration.ofDays(90)),
        "confirmation requires a reported margin below 30 percent; rejection requires 30 percent or above",
        "the issuer's investor relations filings page",
        List.of()
      )
    );
    assertEquals(201, response.statusCode(), response.body());
    return body(response, Prediction.class);
  }

  private static String timelinePath(Seeded seeded) {
    return "/api/v1/hypotheses/" + seeded.hypothesisId() + "/timeline";
  }

  private static String transitionsPath(Seeded seeded) {
    return "/api/v1/hypotheses/" + seeded.hypothesisId() + "/transitions";
  }

  private static String evidencePath(Seeded seeded) {
    return evidencePath(seeded.hypothesisId());
  }

  private static String evidencePath(UUID hypothesisId) {
    return "/api/v1/hypotheses/" + hypothesisId + "/evidence";
  }

  private static String predictionsPath(Seeded seeded) {
    return "/api/v1/hypotheses/" + seeded.hypothesisId() + "/predictions";
  }

  private static String verificationPath(UUID predictionId) {
    return "/api/v1/predictions/" + predictionId + "/verification";
  }
}
