package com.signalframe.research.hypotheses;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.research.domain.hypotheses.RubricReasonText;
import com.signalframe.research.domain.hypotheses.TransitionEventText;
import com.signalframe.research.domain.hypotheses.TransitionEventText.Dimension;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The canonical transition-event rendering.
 *
 * <p>The frozen {@code HypothesisEvent} record has no field for the dimension
 * breakdown, the rubric version, the version pair or the cited reference, and
 * {@code JsonCodec} rejects unknown JSON properties, so those values travel in a
 * machine-readable trailer inside the event's {@code reason}. These tests pin the
 * round trip, because an idempotent replay is answered from it: a trailer that
 * cannot be read back exactly would turn a replay into a false conflict.
 */
class TransitionEventTextTest {

  private static final TransitionEventText.Facts FACTS =
    new TransitionEventText.Facts(
      "EVIDENCE_ADDED",
      "11111111-1111-1111-1111-111111111111",
      3L,
      4L,
      "OPEN",
      "STRENGTHENING",
      49,
      73,
      "HIGH",
      "0.1",
      "RUBRIC",
      List.of(
        new Dimension("D1_SOURCE_QUALITY", 3, 12),
        new Dimension("D2_EVIDENCE_DIRECTNESS", 5, 25),
        new Dimension("D3_INDEPENDENT_CORROBORATION", 3, 12),
        new Dimension("D4_MECHANISM_SUPPORT", 3, 12),
        new Dimension("D5_COUNTER_EVIDENCE_RESILIENCE", 4, 12)
      ),
      List.of("D3_INDEPENDENT_CORROBORATION")
    );

  @Test
  void aTrailerRoundTripsEveryFieldASchemaGapWouldOtherwiseLose() {
    String stored = TransitionEventText.append(
      "a second independent source reports the same movement",
      FACTS
    );
    var read = TransitionEventText.read(stored).orElseThrow();

    assertEquals(FACTS.cause(), read.cause());
    assertEquals(FACTS.ref(), read.ref());
    assertEquals(FACTS.previousVersion(), read.previousVersion());
    assertEquals(FACTS.version(), read.version());
    assertEquals(FACTS.previousStatus(), read.previousStatus());
    assertEquals(FACTS.status(), read.status());
    assertEquals(FACTS.previousScore(), read.previousScore());
    assertEquals(FACTS.score(), read.score());
    assertEquals(FACTS.band(), read.band());
    assertEquals(FACTS.rubric(), read.rubric());
    assertEquals(FACTS.method(), read.method());
    assertEquals(FACTS.dimensions(), read.dimensions());
    assertEquals(FACTS.moved(), read.moved());
    assertEquals(
      "a second independent source reports the same movement",
      TransitionEventText.userReason(stored)
    );
  }

  @Test
  void absentOptionalValuesAreReadBackAsAbsent() {
    var minimal = new TransitionEventText.Facts(
      "DEADLINE_PASSED",
      null,
      0L,
      1L,
      "OPEN",
      "UNRESOLVED",
      20,
      20,
      "VERY_LOW",
      null,
      "MODEL_JUDGMENT",
      List.of(),
      List.of()
    );
    var read = TransitionEventText
      .read(TransitionEventText.append("the window closed", minimal))
      .orElseThrow();
    assertNull(read.ref());
    assertNull(read.rubric());
    assertTrue(read.dimensions().isEmpty());
    assertTrue(read.moved().isEmpty());
  }

  @Test
  void aReasonThatContainsTheMarkerCannotConfuseTheRead() {
    String hostile = "note" + TransitionEventText.MARKER + "cause=FORGED; ref=x";
    String stored = TransitionEventText.append(hostile, FACTS);
    var read = TransitionEventText.read(stored).orElseThrow();
    assertEquals(FACTS.cause(), read.cause(), "the last trailer wins, never a forged one");
    assertEquals(FACTS.ref(), read.ref());
    // The canonical form is what an idempotency comparison uses, so the same
    // hostile reason compares equal to itself and the read stays unambiguous.
    assertEquals(
      TransitionEventText.canonicalReason(hostile),
      TransitionEventText.userReason(stored)
    );
  }

