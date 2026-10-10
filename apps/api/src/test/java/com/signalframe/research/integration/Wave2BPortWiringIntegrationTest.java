package com.signalframe.research.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.Evidence;
import com.signalframe.contract.EvidenceCreateRequest;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTimeline;
import com.signalframe.contract.Prediction;
import com.signalframe.contract.PredictionCreateRequest;
import com.signalframe.contract.PredictionVerificationCommand;
import com.signalframe.contract.PredictionVerificationResult;
import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.application.hypotheses.HypothesisTransitionService;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import com.signalframe.research.domain.hypotheses.TransitionEventText;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cross-module wiring the two task suites cannot see: TASK-07 writing through
 * TASK-06's real {@link HypothesisTransitionPort} in the production context.
 *
 * <p>Everything asserted here goes over HTTP, through the real controller, the real
 * service, the real transaction, the real JDBC repositories, the real Flyway
 * schema and the real deterministic rubric. No fake port, no mock repository, no
 * substituted transaction manager.
 */
class Wave2BPortWiringIntegrationTest extends Wave2BIntegrationSupport {

  @Test
  void theProductionContextExposesExactlyOneRealPortImplementation() {
    var names = context.getBeanNamesForType(HypothesisTransitionPort.class);
    assertEquals(
      1,
      names.length,
      "exactly one HypothesisTransitionPort implementation must exist, found: " +
      List.of(names)
    );
    assertInstanceOf(
      HypothesisTransitionService.class,
      transitionPort,
      "the port must be TASK-06's real implementation, not a stand-in"
    );
    for (var name : names) {
      var lower = name.toLowerCase(java.util.Locale.ROOT);
      assertFalse(
        lower.contains("fake") || lower.contains("noop") || lower.contains("stub"),
        "a stand-in port (" + name + ") must never be the production bean"
      );
    }
  }

