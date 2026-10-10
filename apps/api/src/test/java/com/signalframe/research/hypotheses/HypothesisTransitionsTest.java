package com.signalframe.research.hypotheses;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import com.signalframe.research.domain.hypotheses.HypothesisTransitions;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Freeze test for the Wave 2B cross-module port.
 *
 * <p>This test asserts the <em>shape</em> of the contract that TASK-06 and TASK-07
 * share, not any transition behaviour: the point of P1 is that both tasks can be
 * implemented in parallel against a signature that cannot silently drift. The
 * behaviour belongs to TASK-06's implementation and its own tests.
 */
class HypothesisTransitionsTest {

  @Test
  void thePortHasExactlyOneTransitionOperation() {
    var declared = HypothesisTransitionPort.class.getDeclaredMethods();
    assertEquals(1, declared.length, "one way in, one way out");
    Method transition = declared[0];
    assertEquals("transition", transition.getName());
    assertEquals(HypothesisTransitionResult.class, transition.getReturnType());
    assertArrayEquals(
      new Class<?>[] { HypothesisTransitionCommand.class },
      transition.getParameterTypes()
    );
  }

  @Test
  void evidenceCommandsCarryTheEvidenceReferenceAndNoOutcome() {
    UUID operation = UUID.randomUUID();
    UUID hypothesis = UUID.randomUUID();
    UUID evidence = UUID.randomUUID();
    var command = HypothesisTransitions.evidenceAdded(
      operation,
      hypothesis,
      3L,
      evidence,
      "independent filing contradicts the reported volume"
    );
    assertEquals(operation, command.operationId());
    assertEquals(3L, command.expectedVersion());
    assertEquals(HypothesisTransitionCause.EVIDENCE_ADDED, command.cause());
    assertEquals(evidence, command.evidenceRef());
    assertNull(command.predictionRef());
    assertNull(command.verificationOutcome());
    assertFalse(command.reason().isBlank());
  }

  @Test
  void verificationCommandsCarryThePredictionReferenceAndTheOutcome() {
    UUID prediction = UUID.randomUUID();
    var command = HypothesisTransitions.predictionVerified(
      UUID.randomUUID(),
      UUID.randomUUID(),
      0L,
      prediction,
      VerificationOutcome.PARTIAL,
      "volume rose, but below the pre-registered threshold"
    );
    assertEquals(HypothesisTransitionCause.PREDICTION_VERIFIED, command.cause());
    assertEquals(prediction, command.predictionRef());
    assertNull(command.evidenceRef());
    assertEquals(VerificationOutcome.PARTIAL, command.verificationOutcome());
  }

  @Test
  void aCommandWithoutAReasonOrAReferenceIsRejected() {
    UUID operation = UUID.randomUUID();
    UUID hypothesis = UUID.randomUUID();
    UUID evidence = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () ->
      HypothesisTransitions.evidenceAdded(operation, hypothesis, 0L, evidence, "  ")
    );
    assertThrows(IllegalArgumentException.class, () ->
      HypothesisTransitions.evidenceAdded(operation, hypothesis, 0L, null, "reason")
    );
    assertThrows(IllegalArgumentException.class, () ->
      HypothesisTransitions.evidenceAdded(operation, hypothesis, -1L, evidence, "reason")
    );
    assertThrows(NullPointerException.class, () ->
      HypothesisTransitions.predictionVerified(
        operation,
        hypothesis,
        0L,
        UUID.randomUUID(),
        null,
        "reason"
      )
    );
  }

  @Test
  void theResultVocabularyIsFrozenToTheProtocolStatusAndBand() {
    // A result must be able to express the optimistic-lock movement, the status
    // transition and the deterministic rubric's band in one object.
    var components = java.util.Arrays.stream(
      HypothesisTransitionResult.class.getRecordComponents()
    ).map(java.lang.reflect.RecordComponent::getName).toList();
    assertTrue(components.containsAll(java.util.List.of(
      "operationId",
      "hypothesisId",
      "applied",
      "previousVersion",
      "version",
      "previousStatus",
      "status",
      "previousConfidence",
      "confidence",
      "confidenceBand",
      "eventId",
      "occurredAt"
    )), components.toString());
    // Legacy statuses stay readable (EPISTEMIC_TYPES §6) and the protocol ones
    // are writable.
    assertNotNull(HypothesisStatus.valueOf("STRENGTHENING"));
    assertNotNull(HypothesisStatus.valueOf("WEAKENING"));
    assertNotNull(HypothesisStatus.valueOf("CONFIRMED"));
    assertNotNull(HypothesisStatus.valueOf("UNRESOLVED"));
    assertNotNull(HypothesisStatus.valueOf("SUPPORTED"));
  }
}
