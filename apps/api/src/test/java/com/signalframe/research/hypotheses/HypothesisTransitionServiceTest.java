package com.signalframe.research.hypotheses;

import static com.signalframe.research.hypotheses.HypothesisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.ClaimType;
import com.signalframe.contract.ConfidenceBand;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;
import com.signalframe.contract.Statement;
import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.application.hypotheses.HypothesisTransitionService;
import com.signalframe.research.domain.hypotheses.EvidenceStance;
import com.signalframe.research.domain.hypotheses.HypothesisSnapshot;
import com.signalframe.research.domain.hypotheses.HypothesisTransitions;
import com.signalframe.research.domain.hypotheses.StoredEvidence;
import com.signalframe.research.domain.hypotheses.StoredPrediction;
import com.signalframe.research.domain.hypotheses.TransitionEventText;
import com.signalframe.shared.ApplicationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The transition engine's behaviour without a database.
 *
 * <p>Covers the TASK-06 acceptance criteria decided by logic: legal and illegal
 * state transitions, rubric recomputation instead of a free delta, append-only
 * history, reason and reference recording, idempotency, optimistic concurrency,
 * and the protocol basis a {@code CONFIRMED} status needs. PostgreSQL atomicity,
 * real row locking and legacy rows are covered by
 * {@link HypothesisTransitionIntegrationTest}.
 *
 * <p>The score numbers asserted below are CONFIDENCE_MODEL_V0_1 applied by hand to
 * these fixtures, with the arithmetic spelled out at each assertion. They are
 * exact on purpose: if a dimension derivation changes, these tests must fail
 * rather than quietly accept a different number.
 */
class HypothesisTransitionServiceTest {

  private static final UUID ANALYSIS = UUID.randomUUID();
  private static final UUID SOURCE = UUID.randomUUID();
  private static final UUID FACT = UUID.randomUUID();
  private static final UUID HYPOTHESIS = UUID.randomUUID();
  private static final UUID MECHANISM = UUID.randomUUID();

  private static final int PREVIOUS_SCORE = 20;

  /**
   * The fixture snapshot's rubric arithmetic, excluding D3 (which evidence moves):
   * D1 = 3 (12) because the publisher, date and completeness are all known, the
   * source is primary and no contextual material exists;
   * D2 = 5 (25) because a referenced fact states the quantity directly;
   * D4 = 3 (12) because a PLAUSIBLE mechanism carries a fact reference;
   * D5 = 4 (12) because a counter-argument cites a fact and the falsification
   * condition is observable but not time bounded.
   */
  private static final int BASE_POINTS = 12 + 25 + 12 + 12;

  private HypothesisTransitionService service;
  private FakeHypothesisTransitionRepository repository;

  @BeforeEach
  void setUp() {
    repository = new FakeHypothesisTransitionRepository();
    service = new HypothesisTransitionService(repository);

    var condition = falsificationCondition(
      "if the reported margin recovers without an input-cost movement",
      "the reported unit margin",
      "compare with the pre-decline quarter at the same definition",
      "below 30 percent for two consecutive quarters"
    );
    repository.snapshot = new HypothesisSnapshot(
      snapshot(
        source("example.test", SNAPSHOT_AT, "PRIMARY", "SINGLE_SOURCE", "COMPLETE"),
        List.of(fact(FACT, SOURCE, "unit margin fell 30 percent on higher input cost")),
        List.of(mechanism(MECHANISM, List.of(FACT), "PLAUSIBLE")),
        List.of(counterArgument(HYPOTHESIS, List.of(FACT))),
        List.of(condition),
        List.of(),
        List.of(planItem(HYPOTHESIS))
      ),
      SOURCE
    );

    repository.put(
      HYPOTHESIS,
      ANALYSIS,
      new Hypothesis(
        HYPOTHESIS,
        "input costs squeezed unit margin",
        "input costs squeezed unit margin",
        HypothesisStatus.OPEN,
        PREVIOUS_RENDERING,
        SNAPSHOT_AT,
        SNAPSHOT_AT,
        ClaimType.HYPOTHESIS,
        "input costs squeezed unit margin",
        "the reported margin decline is consistent with an input-cost squeeze",
        PREVIOUS_SCORE,
        List.of(),
        List.of(FACT),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(condition),
        null
      ),
      0
    );
    // The pipeline always writes a CREATED event: the timeline's first link.
    repository.append(
      new HypothesisEvent(
        UUID.randomUUID(),
        HYPOTHESIS,
        "CREATED",
        null,
        PREVIOUS_SCORE,
        PREVIOUS_RENDERING,
        SNAPSHOT_AT,
        null,
        HypothesisStatus.OPEN
      )
    );
    repository.appended = 0;
  }