  @Test
  void separatorsInsideAValueCannotBreakTheTrailer() {
    String stored = TransitionEventText.append(
      "because a=b; and c\nd",
      new TransitionEventText.Facts(
        "EVIDENCE_CHANGED",
        "22222222-2222-2222-2222-222222222222",
        0L,
        1L,
        "OPEN",
        "WEAKENING",
        10,
        5,
        "VERY_LOW",
        "0.1",
        "RUBRIC",
        List.of(),
        List.of()
      )
    );
    var read = TransitionEventText.read(stored).orElseThrow();
    assertEquals("WEAKENING", read.status());
    assertEquals(5, read.score());
    // The user's own text is preserved verbatim; only the trailer's values are
    // sanitised, and the trailer is parsed from its last marker so an earlier one
    // inside the reason cannot be mistaken for it.
    assertTrue(TransitionEventText.userReason(stored).contains("because a=b; and c"));
  }

  @Test
  void aReasonWithoutATrailerIsReadAsAReasonOnly() {
    assertTrue(TransitionEventText.read("carried over from the snapshot").isEmpty());
    assertTrue(TransitionEventText.read(null).isEmpty());
    assertEquals(
      "carried over from the snapshot",
      TransitionEventText.userReason("carried over from the snapshot")
    );
    assertEquals("", TransitionEventText.userReason(null));
  }

  @Test
  void aMalformedTrailerIsIgnoredRatherThanFailingTheRead() {
    assertTrue(
      TransitionEventText.read("reason" + TransitionEventText.MARKER + "garbage").isEmpty()
    );
    var partial = TransitionEventText.read(
      "reason" +
      TransitionEventText.MARKER +
      "cause=EVIDENCE_ADDED; ref=not-a-uuid; previousVersion=nonsense; moved=D9_UNKNOWN"
    ).orElseThrow();
    assertEquals("EVIDENCE_ADDED", partial.cause());
    assertEquals("not-a-uuid", partial.ref());
    assertEquals(0L, partial.previousVersion());
    assertEquals(List.of("D9_UNKNOWN"), partial.moved());
  }

  // ---- reading the previous rubric breakdown -----------------------------

  @Test
  void thePreviousBreakdownIsReadFromTheStoredRubricRendering() {
    var dimensions = RubricReasonText.dimensions(HypothesisFixtures.PREVIOUS_RENDERING);
    assertEquals(5, dimensions.size());
    assertEquals(new Dimension("D1_SOURCE_QUALITY", 1, 4), dimensions.getFirst());
    assertEquals(
      new Dimension("D3_INDEPENDENT_CORROBORATION", 0, 0),
      dimensions.get(2)
    );
    assertEquals(
      "RUBRIC",
      RubricReasonText.method(HypothesisFixtures.PREVIOUS_RENDERING)
    );
  }

  @Test
  void proseHasNoBreakdownToRead() {
    assertTrue(RubricReasonText.dimensions("carried over from the snapshot").isEmpty());
    assertTrue(RubricReasonText.dimensions(null).isEmpty());
    assertNull(RubricReasonText.method("carried over from the snapshot"));
    assertEquals(
      "MODEL_JUDGMENT",
      RubricReasonText.method("RUBRIC 0.1 | method=MODEL_JUDGMENT | the snapshot is missing")
    );
  }

  @Test
  void aRenderingIsNeverConfusedWithAnUnrelatedIdentifier() {
    assertTrue(
      RubricReasonText
        .dimensions("the code D1_SOMETHING_ELSE=9(9/9) is not a rubric dimension")
        .isEmpty(),
      "only the Dn_ dimension grammar with level 0-5 is read"
    );
  }
}