  /** TASK-07's gateway must resolve that same bean — never its 503 fallback. */
  @Test
  void theGatewayResolvesTheRealPortRatherThanFailingUnavailable()
    throws Exception {
    var seeded = seed();
    var evidenceId = seedIndependentSource("Independent Ledger");
    var request = new EvidenceCreateRequest(
      evidenceId,
      "SUPPORTS",
      60,
      "an independent record corroborates the input-cost movement",
      List.of(),
      null
    );
    var response = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/evidence",
      request
    );
    assertEquals(201, response.statusCode(), response.body());
  }

  /**
   * An independent SUPPORTS item must move the hypothesis by changing the rubric's
   * <em>inputs</em>: CAP-A releases because D3 stops being 0, and the score becomes
   * exactly the rubric's deterministic output (49 → 73), never a fixed delta.
   */
  @Test
  void independentSupportingEvidenceRecomputesConfidenceThroughTheRubric()
    throws Exception {
    var seeded = seed();
    assertEquals(ResearchLoopFixtures.INITIAL_CONFIDENCE, hypothesisRow(seeded.hypothesisId()).confidence());
    assertEquals(0, hypothesisRow(seeded.hypothesisId()).version());

    var request = new EvidenceCreateRequest(
      seedIndependentSource("Independent Ledger"),
      "SUPPORTS",
      60,
      "an independent supplier filing corroborates the input-cost movement",
      List.of(),
      null
    );
    var response = post(
      "/api/v1/hypotheses/" + seeded.hypothesisId() + "/evidence",
      request
    );
    assertEquals(201, response.statusCode(), response.body());
    var evidence = body(response, Evidence.class);
    assertEquals(seeded.hypothesisId(), evidence.hypothesisId());
    assertNotNull(evidence.id());

    var row = hypothesisRow(seeded.hypothesisId());
    assertEquals(
      ResearchLoopFixtures.CORROBORATED_CONFIDENCE,
      row.confidence(),
      "the score must be the rubric's recomputation, not a chosen number"
    );
    assertEquals(1L, row.version(), "one transition, one version bump");
    assertEquals(HypothesisStatus.STRENGTHENING, row.status());
    assertTrue(
      row.supportingEvidenceRefs().contains(evidence.id()),
      "the supporting evidence reference must be traced on the hypothesis"
    );

    var events = events(seeded.hypothesisId());
    assertEquals(2, events.size(), "append-only: CREATED plus one transition");
    var event = events.get(1);
    assertEquals(ResearchLoopFixtures.INITIAL_CONFIDENCE, event.previousConfidence());
    assertEquals(ResearchLoopFixtures.CORROBORATED_CONFIDENCE, event.confidence());
    var facts = TransitionEventText.read(event.reason()).orElseThrow();
    assertEquals("EVIDENCE_ADDED", facts.cause());
    assertEquals(evidence.id().toString(), facts.ref());
    assertEquals(0L, facts.previousVersion());
    assertEquals(1L, facts.version());
    assertEquals("0.1", facts.rubric(), "the event records its rubric version");
    assertTrue(
      facts.moved().contains("D3_INDEPENDENT_CORROBORATION"),
      "the moved dimension must be named, got " + facts.moved()
    );
    assertEquals(5, facts.dimensions().size(), "all five dimensions are recorded");
    assertFalse(
      facts.band() == null,
      "the event must record the band of the new score"
    );

    var timeline = body(
      get("/api/v1/hypotheses/" + seeded.hypothesisId() + "/timeline"),
      HypothesisTimeline.class
    );
    assertEquals(1L, timeline.version());
    assertEquals(HypothesisStatus.STRENGTHENING, timeline.status());
    assertEquals(2, timeline.items().size());
    assertEquals(
      ResearchLoopFixtures.CORROBORATED_CONFIDENCE,
      timeline.confidence()
    );
  }

  /**
   * Identical stored artifacts and identical evidence must produce a byte-identical
   * rubric rendering — the CF-02/CF-04 determinism contract at hypothesis scope.
   */
  @Test
  void identicalInputsProduceIdenticalScores() throws Exception {
    var first = seed();
    var second = seed();
    var sharedReason =
      "an independent supplier filing corroborates the input-cost movement";

    var firstResponse = post(
      "/api/v1/hypotheses/" + first.hypothesisId() + "/evidence",
      new EvidenceCreateRequest(
        seedIndependentSource("Independent Ledger"),
        "SUPPORTS",
        60,
        sharedReason,
        List.of(),
        null
      )
    );
    var secondResponse = post(
      "/api/v1/hypotheses/" + second.hypothesisId() + "/evidence",
      new EvidenceCreateRequest(
        seedIndependentSource("Independent Ledger"),
        "SUPPORTS",
        60,
        sharedReason,
        List.of(),
        null
      )
    );
    assertEquals(201, firstResponse.statusCode(), firstResponse.body());
    assertEquals(201, secondResponse.statusCode(), secondResponse.body());

    var firstRow = hypothesisRow(first.hypothesisId());
    var secondRow = hypothesisRow(second.hypothesisId());
    assertEquals(firstRow.confidence(), secondRow.confidence());
    assertEquals(
      firstRow.confidenceReason(),
      secondRow.confidenceReason(),
      "the same inputs and the same rubric version must render the same reason"
    );

    var firstFacts = TransitionEventText
      .read(events(first.hypothesisId()).get(1).reason())
      .orElseThrow();
    var secondFacts = TransitionEventText
      .read(events(second.hypothesisId()).get(1).reason())
      .orElseThrow();
    assertEquals(firstFacts.dimensions(), secondFacts.dimensions());
    assertEquals(firstFacts.moved(), secondFacts.moved());
    assertEquals(firstFacts.score(), secondFacts.score());
  }

  /**
   * The integration the two task suites each assumed the other side provided:
   * recording a verification and then notifying the engine must both succeed, in
   * one transaction, with the outcome visible to the port.
   */
  @Test
  void verificationThroughHttpRecordsTheOutcomeAndMovesTheHypothesis()
    throws Exception {
    var seeded = seed();
    var prediction = body(
      post(
        "/api/v1/hypotheses/" + seeded.hypothesisId() + "/predictions",
        new PredictionCreateRequest(
          "the next quarterly filing reports a unit margin below 30 percent",
          "the reported unit margin in the next quarterly filing",
          Instant.now().plus(Duration.ofDays(90)),
          "confirmation requires a reported margin below 30 percent; rejection requires 30 percent or above",
          "the issuer's investor relations filings page",
          List.of()
        )
      ),
      Prediction.class
    );
    assertEquals("OPEN", prediction.status());

    var operationId = UUID.randomUUID();
    var response = post(
      "/api/v1/predictions/" + prediction.id() + "/verification",
      new PredictionVerificationCommand(
        operationId,
        VerificationOutcome.CONFIRMED,
        "the filing reported 27 percent, below the 30 percent boundary",
        null,
        null
      )
    );
    assertEquals(200, response.statusCode(), response.body());

    var result = body(response, PredictionVerificationResult.class);
    assertTrue(result.applied());
    assertEquals("CONFIRMED", result.prediction().status());
    assertNotNull(
      predictionVerifiedAt(prediction.id()),
      "a verification must record when it was decided (V4 predictions.verified_at)"
    );
    assertNotNull(result.verificationId());
    assertNotNull(result.hypothesisTransition());
    assertTrue(result.hypothesisTransition().applied());
    assertEquals(operationId, result.hypothesisTransition().operationId());
    assertEquals(1L, result.hypothesisTransition().version());

    assertEquals("CONFIRMED", predictionStatus(prediction.id()));
    var row = hypothesisRow(seeded.hypothesisId());
    assertEquals(HypothesisStatus.CONFIRMED, row.status());
    assertEquals(1L, row.version());
    assertEquals(2, countEvents(seeded.hypothesisId()));
    List<HypothesisEvent> events = events(seeded.hypothesisId());
    assertEquals(HypothesisStatus.CONFIRMED, events.get(1).status());
    assertEquals("STATUS_CHANGED", events.get(1).eventType());
  }
}