  // ---- helpers -----------------------------------------------------------

  private StoredEvidence evidenceRow(UUID sourceId, EvidenceStance stance) {
    return new StoredEvidence(UUID.randomUUID(), HYPOTHESIS, sourceId, stance);
  }

  private StoredEvidence addEvidence(UUID sourceId, EvidenceStance stance) {
    var row = evidenceRow(sourceId, stance);
    repository.putEvidence(row);
    return row;
  }

  /** Applies one EVIDENCE_ADDED transition citing a freshly stored evidence row. */
  private HypothesisTransitionResult addEvidenceAndTransition(
    UUID sourceId,
    EvidenceStance stance,
    long expectedVersion
  ) {
    var row = addEvidence(sourceId, stance);
    return service.transition(
      HypothesisTransitions.evidenceAdded(
        UUID.randomUUID(),
        HYPOTHESIS,
        expectedVersion,
        row.id(),
        "an independent filing bears on the margin claim"
      )
    );
  }

  private Hypothesis stored() {
    return repository.hypotheses.get(HYPOTHESIS).payload();
  }

  private TransitionEventText.Facts trailerOfLastEvent() {
    return TransitionEventText
      .read(repository.events.getLast().reason())
      .orElseThrow();
  }

  private List<String> movedOfLastEvent() {
    return trailerOfLastEvent().moved();
  }

  // ---- the happy path and the score --------------------------------------

  @Test
  void aTransitionBumpsTheVersionAndAppendsExactlyOneEvent() {
    var result = addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);

