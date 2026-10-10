package com.signalframe.research.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.domain.evidence.PredictionRules;
import com.signalframe.shared.ApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TASK-07 prediction rules: the deadline boundary, the criterion that must be able to
 * discriminate, and the outcome vocabulary.
 *
 * <p>STG-14.1 makes {@code expectedBy} mandatory and future; STG-14.2 makes the
 * criteria distinguish confirmation from partial confirmation from rejection. The
 * deadline rule is the one place where an off-by-one would silently create an
 * unverifiable prediction, so the equality case is asserted directly.
 */
class PredictionRulesTest {

  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void aDeadlineStrictlyAfterNowIsAccepted() {
    assertEquals(
      NOW.plus(Duration.ofSeconds(1)),
      PredictionRules.requireFuture(NOW.plus(Duration.ofSeconds(1)), NOW)
    );
    assertEquals(
      NOW.plus(Duration.ofNanos(1)),
      PredictionRules.requireFuture(NOW.plus(Duration.ofNanos(1)), NOW)
    );
  }

  @Test
  void aDeadlineEqualToNowIsNotInTheFuture() {
    var failure = assertThrows(ApplicationException.class, () ->
      PredictionRules.requireFuture(NOW, NOW)
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
  }

  @Test
  void aDeadlineInThePastIsRejected() {
    var failure = assertThrows(ApplicationException.class, () ->
      PredictionRules.requireFuture(NOW.minus(Duration.ofDays(1)), NOW)
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
  }

  @Test
  void aMissingDeadlineIsAStructuralFailure() {
    var failure = assertThrows(ApplicationException.class, () ->
      PredictionRules.requireFuture(null, NOW)
    );
    assertEquals(400, failure.status());
    assertEquals("INVALID_REQUEST", failure.code());
  }

  @Test
  void acceptsCriteriaThatAddDistinguishingContent() {
    assertEquals(
      "the disclosed figure falls below 4.0 million in the Q3 filing",
      PredictionRules.requireDistinguishableCriteria(
        "  the disclosed figure falls below 4.0 million in the Q3 filing  ",
        "subscriber growth will reverse",
        "quarterly subscriber count"
      )
    );
  }

  @Test
  void rejectsCriteriaThatMerelyRestateThePrediction() {
    var failure = assertThrows(ApplicationException.class, () ->
      PredictionRules.requireDistinguishableCriteria(
        "Subscriber growth will reverse",
        "subscriber growth will reverse",
        "quarterly subscriber count"
      )
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
  }

  @Test
  void rejectsCriteriaThatMerelyRestateTheObservable() {
    var failure = assertThrows(ApplicationException.class, () ->
      PredictionRules.requireDistinguishableCriteria(
        "QUARTERLY   subscriber count",
        "subscriber growth will reverse",
        "quarterly subscriber count"
      )
    );
    assertEquals(422, failure.status());
    assertEquals("UNPROCESSABLE_TRANSITION", failure.code());
  }

  @Test
  void rejectsBlankCriteria() {
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      PredictionRules.requireDistinguishableCriteria("   ", "s", "o")
    ).status());
  }

  @Test
  void everyVerificationOutcomeIsAcceptedAndNothingElseIs() {
    for (VerificationOutcome outcome : VerificationOutcome.values()) {
      assertEquals(outcome, PredictionRules.requireOutcome(outcome));
    }
    assertEquals(
      List.of(
        VerificationOutcome.CONFIRMED,
        VerificationOutcome.PARTIAL,
        VerificationOutcome.REJECTED,
        VerificationOutcome.UNRESOLVED
      ),
      List.of(VerificationOutcome.values())
    );
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      PredictionRules.requireOutcome(null)
    ).status());
  }

  @Test
  void openIsTheOnlyInitialStatusAndTheResolvedSetMatchesTheOutcomes() {
    assertTrue(PredictionRules.isOpen("OPEN"));
    assertFalse(PredictionRules.isOpen("CONFIRMED"));
    assertFalse(PredictionRules.isOpen(null));
    assertEquals(
      java.util.Arrays
        .stream(VerificationOutcome.values())
        .map(Enum::name)
        .collect(java.util.stream.Collectors.toSet()),
      PredictionRules.RESOLVED
    );
  }

  @Test
  void verificationRequiresAReason() {
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      PredictionRules.requireReason("  ")
    ).status());
    assertEquals(
      "volume rose but below the pre-registered threshold",
      PredictionRules.requireReason(" volume rose but below the pre-registered threshold ")
    );
  }
}
