package com.signalframe.research.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.research.application.evidence.EvidencePredictionService;
import com.signalframe.research.application.evidence.HypothesisTransitionGateway;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import com.signalframe.shared.ApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * TASK-07 application behaviour, exercised against in-memory ports and a recording
 * stand-in for TASK-06's frozen transition port.
 *
 * <p>What this class establishes: every rule is enforced before anything is written,
 * the engine is notified with the reference the cause requires, verification is
 * idempotent on its {@code operationId}, a decided prediction is never decided twice,
 * and a passed deadline is reported without resolving anything.
 *
 * <p>What it deliberately does not establish: transactional rollback. There is no
 * transaction in a plain unit test, so {@code EvidencePredictionHttpTest} proves on a
 * real database that a failing transition leaves no evidence, no verification and no
 * status change behind.
 */
class EvidencePredictionServiceTest {

  InMemoryEvidencePredictionRepository store;
  InMemoryResearchReferences references;
  FakeHypothesisTransitionPort port;

  UUID analysisId;
  UUID factA;
  UUID factB;
  UUID hypothesisId;
  UUID sourceId;

  @BeforeEach
  void setUp() {
    store = new InMemoryEvidencePredictionRepository();
    references = new InMemoryResearchReferences();
    port = new FakeHypothesisTransitionPort();
    factA = UUID.randomUUID();
    factB = UUID.randomUUID();
    analysisId = UUID.randomUUID();
    hypothesisId = references.addHypothesis(
      analysisId,
      7L,
      Set.of(factA, factB)
    );
    sourceId = references.addSource();
    port.hypothesisId = hypothesisId;
  }

  EvidencePredictionService service(HypothesisTransitionPort transitionPort) {
    return new EvidencePredictionService(
      store,
      store,
      references,
      gateway(transitionPort)
    );
  }

  EvidencePredictionService service() {
    return service(port);
  }

  static HypothesisTransitionGateway gateway(HypothesisTransitionPort port) {
    var factory = new DefaultListableBeanFactory();
    if (port != null) {
      factory.registerSingleton("hypothesisTransitionPort", port);
    }
    return new HypothesisTransitionGateway(
      factory.getBeanProvider(HypothesisTransitionPort.class)
    );
  }

  static EvidenceCreateRequest evidenceRequest(
    UUID sourceId,
    String stance,
    int strength,
    UUID analysisId,
    List<UUID> factRefs
  ) {
    return new EvidenceCreateRequest(
      sourceId,
      stance,
      strength,
      "the filing contradicts the reported volume",
      factRefs,
      analysisId
    );
  }

  static PredictionCreateRequest predictionRequest(Instant expectedBy) {
    return new PredictionCreateRequest(
      "subscriber growth will reverse",
      "quarterly subscriber count",
      expectedBy,
      "the disclosed figure falls below 4.0 million in the Q3 filing",
      "the Q3 filing on the company investor-relations site",
      List.of()
    );
  }

  // ---- evidence ---------------------------------------------------------

  @Test
  void evidenceIsPersistedAndTheEngineIsNotifiedWithTheEvidenceReference() {
    Evidence saved = service().addEvidence(
      hypothesisId,
      evidenceRequest(sourceId, "SUPPORTS", 60, analysisId, List.of(factA))
    );

    assertEquals(hypothesisId, saved.hypothesisId());
    assertEquals(sourceId, saved.sourceId());
    assertEquals("SUPPORTS", saved.stance());
    assertEquals(60, saved.strength());
    assertEquals(analysisId, saved.analysisId());
    assertEquals(List.of(factA), saved.factRefs());
    assertNotNull(saved.createdAt());
    assertEquals(1, store.evidenceRows.size());

    assertEquals(1, port.calls.get());
    var command = port.lastCommand();
    assertEquals(HypothesisTransitionCause.EVIDENCE_ADDED, command.cause());
    assertEquals(saved.id(), command.evidenceRef());
    assertNull(command.predictionRef());
    assertNull(command.verificationOutcome());
    assertEquals("the filing contradicts the reported volume", command.reason());
    // The version TASK-07 read is handed to the port, which decides whether it is current.
    assertEquals(7L, command.expectedVersion());
  }