    assertTrue(result.applied());
    assertEquals(0L, result.previousVersion());
    assertEquals(1L, result.version());
    assertEquals(HypothesisStatus.OPEN, result.previousStatus());
    assertEquals(HypothesisStatus.STRENGTHENING, result.status());
    assertEquals(2, repository.timeline(HYPOTHESIS).size(), "CREATED plus one");
    assertEquals(1L, repository.hypotheses.get(HYPOTHESIS).version());
    assertEquals(HypothesisStatus.STRENGTHENING, stored().status());
    assertEquals(result.eventId(), repository.events.getLast().id());
    assertEquals(result.confidence(), stored().confidence());
    assertEquals(result.confidenceBand(), stored().confidenceBand());
  }

  @Test
  void confidenceIsTheDeterministicRubricNotADeltaOnThePreviousNumber() {
    // The previous number is 20 and is deliberately unrelated to these inputs.
    // Evidence from the snapshot's own source is a non-independent addition:
    // D3 = 1 (4). raw = 61 + 4 = 65; CAP-A binds at D3 <= 1 with a ceiling of 49.
    var result = addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);

    assertEquals(BASE_POINTS + 4, 65, "the raw total, for the record");
    assertEquals(49, result.confidence());
    assertEquals(ConfidenceBand.LOW, result.confidenceBand());
    assertEquals("0.1", result.rubricVersion());
    assertNotEquals(
      PREVIOUS_SCORE + 1,
      result.confidence(),
      "no arbitrary delta is applied to the previous number"
    );

    // CF-03/CF-08: the stored rendering is the rubric's own output for the score.
    assertTrue(stored().confidenceReason().contains("score=49"), stored().confidenceReason());
    assertTrue(stored().confidenceReason().contains("method=RUBRIC"));
    assertTrue(stored().confidenceReason().contains("CAP-A=49"));

    // The event names the breakdown and the dimension that moved, neither of
    // which the frozen HypothesisEvent record has a field for.
    var facts = trailerOfLastEvent();
    assertEquals("RUBRIC", facts.method());
    assertEquals("0.1", facts.rubric());
    assertEquals(5, facts.dimensions().size());
    assertEquals(PREVIOUS_SCORE, facts.previousScore());
    assertEquals(49, facts.score());
    assertEquals("LOW", facts.band());
    assertEquals(0L, facts.previousVersion());
    assertEquals(1L, facts.version());
    assertTrue(
      movedOfLastEvent().contains("D3_INDEPENDENT_CORROBORATION"),
      "a moved number must name the dimensions that moved: " + movedOfLastEvent()
    );
  }

  @Test
  void independentCorroborationRaisesTheScoreThroughD3AndNamesOnlyD3() {
    var first = addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);
    assertEquals(49, first.confidence(), "CAP-A pins the uncorroborated score at 49");

    // Two evidence items from sources other than the snapshot's own: independent
    // confirmation of the core fact.
    var second = addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS);
    addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS);

    var result = service.transition(
      HypothesisTransitions.evidenceAdded(
        UUID.randomUUID(),
        HYPOTHESIS,
        1L,
        second.id(),
        "a second independent source reports the same movement"
      )
    );

    // D3 rises from 1 (4) to 4 (16), which releases CAP-A:
    // raw = 61 + 16 = 77 with no binding cap.
    assertEquals(77, result.confidence());
    assertEquals(ConfidenceBand.HIGH, result.confidenceBand());
    assertEquals(
      List.of("D3_INDEPENDENT_CORROBORATION"),
      movedOfLastEvent(),
      "only D3 moved: nothing else about the snapshot changed"
    );
    assertEquals(HypothesisStatus.STRENGTHENING, result.status());
  }

  @Test
  void theSameStoredStateIsScoredIdenticallyByAFreshEngine() {
    addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);
    int storedScore = stored().confidence();

    var replay = new HypothesisTransitionService(repository);
    var again = replay.transition(
      HypothesisTransitions.evidenceChanged(
        UUID.randomUUID(),
        HYPOTHESIS,
        1L,
        repository.evidenceFor(HYPOTHESIS).getFirst().id(),
        "restating the same evidence changes nothing"
      )
    );

    // CF-02/CF-04: no clock, no randomness, no model, so the recomputation is
    // byte-identical and the store accepts the unchanged number.
    assertEquals(storedScore, again.confidence());
  }

  // ---- fail closed on a legacy snapshot ----------------------------------

  @Test
  void aMissingSnapshotFailsClosedAndNeverMovesTheNumber() {
    repository.snapshot = null;
    var result = addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);

    assertEquals(
      PREVIOUS_SCORE,
      result.confidence(),
      "CF-06: the stored number is carried, never rewritten to zero"
    );
    assertNull(result.rubricVersion());
    var facts = trailerOfLastEvent();
    assertEquals("MODEL_JUDGMENT", facts.method());
    assertNull(facts.rubric());
    assertTrue(facts.dimensions().isEmpty());
    assertTrue(facts.moved().isEmpty(), "a model judgment has no dimensions to move");
    // The status still follows the evidence: a status is not a function of a score.
    assertEquals(HypothesisStatus.STRENGTHENING, result.status());
  }

  @Test
  void aSnapshotWithoutASourceAssessmentAlsoFailsClosed() {
    repository.snapshot = new HypothesisSnapshot(
      snapshot(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
      SOURCE
    );
    var result = addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);
    assertEquals(PREVIOUS_SCORE, result.confidence());
    assertEquals("MODEL_JUDGMENT", trailerOfLastEvent().method());
  }

  // ---- reason, references and append-only history -------------------------

  @Test
  void everyTransitionRecordsItsReasonAndTheEvidenceItCited() {
    var supporting = addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS);
    var contradicted = addEvidence(UUID.randomUUID(), EvidenceStance.CONTRADICTS);

    service.transition(
      HypothesisTransitions.evidenceAdded(
        UUID.randomUUID(),
        HYPOTHESIS,
        0L,
        supporting.id(),
        "a supplier disclosure supports the squeeze"
      )
    );
    service.transition(
      HypothesisTransitions.evidenceAdded(
        UUID.randomUUID(),
        HYPOTHESIS,
        1L,
        contradicted.id(),
        "a competing disclosure contradicts it"
      )
    );

    assertEquals(List.of(supporting.id()), stored().supportingEvidenceRefs());
    assertEquals(List.of(contradicted.id()), stored().contradictingEvidenceRefs());

    for (var event : repository.timeline(HYPOTHESIS)) {
      if ("CREATED".equals(event.eventType())) continue;
      assertFalse(
        TransitionEventText.userReason(event.reason()).isBlank(),
        "a status or confidence movement is never recorded without a reason"
      );
      assertFalse(
        trailerOfLastEvent().ref().isBlank(),
        "the cited reference is recorded in the timeline"
      );
    }
  }

  @Test
  void historyIsAppendOnlyAndPreviousConfidenceStaysTraceable() {
    addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);
    addEvidenceAndTransition(UUID.randomUUID(), EvidenceStance.SUPPORTS, 1L);
    addEvidenceAndTransition(UUID.randomUUID(), EvidenceStance.CONTRADICTS, 2L);

    var timeline = repository.timeline(HYPOTHESIS);
    assertEquals(4, timeline.size(), "CREATED plus three transitions");
    assertEquals("CREATED", timeline.getFirst().eventType());
    assertNull(timeline.getFirst().previousConfidence());
    for (int i = 1; i < timeline.size(); i++) {
      assertEquals(
        timeline.get(i - 1).confidence(),
        timeline.get(i).previousConfidence(),
        "each event's previous confidence is where the timeline before it ended"
      );
      assertEquals(
        timeline.get(i - 1).status(),
        timeline.get(i).previousStatus(),
        "each event's previous status is where the timeline before it ended"
      );
      assertTrue(
        timeline.get(i).createdAt().isAfter(timeline.get(i - 1).createdAt()),
        "the timeline order is total and strictly increasing"
      );
    }

    var versions = new ArrayList<Long>();
    for (var event : timeline) {
      if ("CREATED".equals(event.eventType())) continue;
      versions.add(TransitionEventText.read(event.reason()).orElseThrow().version());
    }
    assertEquals(List.of(1L, 2L, 3L), versions);

    // No earlier event was rewritten by a later transition.
    assertEquals(PREVIOUS_SCORE, timeline.getFirst().confidence());
    assertEquals(
      PREVIOUS_RENDERING,
      timeline.getFirst().reason(),
      "the CREATED event keeps its original payload byte for byte"
    );
  }

  @Test
  void aStatusMovementIsReportedAsAStatusChangeAndAMovementFreeEventNamesItsCause() {
    addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);
    assertEquals(
      "STATUS_CHANGED",
      repository.events.getLast().eventType(),
      "OPEN -> STRENGTHENING must be visible as a status transition"
    );

    // A second item from the same source cannot change D3, so neither the score
    // nor the status moves; the event then names its cause.
    addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 1L);
    assertEquals("EVIDENCE_ADDED", repository.events.getLast().eventType());
    assertEquals(HypothesisStatus.STRENGTHENING, stored().status());
    assertEquals(2L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  // ---- idempotency -------------------------------------------------------

  @Test
  void replayingAnOperationReturnsTheRecordedResultAndWritesNothing() {
    var operationId = UUID.randomUUID();
    var evidenceId = addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS).id();
    var command = HypothesisTransitions.evidenceAdded(
      operationId,
      HYPOTHESIS,
      0L,
      evidenceId,
      "an independent filing bears on the margin claim"
    );

    var first = service.transition(command);
    assertTrue(first.applied());
    int eventsAfterFirst = repository.events.size();

    var replayed = service.transition(command);
    assertFalse(replayed.applied(), "a replay reports applied = false");
    assertEquals(first.eventId(), replayed.eventId());
    assertEquals(first.version(), replayed.version());
    assertEquals(first.status(), replayed.status());
    assertEquals(first.confidence(), replayed.confidence());
    assertEquals(first.confidenceBand(), replayed.confidenceBand());
    assertEquals(first.occurredAt(), replayed.occurredAt());
    assertEquals(
      eventsAfterFirst,
      repository.events.size(),
      "a replay appends no second event"
    );
    assertEquals(1L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  @Test
  void aReplayIgnoresAStaleExpectedVersion() {
    var operationId = UUID.randomUUID();
    var evidenceId = addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS).id();
    var first = service.transition(
      HypothesisTransitions.evidenceAdded(
        operationId,
        HYPOTHESIS,
        0L,
        evidenceId,
        "an independent filing bears on the margin claim"
      )
    );
    assertEquals(1L, first.version());

    // A client that timed out and retries with the version it originally read is
    // not refused: the replay writes nothing, so the stale lock cannot move state.
    var replayed = service.transition(
      HypothesisTransitions.evidenceAdded(
        operationId,
        HYPOTHESIS,
        0L,
        evidenceId,
        "an independent filing bears on the margin claim"
      )
    );
    assertFalse(replayed.applied());
    assertEquals(first.eventId(), replayed.eventId());
  }

  @Test
  void theSameOperationWithADifferentRequestIsAConflict() {
    var operationId = UUID.randomUUID();
    var evidenceId = addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS).id();
    service.transition(
      HypothesisTransitions.evidenceAdded(
        operationId,
        HYPOTHESIS,
        0L,
        evidenceId,
        "an independent filing bears on the margin claim"
      )
    );
    int events = repository.events.size();

    var differentReason = HypothesisTransitions.evidenceAdded(
      operationId,
      HYPOTHESIS,
      0L,
      evidenceId,
      "a completely different reason for the same key"
    );
    assertEquals(
      "IDEMPOTENCY_MISMATCH",
      assertThrows(ApplicationException.class, () -> service.transition(differentReason)).code()
    );

    var differentReference = HypothesisTransitions.evidenceAdded(
      operationId,
      HYPOTHESIS,
      0L,
      addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS).id(),
      "an independent filing bears on the margin claim"
    );
    assertEquals(
      "IDEMPOTENCY_MISMATCH",
      assertThrows(ApplicationException.class, () -> service.transition(differentReference)).code()
    );

    assertEquals(events, repository.events.size());
    assertEquals(1L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  // ---- optimistic concurrency -------------------------------------------

  @Test
  void aStaleExpectedVersionIsRefusedAndWritesNothing() {
    addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L);
    int events = repository.events.size();

    var conflict = assertThrows(ApplicationException.class, () ->
      addEvidenceAndTransition(SOURCE, EvidenceStance.SUPPORTS, 0L)
    );
    assertEquals(409, conflict.status());
    assertEquals("VERSION_CONFLICT", conflict.code());
    assertEquals(events, repository.events.size(), "a conflict appends no event");
    assertEquals(1L, repository.hypotheses.get(HYPOTHESIS).version());
    assertEquals(1, repository.updated);
  }

  @Test
  void aMissingExpectedVersionIsRefusedAsAConflictRatherThanGuessed() {
    var conflict = assertThrows(ApplicationException.class, () ->
      service.transition(
        HYPOTHESIS,
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          null,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          "a reason",
          addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS).id(),
          null,
          null
        )
      )
    );
    assertEquals(409, conflict.status());
    assertEquals(0L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  // ---- illegal transitions ----------------------------------------------

  @Test
  void aRejectedHypothesisIsTerminal() {
    addEvidenceAndTransition(UUID.randomUUID(), EvidenceStance.CONTRADICTS, 0L);
    var rejected = service.transition(
      HYPOTHESIS,
      new HypothesisTransitionCommand(
        UUID.randomUUID(),
        1L,
        HypothesisTransitionCause.FALSIFICATION_OBSERVED,
        "the pre-registered condition was met",
        addEvidence(UUID.randomUUID(), EvidenceStance.CONTRADICTS).id(),
        null,
        null
      )
    );
    assertEquals(HypothesisStatus.REJECTED, rejected.status());

    int events = repository.events.size();
    var refused = assertThrows(ApplicationException.class, () ->
      addEvidenceAndTransition(UUID.randomUUID(), EvidenceStance.SUPPORTS, 2L)
    );
    assertEquals(422, refused.status());
    assertTrue(refused.getMessage().contains("REJECTED"), refused.getMessage());
    assertEquals(events, repository.events.size());
    assertEquals(2L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  @Test
  void rejectingWithoutAPreRegisteredConditionIsRefused() {
    var bare = stored();
    repository.put(
      HYPOTHESIS,
      ANALYSIS,
      new Hypothesis(
        bare.id(),
        bare.title(),
        bare.description(),
        bare.status(),
        bare.confidenceReason(),
        bare.createdAt(),
        bare.updatedAt(),
        bare.type(),
        bare.statement(),
        bare.reasoning(),
        bare.confidence(),
        bare.sourceRefs(),
        bare.supportingFactRefs(),
        bare.supportingEvidenceRefs(),
        bare.contradictingEvidenceRefs(),
        bare.assumptions(),
        bare.alternativeHypothesisRefs(),
        List.<Statement>of(),
        bare.confidenceBand()
      ),
      0
    );
    var refused = assertThrows(ApplicationException.class, () ->
      service.transition(
        HYPOTHESIS,
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          0L,
          HypothesisTransitionCause.FALSIFICATION_OBSERVED,
          "the condition was met",
          addEvidence(UUID.randomUUID(), EvidenceStance.CONTRADICTS).id(),
          null,
          null
        )
      )
    );
    assertEquals(422, refused.status());
    assertTrue(refused.getMessage().contains("falsification"), refused.getMessage());
    assertEquals(0L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  @Test
  void anEvidenceItemOfAnotherHypothesisIsRefused() {
    var foreign = new StoredEvidence(
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      EvidenceStance.SUPPORTS
    );
    repository.putEvidence(foreign);
    var refused = assertThrows(ApplicationException.class, () ->
      service.transition(
        HYPOTHESIS,
        HypothesisTransitions.evidenceAdded(
          UUID.randomUUID(),
          HYPOTHESIS,
          0L,
          foreign.id(),
          "an unrelated filing"
        )
      )
    );
    assertEquals(422, refused.status());
    assertTrue(refused.getMessage().contains("different hypothesis"));
  }

  @Test
  void anUnknownReferenceIsNotFound() {
    var missing = assertThrows(ApplicationException.class, () ->
      service.transition(
        HYPOTHESIS,
        HypothesisTransitions.evidenceAdded(
          UUID.randomUUID(),
          HYPOTHESIS,
          0L,
          UUID.randomUUID(),
          "an evidence item that does not exist"
        )
      )
    );
    assertEquals(404, missing.status());
  }

  @Test
  void anEvidenceCauseWithoutAnEvidenceReferenceIsRefused() {
    var refused = assertThrows(ApplicationException.class, () ->
      service.transition(
        HYPOTHESIS,
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          0L,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          "a reason with no reference",
          null,
          null,
          null
        )
      )
    );
    assertEquals(422, refused.status());
    assertTrue(refused.getMessage().contains("evidence reference"));
  }

  @Test
  void anUnknownHypothesisIsNotFound() {
    var missing = assertThrows(ApplicationException.class, () ->
      service.transition(
        UUID.randomUUID(),
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          0L,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          "a reason",
          addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS).id(),
          null,
          null
        )
      )
    );
    assertEquals(404, missing.status());
  }

  // ---- CONFIRMED needs a verification record -----------------------------

  @Test
  void aConfirmedPredictionWithAVerificationRecordConfirmsTheHypothesis() {
    var prediction = new StoredPrediction(
      UUID.randomUUID(),
      HYPOTHESIS,
      "CONFIRMED",
      Instant.now()
    );
    repository.putPrediction(prediction);

    var result = service.transition(
      HypothesisTransitions.predictionVerified(
        UUID.randomUUID(),
        HYPOTHESIS,
        0L,
        prediction.id(),
        VerificationOutcome.CONFIRMED,
        "the pre-registered condition resolved in favour of the hypothesis"
      )
    );

    assertEquals(HypothesisStatus.CONFIRMED, result.status());
    assertEquals(
      "STATUS_CHANGED",
      repository.events.getLast().eventType(),
      "a status transition is reported as one, so CONFIRMED is not a confidence change"
    );
    // The two judgments are separate (CONFIDENCE_MODEL §8, EP-11): the status is
    // what the verification decided, the score is what the rubric computes.
    // With no evidence D3 = 0, so CAP-A pins the raw 61 at 49.
    assertEquals(49, result.confidence());
    assertEquals(ConfidenceBand.LOW, result.confidenceBand());
    assertEquals(
      prediction.id().toString(),
      trailerOfLastEvent().ref(),
      "the timeline records which prediction was verified"
    );
  }

  @Test
  void confirmingWithoutAVerificationRecordIsRefused() {
    var open = new StoredPrediction(UUID.randomUUID(), HYPOTHESIS, "OPEN", null);
    repository.putPrediction(open);
    var refused = assertThrows(ApplicationException.class, () ->
      service.transition(
        HypothesisTransitions.predictionVerified(
          UUID.randomUUID(),
          HYPOTHESIS,
          0L,
          open.id(),
          VerificationOutcome.CONFIRMED,
          "asserting a confirmation that was never recorded"
        )
      )
    );
    assertEquals(422, refused.status());
    assertTrue(refused.getMessage().contains("verification record"), refused.getMessage());
    assertEquals(0L, repository.hypotheses.get(HYPOTHESIS).version());
  }

  @Test
  void confirmingWithAVerificationStatusButNoTimestampIsRefused() {
    var untraceable = new StoredPrediction(
      UUID.randomUUID(),
      HYPOTHESIS,
      "CONFIRMED",
      null
    );
    repository.putPrediction(untraceable);
    var refused = assertThrows(ApplicationException.class, () ->
      service.transition(
        HypothesisTransitions.predictionVerified(
          UUID.randomUUID(),
          HYPOTHESIS,
          0L,
          untraceable.id(),
          VerificationOutcome.CONFIRMED,
          "a verification with no decision time"
        )
      )
    );
    assertEquals(422, refused.status());
    assertTrue(refused.getMessage().contains("timestamp"), refused.getMessage());
  }

  @Test
  void aConfirmedPredictionDoesNotConfirmAHypothesisWithAStandingContradiction() {
    addEvidence(UUID.randomUUID(), EvidenceStance.CONTRADICTS);
    var prediction = new StoredPrediction(
      UUID.randomUUID(),
      HYPOTHESIS,
      "CONFIRMED",
      Instant.now()
    );
    repository.putPrediction(prediction);

    var result = service.transition(
      HypothesisTransitions.predictionVerified(
        UUID.randomUUID(),
        HYPOTHESIS,
        0L,
        prediction.id(),
        VerificationOutcome.CONFIRMED,
        "the prediction resolved, but a contradiction still stands"
      )
    );
    assertEquals(HypothesisStatus.STRENGTHENING, result.status());
  }

  @Test
  void aRejectedPredictionWeakensRatherThanRejects() {
    var prediction = new StoredPrediction(
      UUID.randomUUID(),
      HYPOTHESIS,
      "REJECTED",
      Instant.now()
    );
    repository.putPrediction(prediction);
    var result = service.transition(
      HypothesisTransitions.predictionVerified(
        UUID.randomUUID(),
        HYPOTHESIS,
        0L,
        prediction.id(),
        VerificationOutcome.REJECTED,
        "the prediction failed"
      )
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      result.status(),
      "REJECTED is reserved for a met falsification condition"
    );
  }

  // ---- the frozen port path ---------------------------------------------

  @Test
  void theFrozenPortIdentifiesTheHypothesisFromTheCitedReference() {
    var row = addEvidence(UUID.randomUUID(), EvidenceStance.SUPPORTS);
    // TASK-07's call shape: the frozen command record carries no hypothesis id.
    var result = service.transition(
      HypothesisTransitions.evidenceAdded(
        UUID.randomUUID(),
        HYPOTHESIS,
        0L,
        row.id(),
        "evidence submitted through the frozen port"
      )
    );
    assertEquals(HYPOTHESIS, result.hypothesisId());
    assertTrue(result.applied());
    assertEquals(1L, result.version());
  }

  @Test
  void aPortCommandWithNoReferenceCannotIdentifyAHypothesis() {
    var refused = assertThrows(ApplicationException.class, () ->
      service.transition(
        new HypothesisTransitionCommand(
          UUID.randomUUID(),
          0L,
          HypothesisTransitionCause.DEADLINE_PASSED,
          "the deadline passed with no data",
          null,
          null,
          null
        )
      )
    );
    assertEquals(400, refused.status());
  }

  // ---- deadline ----------------------------------------------------------

  @Test
  void aPassedDeadlineBecomesUnresolved() {
    var prediction = new StoredPrediction(UUID.randomUUID(), HYPOTHESIS, "OPEN", null);
    repository.putPrediction(prediction);
    var result = service.transition(
      HYPOTHESIS,
      new HypothesisTransitionCommand(
        UUID.randomUUID(),
        0L,
        HypothesisTransitionCause.DEADLINE_PASSED,
        "the window closed with no data",
        null,
        prediction.id(),
        null
      )
    );
    assertEquals(HypothesisStatus.UNRESOLVED, result.status());
  }
}
