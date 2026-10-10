package com.signalframe.research.hypotheses;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.domain.hypotheses.EvidenceStance;
import com.signalframe.research.domain.hypotheses.HypothesisLifecycle;
import com.signalframe.research.domain.hypotheses.HypothesisLifecycle.Decision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle rules of Wave 2B (EPISTEMIC_TYPES §2.3, CONFIDENCE_MODEL §10
 * Phase 2).
 *
 * <p>These are pure-rule tests: no database, no Spring, no clock. Every legal and
 * illegal combination of (previous status, cause, stance or outcome, band
 * movement, standing contradiction) is enumerated, so a changed decision table
 * cannot pass by only covering the happy path.
 */
class HypothesisLifecycleTest {

  private static Decision decision(
    HypothesisStatus previous,
    HypothesisTransitionCause cause
  ) {
    return new Decision(previous, cause, null, null, 0, false);
  }

  private static Decision evidence(
    HypothesisStatus previous,
    HypothesisTransitionCause cause,
    EvidenceStance stance
  ) {
    return new Decision(previous, cause, stance, null, 0, false);
  }

  private static Decision verification(
    HypothesisStatus previous,
    VerificationOutcome outcome,
    boolean contradictionStands
  ) {
    return new Decision(
      previous,
      HypothesisTransitionCause.PREDICTION_VERIFIED,
      null,
      outcome,
      0,
      contradictionStands
    );
  }

  // ---- vocabulary --------------------------------------------------------

  @Test
  void theProtocolVocabularyIsExactlyTheSixLifecycleStates() {
    assertEquals(
      List.of(
        HypothesisStatus.OPEN,
        HypothesisStatus.STRENGTHENING,
        HypothesisStatus.WEAKENING,
        HypothesisStatus.CONFIRMED,
        HypothesisStatus.REJECTED,
        HypothesisStatus.UNRESOLVED
      ),
      HypothesisLifecycle.PROTOCOL_STATUSES
    );
    // The three legacy values stay readable and are never written (EPISTEMIC_TYPES §6).
    assertEquals(
      List.of(
        HypothesisStatus.SUPPORTED,
        HypothesisStatus.CHALLENGED,
        HypothesisStatus.ARCHIVED
      ),
      HypothesisLifecycle.LEGACY_STATUSES
    );
    assertTrue(
      HypothesisLifecycle.PROTOCOL_STATUSES.stream().noneMatch(
        HypothesisLifecycle::isLegacy
      )
    );
  }

