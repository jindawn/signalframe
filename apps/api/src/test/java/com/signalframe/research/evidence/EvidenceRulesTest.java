package com.signalframe.research.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.research.domain.evidence.EvidenceRules;
import com.signalframe.shared.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TASK-07 evidence rules: the stance vocabulary, the strength range, the mandatory
 * reason and the bound on free text.
 *
 * <p>These are the checks the freeze §6 requires before any hypothesis transition is
 * attempted. The HTTP layer repeats the structural half through bean validation; this
 * is the domain half, exercised without a container.
 */
class EvidenceRulesTest {

  @ParameterizedTest
  @ValueSource(strings = { "SUPPORTS", "CONTRADICTS", "NEUTRAL" })
  void acceptsExactlyTheThreeFrozenStances(String stance) {
    assertEquals(stance, EvidenceRules.requireStance(stance));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "SUPPORT",
      "supports",
      "OPPOSES",
      "DISPUTED",
      "",
      "SUPPORTS,NEUTRAL",
    }
  )
  void rejectsAnyStanceOutsideTheFrozenVocabulary(String stance) {
    var failure = assertThrows(ApplicationException.class, () ->
      EvidenceRules.requireStance(stance)
    );
    assertEquals(400, failure.status());
    assertEquals("INVALID_REQUEST", failure.code());
  }

  @Test
  void rejectsAMissingStance() {
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      EvidenceRules.requireStance(null)
    ).status());
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1, 50, 99, 100 })
  void acceptsStrengthInsideTheCursorRange(int strength) {
    assertEquals(strength, EvidenceRules.requireStrength(strength));
  }

  @ParameterizedTest
  @ValueSource(ints = { -1, -100, 101, 1000 })
  void rejectsStrengthOutsideTheRange(int strength) {
    var failure = assertThrows(ApplicationException.class, () ->
      EvidenceRules.requireStrength(strength)
    );
    assertEquals(400, failure.status());
    assertEquals("INVALID_REQUEST", failure.code());
  }

  @Test
  void rejectsAMissingStrengthRatherThanDefaultingIt() {
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      EvidenceRules.requireStrength(null)
    ).status());
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   ", "\n\t " })
  void aBlankReasonIsNotAReason(String reason) {
    var failure = assertThrows(ApplicationException.class, () ->
      EvidenceRules.requireReason(reason)
    );
    assertEquals(400, failure.status());
    assertEquals("INVALID_REQUEST", failure.code());
  }

  @Test
  void aReasonIsKeptButTrimmed() {
    assertEquals(
      "independent filing contradicts the reported volume",
      EvidenceRules.requireReason(
        "  independent filing contradicts the reported volume  "
      )
    );
  }

  @Test
  void aReasonLongerThanTheContractBoundIsRejected() {
    var tooLong = "x".repeat(EvidenceRules.MAX_TEXT + 1);
    assertEquals(400, assertThrows(ApplicationException.class, () ->
      EvidenceRules.requireReason(tooLong)
    ).status());
    assertEquals(
      EvidenceRules.MAX_TEXT,
      EvidenceRules.requireReason("x".repeat(EvidenceRules.MAX_TEXT)).length()
    );
  }
}