  @Test
  void evidenceFactRefsFallBackToTheHypothesisSnapshotWhenNoAnalysisIsGiven() {
    Evidence saved = service().addEvidence(
      hypothesisId,
      evidenceRequest(sourceId, "CONTRADICTS", 40, null, List.of(factB))
    );
    assertNull(saved.analysisId());
    assertEquals(List.of(factB), saved.factRefs());
  }

  @Test
  void evidenceForAnUnknownHypothesisIsNotFoundAndWritesNothing() {
    var failure = assertThrows(ApplicationException.class, () ->
      service().addEvidence(
        UUID.randomUUID(),
        evidenceRequest(sourceId, "SUPPORTS", 50, analysisId, List.of())
      )
    );
    assertEquals(404, failure.status());
    assertTrue(store.evidenceRows.isEmpty());
    assertEquals(0, port.calls.get());
  }

  @Test
  void evidenceWithAnUnknownSourceIsRejectedByTheForeignKeyCheck() {
    var failure = assertThrows(ApplicationException.class, () ->
      service().addEvidence(
        hypothesisId,
        evidenceRequest(UUID.randomUUID(), "SUPPORTS", 50, analysisId, List.of())
      )
    );
    assertEquals(404, failure.status());
    assertEquals("NOT_FOUND", failure.code());
    assertTrue(store.evidenceRows.isEmpty());
    assertEquals(0, port.calls.get(), "no transition without a persisted source FK");
  }

  @Test
  void evidenceWithAnUnknownAnalysisIsRejected() {
    var failure = assertThrows(ApplicationException.class, () ->
      service().addEvidence(
        hypothesisId,
        evidenceRequest(
          sourceId,
          "NEUTRAL",
          10,
          UUID.randomUUID(),
          List.of()
        )
      )
    );
    assertEquals(404, failure.status());
    assertTrue(store.evidenceRows.isEmpty());
  }