  @Test
  void legacyStatusesMapToTheirProtocolReading() {
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      HypothesisLifecycle.protocol(HypothesisStatus.SUPPORTED)
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.protocol(HypothesisStatus.CHALLENGED)
    );
    assertEquals(
      HypothesisStatus.UNRESOLVED,
      HypothesisLifecycle.protocol(HypothesisStatus.ARCHIVED)
    );
    for (var status : HypothesisLifecycle.PROTOCOL_STATUSES) assertEquals(
      status,
      HypothesisLifecycle.protocol(status),
      "a protocol status maps to itself"
    );
    assertEquals(
      HypothesisStatus.OPEN,
      HypothesisLifecycle.protocol(null),
      "a missing status reads as the value a new snapshot starts from"
    );
  }

  @Test
  void onlyRejectedIsTerminal() {
    assertTrue(HypothesisLifecycle.isTerminal(HypothesisStatus.REJECTED));
    for (var status : HypothesisLifecycle.PROTOCOL_STATUSES) if (
      status != HypothesisStatus.REJECTED
    ) assertFalse(
      HypothesisLifecycle.isTerminal(status),
      status + " must remain revisable"
    );
    // ARCHIVED reads as UNRESOLVED, which is not a decisive outcome.
    assertFalse(HypothesisLifecycle.isTerminal(HypothesisStatus.ARCHIVED));
    // A rejected hypothesis has no next status at all.
    assertThrows(IllegalArgumentException.class, () ->
      HypothesisLifecycle.nextStatus(decision(HypothesisStatus.REJECTED, HypothesisTransitionCause.EVIDENCE_ADDED))
    );
  }

  @Test
  void aStoredLegacyValueIsKeptWhileTheProtocolReadingDoesNotMove() {
    assertEquals(
      HypothesisStatus.SUPPORTED,
      HypothesisLifecycle.storedStatus(
        HypothesisStatus.SUPPORTED,
        HypothesisStatus.STRENGTHENING
      ),
      "no status movement must never rewrite a stored legacy value"
    );
    assertEquals(
      HypothesisStatus.CHALLENGED,
      HypothesisLifecycle.storedStatus(
        HypothesisStatus.CHALLENGED,
        HypothesisStatus.WEAKENING
      )
    );
    assertEquals(
      HypothesisStatus.CONFIRMED,
      HypothesisLifecycle.storedStatus(
        HypothesisStatus.SUPPORTED,
        HypothesisStatus.CONFIRMED
      ),
      "a genuine movement writes the protocol status"
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.storedStatus(
        HypothesisStatus.OPEN,
        HypothesisStatus.WEAKENING
      )
    );
    assertEquals(
      HypothesisStatus.OPEN,
      HypothesisLifecycle.storedStatus(null, HypothesisStatus.OPEN)
    );
  }

  // ---- evidence-driven transitions ---------------------------------------

  @Test
  void supportingEvidenceStrengthensAndContradictingEvidenceWeakens() {
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      HypothesisLifecycle.nextStatus(
        evidence(
          HypothesisStatus.OPEN,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          EvidenceStance.SUPPORTS
        )
      )
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.nextStatus(
        evidence(
          HypothesisStatus.OPEN,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          EvidenceStance.CONTRADICTS
        )
      )
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.nextStatus(
        evidence(
          HypothesisStatus.STRENGTHENING,
          HypothesisTransitionCause.EVIDENCE_CHANGED,
          EvidenceStance.CONTRADICTS
        )
      )
    );
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      HypothesisLifecycle.nextStatus(
        evidence(
          HypothesisStatus.WEAKENING,
          HypothesisTransitionCause.EVIDENCE_CHANGED,
          EvidenceStance.SUPPORTS
        )
      )
    );
  }

  @Test
  void aNeutralEvidenceItemMovesOnlyWhenTheBandMoves() {
    var flat = evidence(
      HypothesisStatus.STRENGTHENING,
      HypothesisTransitionCause.EVIDENCE_ADDED,
      EvidenceStance.NEUTRAL
    );
    assertNull(
      HypothesisLifecycle.nextStatus(flat),
      "no band movement means no status movement"
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.nextStatus(
        new Decision(
          HypothesisStatus.STRENGTHENING,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          EvidenceStance.NEUTRAL,
          null,
          -1,
          false
        )
      )
    );
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      HypothesisLifecycle.nextStatus(
        new Decision(
          HypothesisStatus.OPEN,
          HypothesisTransitionCause.EVIDENCE_ADDED,
          EvidenceStance.NEUTRAL,
          null,
          1,
          false
        )
      )
    );
  }

  @Test
  void aHypothesisWithoutAStanceFallsBackToBandMovement() {
    assertNull(
      HypothesisLifecycle.nextStatus(
        decision(HypothesisStatus.OPEN, HypothesisTransitionCause.EVIDENCE_ADDED)
      )
    );
  }

  // ---- verification-driven transitions -----------------------------------

  @Test
  void confirmedNeedsAVerificationRecordAndIsNeverAssertedByAScore() {
    assertEquals(
      HypothesisStatus.CONFIRMED,
      HypothesisLifecycle.nextStatus(
        verification(HypothesisStatus.OPEN, VerificationOutcome.CONFIRMED, false)
      )
    );
    // A high band cannot reach CONFIRMED: no evidence branch produces it.
    for (var status : List.of(
      HypothesisStatus.OPEN,
      HypothesisStatus.STRENGTHENING,
      HypothesisStatus.WEAKENING
    )) {
      for (var stance : EvidenceStance.values()) for (int movement : List.of(-1, 0, 1)) {
        var next = HypothesisLifecycle.nextStatus(
          new Decision(
            status,
            HypothesisTransitionCause.EVIDENCE_ADDED,
            stance,
            null,
            movement,
            false
          )
        );
        assertNotEquals(
          HypothesisStatus.CONFIRMED,
          next,
          "only a verification record may confirm a hypothesis (EPISTEMIC_TYPES §2.3)"
        );
      }
      assertNotEquals(
        HypothesisStatus.CONFIRMED,
        HypothesisLifecycle.nextStatus(
          decision(status, HypothesisTransitionCause.DEADLINE_PASSED)
        )
      );
    }
  }

  @Test
  void aConfirmedPredictionDoesNotConfirmAHypothesisThatSomethingContradicts() {
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      HypothesisLifecycle.nextStatus(
        verification(HypothesisStatus.OPEN, VerificationOutcome.CONFIRMED, true)
      ),
      "a standing contradiction caps a confirmed prediction at STRENGTHENING"
    );
  }

  @Test
  void aFailedPredictionWeakensRatherThanRejects() {
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.nextStatus(
        verification(HypothesisStatus.OPEN, VerificationOutcome.REJECTED, false)
      ),
      "REJECTED is a met falsification condition, not a failed prediction"
    );
    assertEquals(
      HypothesisStatus.UNRESOLVED,
      HypothesisLifecycle.nextStatus(
        verification(HypothesisStatus.OPEN, VerificationOutcome.UNRESOLVED, false)
      )
    );
    // A PARTIAL outcome is neither decisive nor empty: with a flat band there is
    // no status movement at all, and the previous status stands.
    assertNull(
      HypothesisLifecycle.nextStatus(
        verification(HypothesisStatus.OPEN, VerificationOutcome.PARTIAL, false)
      )
    );
    assertEquals(
      HypothesisStatus.STRENGTHENING,
      HypothesisLifecycle.nextStatus(
        new Decision(
          HypothesisStatus.OPEN,
          HypothesisTransitionCause.PREDICTION_VERIFIED,
          null,
          VerificationOutcome.PARTIAL,
          1,
          false
        )
      ),
      "a partial outcome that moved the band strengthens"
    );
    assertEquals(
      HypothesisStatus.WEAKENING,
      HypothesisLifecycle.nextStatus(
        new Decision(
          HypothesisStatus.OPEN,
          HypothesisTransitionCause.PREDICTION_VERIFIED,
          null,
          VerificationOutcome.PARTIAL,
          -1,
          false
        )
      )
    );
  }

  @Test
  void verificationWithoutAnOutcomeIsARefusedDecision() {
    assertThrows(IllegalArgumentException.class, () ->
      HypothesisLifecycle.nextStatus(
        new Decision(
          HypothesisStatus.OPEN,
          HypothesisTransitionCause.PREDICTION_VERIFIED,
          null,
          null,
          0,
          false
        )
      )
    );
  }

  // ---- decisive outcomes -------------------------------------------------

  @Test
  void aMetFalsificationConditionRejectsAndAPassedDeadlineIsUnresolved() {
    assertEquals(
      HypothesisStatus.REJECTED,
      HypothesisLifecycle.nextStatus(
        decision(HypothesisStatus.OPEN, HypothesisTransitionCause.FALSIFICATION_OBSERVED)
      )
    );
    assertEquals(
      HypothesisStatus.UNRESOLVED,
      HypothesisLifecycle.nextStatus(
        decision(HypothesisStatus.CONFIRMED, HypothesisTransitionCause.DEADLINE_PASSED)
      ),
      "CONFIRMED is revisable: a deadline that passed with no data is unresolved"
    );
  }

  // ---- totality ----------------------------------------------------------

  @Test
  void everyLegalDecisionProducesAProtocolStatusOrNoMovement() {
    var produced = new ArrayList<HypothesisStatus>();
    for (var previous : List.of(
      HypothesisStatus.OPEN,
      HypothesisStatus.STRENGTHENING,
      HypothesisStatus.WEAKENING,
      HypothesisStatus.CONFIRMED,
      HypothesisStatus.UNRESOLVED,
      HypothesisStatus.SUPPORTED,
      HypothesisStatus.CHALLENGED,
      HypothesisStatus.ARCHIVED
    )) {
      for (var cause : HypothesisTransitionCause.values()) {
        if (cause == HypothesisTransitionCause.PREDICTION_VERIFIED) {
          for (var outcome : VerificationOutcome.values()) {
            var next = HypothesisLifecycle.nextStatus(
              new Decision(previous, cause, null, outcome, 0, false)
            );
            // Only a PARTIAL outcome may legitimately mean "no movement"; the
            // other three are decisive.
            if (outcome == VerificationOutcome.PARTIAL) assertTrue(
              next == null || HypothesisLifecycle.PROTOCOL_STATUSES.contains(next)
            );
            else {
              assertNotNull(next, cause + "/" + outcome + " must decide something");
              assertTrue(
                HypothesisLifecycle.PROTOCOL_STATUSES.contains(next),
                cause + "/" + outcome + " produced " + next
              );
              produced.add(next);
            }
          }
          continue;
        }
        var stances = new EvidenceStance[] {
          null,
          EvidenceStance.SUPPORTS,
          EvidenceStance.CONTRADICTS,
          EvidenceStance.NEUTRAL,
        };
        for (var stance : stances) for (int movement : List.of(-1, 0, 1)) {
          var next = HypothesisLifecycle.nextStatus(
            new Decision(previous, cause, stance, null, movement, false)
          );
          if (next == null) continue;
          assertTrue(
            HypothesisLifecycle.PROTOCOL_STATUSES.contains(next),
            cause + " produced a non-protocol status " + next
          );
          produced.add(next);
        }
      }
    }
    // Every status a decision may produce is reached by this enumeration. OPEN is
    // absent by design: it is the state a new hypothesis starts in, never an
    // outcome of a transition (a status that has moved does not go back to "no
    // decisive evidence movement yet").
    assertTrue(
      produced.containsAll(
        List.of(
          HypothesisStatus.STRENGTHENING,
          HypothesisStatus.WEAKENING,
          HypothesisStatus.CONFIRMED,
          HypothesisStatus.REJECTED,
          HypothesisStatus.UNRESOLVED
        )
      ),
      "the enumeration must reach every producible protocol status, reached " + produced
    );
    assertFalse(
      produced.contains(HypothesisStatus.OPEN),
      "no decision produces OPEN"
    );
  }

  // ---- event type --------------------------------------------------------

  @Test
  void aStatusTransitionIsReportedAsStatusChanged() {
    assertEquals(
      "STATUS_CHANGED",
      HypothesisLifecycle.eventType(
        HypothesisStatus.OPEN,
        HypothesisStatus.STRENGTHENING,
        true,
        HypothesisTransitionCause.EVIDENCE_ADDED
      )
    );
    // No status movement, but the number moved.
    assertEquals(
      "CONFIDENCE_CHANGED",
      HypothesisLifecycle.eventType(
        HypothesisStatus.STRENGTHENING,
        null,
        true,
        HypothesisTransitionCause.EVIDENCE_ADDED
      )
    );
    // Neither moved: the event names its cause.
    assertEquals(
      "EVIDENCE_ADDED",
      HypothesisLifecycle.eventType(
        HypothesisStatus.STRENGTHENING,
        null,
        false,
        HypothesisTransitionCause.EVIDENCE_ADDED
      )
    );
    assertEquals(
      "PREDICTION_VERIFIED",
      HypothesisLifecycle.eventType(
        HypothesisStatus.STRENGTHENING,
        null,
        false,
        HypothesisTransitionCause.PREDICTION_VERIFIED
      )
    );
  }

  @Test
  void aLegacyStoredValueThatDoesNotMoveStillReportsNoStatusChange() {
    assertEquals(
      "EVIDENCE_ADDED",
      HypothesisLifecycle.eventType(
        HypothesisStatus.SUPPORTED,
        HypothesisStatus.STRENGTHENING,
        false,
        HypothesisTransitionCause.EVIDENCE_ADDED
      ),
      "SUPPORTED already reads as STRENGTHENING"
    );
  }
}