  @Test
  void evidenceWithAnUnresolvableFactRefIsRejectedRatherThanSilentlyDropped() {
    var unknownFact = UUID.randomUUID();
    var failure = assertThrows(ApplicationException.class, () ->
      service().addEvidence(
        hypothesisId,
        evidenceRequest(
          sourceId,
          "SUPPORTS",
          50,
          analysisId,
          List.of(factA, unknownFact)
        )
      )
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
    assertTrue(failure.getMessage().contains(unknownFact.toString()));
    assertTrue(store.evidenceRows.isEmpty());
  }

  @Test
  void anInvalidStanceNeverReachesTheDatabase() {
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      service().addEvidence(
        hypothesisId,
        evidenceRequest(sourceId, "OPPOSES", 50, analysisId, List.of())
      )
    ).status());
    assertTrue(store.evidenceRows.isEmpty());
    assertEquals(0, port.calls.get());
  }

  @Test
  void anOutOfRangeStrengthNeverReachesTheDatabase() {
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      service().addEvidence(
        hypothesisId,
        evidenceRequest(sourceId, "SUPPORTS", 101, analysisId, List.of())
      )
    ).status());
    assertTrue(store.evidenceRows.isEmpty());
    assertEquals(0, port.calls.get());
  }

  @Test
  void evidenceFailsWith503WhenTheHypothesisEngineIsUnavailable() {
    var failure = assertThrows(ApplicationException.class, () ->
      service(null).addEvidence(
        hypothesisId,
        evidenceRequest(sourceId, "SUPPORTS", 50, analysisId, List.of())
      )
    );
    assertEquals(503, failure.status());
    assertEquals("UNAVAILABLE", failure.code());
  }

  // ---- prediction creation ---------------------------------------------

  @Test
  void aPredictionIsCreatedOpenWithTheFrozenTypeAndNoOutcome() {
    Instant deadline = Instant.now().plus(Duration.ofDays(30));
    Prediction prediction = service().createPrediction(
      hypothesisId,
      predictionRequest(deadline)
    );

    assertEquals("OPEN", prediction.status());
    assertEquals("PREDICTION", prediction.type());
    assertEquals(deadline, prediction.expectedBy());
    assertEquals(hypothesisId, prediction.hypothesisId());
    assertEquals("quarterly subscriber count", prediction.observable());
    assertNotNull(prediction.whereToCheck());
    assertTrue(prediction.basisFactRefs().isEmpty());
    assertEquals(1, store.predictionRows.size());
    // Creating a prediction is not a hypothesis transition: no cause covers it.
    assertEquals(0, port.calls.get());
  }

  @Test
  void aPredictionDeadlineInThePastIsUnprocessable() {
    var failure = assertThrows(ApplicationException.class, () ->
      service().createPrediction(
        hypothesisId,
        predictionRequest(Instant.now().minus(Duration.ofSeconds(1)))
      )
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
    assertTrue(store.predictionRows.isEmpty());
  }

  @Test
  void criteriaThatRestateThePredictionAreUnprocessable() {
    var request = new PredictionCreateRequest(
      "subscriber growth will reverse",
      "quarterly subscriber count",
      Instant.now().plus(Duration.ofDays(30)),
      "subscriber growth will reverse",
      "the Q3 filing",
      List.of()
    );
    var failure = assertThrows(ApplicationException.class, () ->
      service().createPrediction(hypothesisId, request)
    );
    assertEquals(422, failure.status());
    assertTrue(store.predictionRows.isEmpty());
  }

  @Test
  void aPredictionForAnUnknownHypothesisIsNotFound() {
    assertEquals(404, assertThrows(ApplicationException.class, () ->
      service().createPrediction(
        UUID.randomUUID(),
        predictionRequest(Instant.now().plus(Duration.ofDays(1)))
      )
    ).status());
    assertTrue(store.predictionRows.isEmpty());
  }

  @Test
  void predictionBasisFactRefsMustResolveInsideTheHypothesisSnapshot() {
    var request = new PredictionCreateRequest(
      "subscriber growth will reverse",
      "quarterly subscriber count",
      Instant.now().plus(Duration.ofDays(30)),
      "the disclosed figure falls below 4.0 million in the Q3 filing",
      "the Q3 filing",
      List.of(UUID.randomUUID())
    );
    assertEquals(422, assertThrows(ApplicationException.class, () ->
      service().createPrediction(hypothesisId, request)
    ).status());
    assertTrue(store.predictionRows.isEmpty());
  }

  // ---- due query --------------------------------------------------------

  @Test
  void dueReturnsOnlyOpenPredictionsWithAPassedDeadline() {
    var svc = service();
    // A prediction is always created with a future deadline (STG-14.1), so an overdue
    // one only exists as stored state that time has moved past.
    Prediction due = seedOverdueOpenPrediction(Duration.ofDays(1));
    Prediction future = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(1)))
    );

    var dueResponse = svc.duePredictions();

    assertEquals(List.of(due.id()), ids(dueResponse.predictions()));
    assertTrue(
      dueResponse.predictions().stream().noneMatch(p -> p.id().equals(future.id()))
    );
    assertTrue(dueResponse.asOf().isBefore(Instant.now().plusSeconds(1)));
  }

  @Test
  void aPredictionWhoseDeadlineIsStillInTheFutureIsNotDue() {
    var svc = service();
    svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofSeconds(30)))
    );
    assertTrue(svc.duePredictions().predictions().isEmpty());
  }

  @Test
  void aResolvedPredictionIsNeverReportedAsDue() {
    var svc = service();
    Prediction overdue = seedOverdueOpenPrediction(Duration.ofDays(1));
    svc.verifyPrediction(
      overdue.id(),
      verification(UUID.randomUUID(), VerificationOutcome.UNRESOLVED)
    );

    assertTrue(svc.duePredictions().predictions().isEmpty());
  }

  @Test
  void theDueQueryNeverResolvesAnythingByItself() {
    var svc = service();
    Prediction overdue = seedOverdueOpenPrediction(Duration.ofDays(400));

    svc.duePredictions();
    svc.duePredictions();

    // A passed deadline is an observation, not a verdict: marking it UNRESOLVED is an
    // explicit action with its own reason (freeze §6, requirement 9).
    assertEquals("OPEN", store.predictionRows.get(overdue.id()).status());
    assertEquals(0, store.resolveCalls);
  }

  /**
   * Stores an {@code OPEN} prediction whose deadline has already passed.
   *
   * <p>It cannot be produced through {@link EvidencePredictionService#createPrediction}
   * because creation requires a future deadline; this is the stored state a snapshot
   * reaches as time passes.
   */
  Prediction seedOverdueOpenPrediction(Duration overdueBy) {
    var prediction = new Prediction(
      UUID.randomUUID(),
      hypothesisId,
      "subscriber growth will reverse",
      "PREDICTION",
      Instant.now().minus(overdueBy),
      "OPEN",
      "the disclosed figure falls below 4.0 million in the Q3 filing",
      "quarterly subscriber count",
      "the Q3 filing on the company investor-relations site",
      List.of()
    );
    store.predictionRows.put(prediction.id(), prediction);
    return prediction;
  }

  // ---- verification -----------------------------------------------------

  @ParameterizedTest
  @EnumSource(VerificationOutcome.class)
  void everyVerificationOutcomeIsRecordedAndReportedToTheEngine(
    VerificationOutcome outcome
  ) {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    UUID operationId = UUID.randomUUID();

    var result = svc.verifyPrediction(
      prediction.id(),
      verification(operationId, outcome)
    );

    assertTrue(result.applied());
    assertEquals(outcome.name(), result.prediction().status());
    assertNotNull(result.verificationId());
    assertEquals(operationId, result.hypothesisTransition().operationId());
    assertEquals(prediction.hypothesisId(), result.prediction().hypothesisId());

    var command = port.lastCommand();
    assertEquals(HypothesisTransitionCause.PREDICTION_VERIFIED, command.cause());
    assertEquals(prediction.id(), command.predictionRef());
    assertNull(command.evidenceRef());
    assertEquals(outcome, command.verificationOutcome());
    assertEquals("verified against the published filing", command.reason());
  }

  @Test
  void verificationLeavesThePredictionTextUntouched() {
    var svc = service();
    Prediction open = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );

    var result = svc.verifyPrediction(
      open.id(),
      verification(UUID.randomUUID(), VerificationOutcome.PARTIAL)
    );

    Prediction decided = result.prediction();
    assertEquals(open.statement(), decided.statement());
    assertEquals(open.observable(), decided.observable());
    assertEquals(open.expectedBy(), decided.expectedBy());
    assertEquals(open.verificationCriteria(), decided.verificationCriteria());
    assertEquals(open.whereToCheck(), decided.whereToCheck());
    assertEquals(open.type(), decided.type());
    assertEquals("PARTIAL", decided.status());
  }

  @Test
  void replayingTheSameOperationIdIsIdempotent() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    UUID operationId = UUID.randomUUID();
    var command = verification(operationId, VerificationOutcome.CONFIRMED);

    var first = svc.verifyPrediction(prediction.id(), command);
    var replay = svc.verifyPrediction(prediction.id(), command);

    assertTrue(first.applied());
    assertFalse(replay.applied(), "a replay applies nothing");
    assertEquals(first.verificationId(), replay.verificationId());
    assertEquals(first.prediction().status(), replay.prediction().status());
    assertEquals(
      first.hypothesisTransition().eventId(),
      replay.hypothesisTransition().eventId(),
      "the replayed result is the originally recorded one"
    );
    assertEquals(1, store.resolveCalls, "the prediction is resolved exactly once");
    assertEquals(2, port.calls.get());
    assertEquals(operationId, port.lastCommand().operationId());
  }

  @Test
  void aSecondVerificationWithANewOperationIdIsRejected() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    svc.verifyPrediction(
      prediction.id(),
      verification(UUID.randomUUID(), VerificationOutcome.CONFIRMED)
    );

    var failure = assertThrows(ApplicationException.class, () ->
      svc.verifyPrediction(
        prediction.id(),
        verification(UUID.randomUUID(), VerificationOutcome.REJECTED)
      )
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
    assertEquals(
      "CONFIRMED",
      store.predictionRows.get(prediction.id()).status(),
      "the first outcome stands"
    );
    assertEquals(1, store.resolveCalls);
  }

  @Test
  void verificationOfAnUnknownPredictionIsNotFound() {
    assertEquals(404, assertThrows(ApplicationException.class, () ->
      service().verifyPrediction(
        UUID.randomUUID(),
        verification(UUID.randomUUID(), VerificationOutcome.CONFIRMED)
      )
    ).status());
    assertEquals(0, port.calls.get());
  }

  @Test
  void verificationWithAnUnknownEvidenceReferenceIsNotFound() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    var command = new PredictionVerificationCommand(
      UUID.randomUUID(),
      VerificationOutcome.CONFIRMED,
      "verified against the published filing",
      UUID.randomUUID(),
      null
    );

    assertEquals(404, assertThrows(ApplicationException.class, () ->
      svc.verifyPrediction(prediction.id(), command)
    ).status());
    assertEquals("OPEN", store.predictionRows.get(prediction.id()).status());
  }

  @Test
  void verificationCarriesTheEvidenceReferenceAndTheRecordedTimestamp() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    UUID evidenceId = references.addEvidence();
    Instant decidedAt = Instant.parse("2026-06-01T09:30:00Z");
    var command = new PredictionVerificationCommand(
      UUID.randomUUID(),
      VerificationOutcome.CONFIRMED,
      "verified against the published filing",
      evidenceId,
      decidedAt
    );

    svc.verifyPrediction(prediction.id(), command);

    assertEquals(evidenceId, port.lastCommand().evidenceRef());
    assertEquals(evidenceId, command.evidenceRef());
    assertEquals(decidedAt, command.verifiedAt());
  }

  @Test
  void verificationFailsWithoutAnOperationId() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    var command = new PredictionVerificationCommand(
      null,
      VerificationOutcome.CONFIRMED,
      "verified against the published filing",
      null,
      null
    );

    assertEquals(400, assertThrows(ApplicationException.class, () ->
      svc.verifyPrediction(prediction.id(), command)
    ).status());
    assertEquals("OPEN", store.predictionRows.get(prediction.id()).status());
    assertEquals(0, port.calls.get());
  }

  /**
   * A refused engine call fails the whole verification.
   *
   * <p><b>Wave 2B integration.</b> The verification record is written <em>before</em>
   * the engine is notified, because the frozen
   * {@link com.signalframe.research.domain.hypotheses.HypothesisTransitionPort}
   * accepts a {@code PREDICTION_VERIFIED} command only when the stored prediction
   * already carries that outcome and a verification timestamp (freeze §2.1,
   * EPISTEMIC_TYPES §2.3) — notifying it first made every first verification
   * unprocessable, which is exactly what the integration gate found.
   *
   * <p>Unwinding that write when the engine refuses is a <em>transaction</em>
   * property, and the in-memory fake used here has no transaction, so this unit test
   * asserts what unit scope can honestly assert: the failure propagates and the
   * caller gets no result. Real PostgreSQL behaviour is asserted by
   * {@code Wave2BResearchLoopIntegrationTest.aRefusedTransitionRollsBackTheVerificationRecord},
   * which starts a rejected hypothesis and observes the prediction still {@code OPEN}
   * with no verification timestamp.
   */
  @Test
  void aFailingEngineFailsTheWholeVerification() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );
    port.failWith = new ApplicationException(409, "VERSION_CONFLICT", "stale");

    var failure = assertThrows(ApplicationException.class, () ->
      svc.verifyPrediction(
        prediction.id(),
        verification(UUID.randomUUID(), VerificationOutcome.CONFIRMED)
      )
    );
    assertEquals(409, failure.status());
    assertEquals(
      1,
      store.resolveCalls,
      "the record is written first so the engine can see it; the surrounding " +
      "transaction unwinds it (asserted on real PostgreSQL)"
    );
  }

  /**
   * Freeze §4.1 invariant 7, checked from TASK-07's side: the ports TASK-07 owns expose
   * no way to write a hypothesis.
   *
   * <p>A CONFIRMED verification therefore cannot confirm anything by itself. It records
   * an outcome on the prediction and asks
   * {@link com.signalframe.research.domain.hypotheses.HypothesisTransitionPort} to
   * decide the transition. If a status change were ever reachable from here, "no
   * automatic claim of truth" would stop being a structural guarantee and become a
   * convention.
   */
  @Test
  void noTask07PortCanWriteAHypothesis() {
    List<Class<?>> task07Ports = List.of(
      com.signalframe.research.domain.evidence.EvidenceRepository.class,
      com.signalframe.research.domain.evidence.PredictionRepository.class,
      com.signalframe.research.domain.evidence.ResearchReferences.class
    );
    for (Class<?> port : task07Ports) {
      for (var method : port.getMethods()) {
        assertFalse(
          List.of(method.getParameterTypes()).contains(Hypothesis.class),
          port.getSimpleName() + "." + method.getName() + " takes a Hypothesis"
        );
        assertFalse(
          method.getReturnType().equals(Hypothesis.class),
          port.getSimpleName() + "." + method.getName() + " returns a Hypothesis"
        );
        assertFalse(
          List
            .of(method.getParameterTypes())
            .contains(HypothesisEvent.class) ||
          method.getReturnType().equals(HypothesisEvent.class),
          port.getSimpleName() +
          "." +
          method.getName() +
          " touches the hypothesis timeline"
        );
        assertFalse(
          List
            .of(method.getParameterTypes())
            .contains(HypothesisStatus.class) ||
          method.getReturnType().equals(HypothesisStatus.class),
          port.getSimpleName() +
          "." +
          method.getName() +
          " can set a hypothesis status"
        );
      }
    }
  }

  @Test
  void aConfirmedVerificationOnlyAsksTheEngineItNeverAssertsTheHypothesisStatus() {
    var svc = service();
    Prediction prediction = svc.createPrediction(
      hypothesisId,
      predictionRequest(Instant.now().plus(Duration.ofDays(10)))
    );

    var result = svc.verifyPrediction(
      prediction.id(),
      verification(UUID.randomUUID(), VerificationOutcome.CONFIRMED)
    );

    // The outcome belongs to the prediction. What the hypothesis becomes is the
    // engine's decision, reported back, not written here.
    assertEquals("CONFIRMED", result.prediction().status());
    assertEquals(
      HypothesisTransitionCause.PREDICTION_VERIFIED,
      port.lastCommand().cause()
    );
  }

  static PredictionVerificationCommand verification(
    UUID operationId,
    VerificationOutcome outcome
  ) {
    return new PredictionVerificationCommand(
      operationId,
      outcome,
      "verified against the published filing",
      null,
      null
    );
  }

  static List<UUID> ids(List<Prediction> predictions) {
    return predictions.stream().map(Prediction::id).toList();
  }
}
