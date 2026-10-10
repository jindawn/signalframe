package com.signalframe.shared.confidence;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import com.signalframe.shared.confidence.ConfidenceInputs.Cap;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The deterministic confidence rubric (CONFIDENCE_MODEL_V0_1).
 *
 * <p>Covers the six worked examples as exact-value cases, the cap table, band
 * boundaries, Gate D recomputation, CF-06 fail-closed behaviour and a property
 * sweep over level combinations asserting the arithmetic invariants.
 *
 * <p>The fixtures are built from the generated contract records (SCH-01…SCH-13),
 * which is also what makes the rubric usable by the research module: the inputs are
 * the snapshot's own vocabulary, not an analysis-private copy of it.
 */
class ConfidenceRubricTest {

  private final ConfidenceRubric rubric = ConfidenceRubric.deterministic();
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void workedExample1SingleReportNoCorroborationNoMechanism() {
    var score = rubric.score(at(1, 2, 0, 0, 2));
    assertEquals(1, score.dimensions().get(0).level().intValue());
    assertEquals(2, score.dimensions().get(1).level().intValue());
    assertEquals(0, score.dimensions().get(2).level().intValue());
    assertEquals(0, score.dimensions().get(3).level().intValue());
    assertEquals(2, score.dimensions().get(4).level().intValue());
    assertEquals(20, score.score());
    assertEquals(ConfidenceBand.VERY_LOW, score.band());
    // CAP-A is binding but its ceiling (49) is above the raw total (20), so the
    // score is unchanged and `capped` stays false.
    assertFalse(score.capped());
    assertTrue(capIds(score).contains("CAP-A"));
  }

  @Test
  void workedExample2DefensibleReadingStillMissingCorroborationDepth() {
    var score = rubric.score(at(2, 4, 3, 3, 3));
    assertEquals(8 + 20 + 12 + 12 + 9, score.score());
    assertEquals(ConfidenceBand.MEDIUM, score.band());
    assertFalse(capIds(score).contains("CAP-A"));
    assertFalse(capIds(score).contains("CAP-C"));
    assertFalse(capIds(score).contains("CAP-D"));
    assertTrue(capIds(score).contains("CAP-F"));
  }

  @Test
  void workedExample3PrimarySourceWithCorroborationAndMechanism() {
    var score = rubric.score(at(3, 5, 4, 4, 4));
    assertEquals(12 + 25 + 16 + 16 + 12, score.score());
    assertEquals(ConfidenceBand.HIGH, score.band());
    assertFalse(score.capped());
  }

  @Test
  void workedExample4TopLevelDimensions() {
    var score = rubric.score(at(4, 5, 4, 5, 5));
    assertEquals(16 + 25 + 16 + 20 + 15, score.score());
    assertEquals(ConfidenceBand.VERY_HIGH, score.band());
  }

  @Test
  void workedExample5CeilingRuleDoesItsJob() {
    var score = rubric.score(at(4, 5, 0, 4, 2));
    assertEquals(63, rawPoints(score));
    assertEquals(49, score.score());
    assertEquals(ConfidenceBand.LOW, score.band());
    assertTrue(score.capped());
  }

  @Test
  void workedExample6StackedCaps() {
    var score = rubric.score(at(1, 1, 0, 1, 1));
    assertEquals(16, score.score());
    assertEquals(ConfidenceBand.VERY_LOW, score.band());
    assertEquals(
      Set.of("CAP-A", "CAP-D", "CAP-E", "CAP-F"),
      Set.copyOf(capIds(score))
    );
  }

  @Test
  void everyWeightedDimensionIsExactWithoutRounding() {
    var score = rubric.score(at(3, 5, 4, 4, 4));
    for (var dimension : score.dimensions()) {
      int weight = RubricDimensions.weightOf(dimension.dimension());
      int level = dimension.level().intValue();
      int points = dimension.points().intValue();
      assertEquals(weight * level / 5, points, dimension.dimension());
      assertTrue(weight > 0, "every stored dimension belongs to the profile");
    }
    assertEquals(
      score.score(),
      Math.min(rawPoints(score), score.caps().isEmpty() ? 100 : minCap(score.caps(), 100)),
      "score must be min(raw, binding caps)"
    );
  }

  @Test
  void capBAppliesWhenTheMainHypothesisRestsOnADisputedFact() {
    var inputs = input(3, 5, 3, 3, 5).disputed(true).build();
    var score = rubric.score(inputs);
    assertTrue(capIds(score).contains("CAP-B"));
    assertTrue(score.score() <= 69);
  }

  @Test
  void capCAppliesWithoutAUsableVerificationPlanItem() {
    var inputs = input(3, 5, 3, 3, 4).noPlan().build();
    var score = rubric.score(inputs);
    assertTrue(capIds(score).contains("CAP-C"));
    assertTrue(score.score() <= 59);
  }

  @Test
  void capsNeverRaiseAScoreAndTheFloorIsZero() {
    var score = rubric.score(at(0, 0, 0, 0, 0));
    assertEquals(0, score.score());
    assertEquals(ConfidenceBand.VERY_LOW, score.band());
  }

  @Test
  void bandBoundariesAreContiguousAndCover0To100() {
    assertEquals(ConfidenceBand.VERY_LOW, ConfidenceBands.of(0));
    assertEquals(ConfidenceBand.VERY_LOW, ConfidenceBands.of(29));
    assertEquals(ConfidenceBand.LOW, ConfidenceBands.of(30));
    assertEquals(ConfidenceBand.LOW, ConfidenceBands.of(49));
    assertEquals(ConfidenceBand.MEDIUM, ConfidenceBands.of(50));
    assertEquals(ConfidenceBand.MEDIUM, ConfidenceBands.of(69));
    assertEquals(ConfidenceBand.HIGH, ConfidenceBands.of(70));
    assertEquals(ConfidenceBand.HIGH, ConfidenceBands.of(84));
    assertEquals(ConfidenceBand.VERY_HIGH, ConfidenceBands.of(85));
    assertEquals(ConfidenceBand.VERY_HIGH, ConfidenceBands.of(100));
    assertThrows(IllegalArgumentException.class, () -> ConfidenceBands.of(101));
    assertThrows(IllegalArgumentException.class, () -> ConfidenceBands.of(-1));
  }

  @Test
  void gateDRecomputationReproducesScoreBandAndPointsExactly() {
    var inputs = at(3, 5, 3, 3, 4);
    var stored = rubric.score(inputs);
    assertTrue(rubric.matchesStored(stored, inputs));
    var tampered = new ConfidenceScore(
      stored.score() + 1,
      stored.band(),
      stored.rubricVersion(),
      stored.dimensions(),
      stored.reason(),
      stored.capped(),
      stored.method(),
      null,
      stored.caps()
    );
    assertFalse(rubric.matchesStored(tampered, inputs));
  }

  @Test
  void failClosedWhenNoSourceAssessmentExists() {
    var inputs = new ConfidenceInputs(
      null,
      0,
      0,
      0,
      0,
      0,
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      false,
      false,
      false,
      0
    );
    var score = rubric.score(inputs);
    assertEquals(ConfidenceMethod.MODEL_JUDGMENT, score.method());
    assertTrue(score.dimensions().isEmpty());
    assertFalse(rubric.matchesStored(score, inputs));
  }

  @Test
  void propertySweepKeepsTheArithmeticInvariants() {
    for (int d1 = 0; d1 <= 5; d1++) {
      for (int d2 = 0; d2 <= 5; d2++) {
        for (int d3 = 0; d3 <= 5; d3++) {
          for (int d4 = 0; d4 <= 5; d4++) {
            for (int d5 = 0; d5 <= 5; d5++) {
              var inputs = at(d1, d2, d3, d4, d5);
              var score = rubric.score(inputs);
              int raw = rawPoints(score);
              assertTrue(
                score.score() <= raw,
                "caps never raise a score: " + score
              );
              assertEquals(
                ConfidenceBands.of(score.score()),
                score.band(),
                "band comes from the capped score"
              );
              assertEquals(
                score.score() < raw,
                score.capped(),
                "capped must mean a ceiling lowered the raw total"
              );
              assertTrue(
                rubric.matchesStored(score, inputs),
                "recomputation must be exact: " + score
              );
              assertTrue(
                score.score() >= 0 && score.score() <= 100
              );
            }
          }
        }
      }
    }
  }

  // ---- fixture DSL --------------------------------------------------------

  private static List<String> capIds(ConfidenceScore score) {
    return score.caps().stream().map(Cap::id).toList();
  }

  private static int rawPoints(ConfidenceScore score) {
    return score.dimensions().stream().mapToInt(d -> d.points().intValue()).sum();
  }

  private static int minCap(List<Cap> caps, int fallback) {
    return caps.isEmpty()
      ? fallback
      : caps.stream().mapToInt(Cap::ceiling).min().orElse(fallback);
  }

  /**
   * Builds inputs whose documented conditions produce the requested level vector
   * where the level functions allow it (D1 = 4 and D3 = 5 cannot coexist, and a
   * documented mechanism raises D2 to at least 4).
   */
  static ConfidenceInputs at(int d1, int d2, int d3, int d4, int d5) {
    return input(d1, d2, d3, d4, d5).build();
  }

  static InputBuilder input(int d1, int d2, int d3, int d4, int d5) {
    return new InputBuilder(d1, d2, d3, d4, d5);
  }

  static final class InputBuilder {

    private final int d1;
    private final int d2;
    private final int d3;
    private final int d4;
    private final int d5;
    private boolean disputed;
    private boolean plan = true;

    InputBuilder(int d1, int d2, int d3, int d4, int d5) {
      this.d1 = d1;
      this.d2 = d2;
      this.d3 = d3;
      this.d4 = d4;
      this.d5 = d5;
    }

    InputBuilder disputed(boolean value) {
      this.disputed = value;
      return this;
    }

    InputBuilder noPlan() {
      this.plan = false;
      return this;
    }

    ConfidenceInputs build() {
      int methodCount = Math.max(d1 >= 5 ? 2 : (d1 == 4 ? 1 : 0), d3 == 5 ? 2 : 0);
      int contextual = d1 >= 4 ? 1 : 0;
      var source = source(d1);
      var fact = new Fact(
        UUID.randomUUID(),
        ClaimType.FACT,
        d2 >= 5 ? "价格下降 30%" : "A reported claim",
        "Reported source claim.",
        40,
        List.of(new SourceRef(UUID.randomUUID(), "A reported claim", 0, 17)),
        disputed ? "DISPUTED" : "REPORTED"
      );
      var facts = d2 == 0 ? List.<Fact>of() : List.of(fact);
      if (d2 == 0) {
        // No resolved fact refs anywhere.
        return assemble(
          source,
          methodCount,
          contextual,
          List.of(),
          List.of(),
          mechanisms(d4),
          counters(d5),
          conditions(d5),
          signals(d4),
          planItems(d2),
          d2,
          d5
        );
      }
      var variables = List.of(
        new Variable(
          UUID.randomUUID(),
          "unit price",
          "DOWN",
          ClaimType.INFERENCE,
          "unit price fell",
          "the fact states a 30% decline",
          40,
          List.of(),
          "UNKNOWN",
          "lower",
          "30%",
          "changes the cost base of every buyer",
          List.of(fact.id())
        )
      );
      return assemble(
        source,
        methodCount,
        contextual,
        facts,
        variables,
        mechanisms(d4),
        counters(d5),
        conditions(d5),
        signals(d4),
        planItems(d2),
        d2,
        d5
      );
    }

    private List<CausalLink> mechanisms(int level) {
      if (level == 0) return List.of();
      var ref = UUID.randomUUID();
      return switch (level) {
        case 1 -> List.of(
          new CausalLink(
            UUID.randomUUID(),
            "unit price",
            "demand",
            ClaimType.INFERENCE,
            "lower price may raise demand",
            "offered for testing",
            20,
            List.of(),
            "SPECULATIVE",
            List.of()
          )
        );
        case 2 -> List.of(
          new CausalLink(
            UUID.randomUUID(),
            "unit price",
            "demand",
            ClaimType.INFERENCE,
            "price elasticity",
            "consistent but undocumented",
            20,
            List.of(),
            "PLAUSIBLE",
            List.of()
          )
        );
        case 3 -> List.of(
          new CausalLink(
            UUID.randomUUID(),
            "unit price",
            "demand",
            ClaimType.INFERENCE,
            "price elasticity",
            "consistent but undocumented",
            20,
            List.of(),
            "PLAUSIBLE",
            List.of(ref)
          )
        );
        default -> List.of(
          new CausalLink(
            UUID.randomUUID(),
            "unit price",
            "demand",
            ClaimType.INFERENCE,
            "the source documents the volume response",
            "documented at both ends",
            40,
            List.of(),
            "SUPPORTED",
            List.of(ref, UUID.randomUUID())
          )
        );
      };
    }

    private List<Statement> counters(int level) {
      if (level == 0) return List.of();
      boolean withRef = level >= 3;
      return List.of(
        new Statement(
          ClaimType.INFERENCE,
          "the disclosure is self-selected",
          "a definitional change could explain the move",
          20,
          List.of(),
          withRef ? List.of(UUID.randomUUID()) : List.of(),
          List.of(),
          UUID.randomUUID(),
          null
        )
      );
    }

    /**
     * SCH-06 nests a falsification condition as a `Statement`, so STG-12's three
     * mandatory parts travel in the reasoning trailer — the exact convention the
     * rubric reads back for D5.
     */
    private List<Statement> conditions(int level) {
      if (level <= 1) return List.of();
      boolean observable = level >= 4;
      boolean timeBounded = level >= 5;
      var reasoning = FalsificationConditionText.append(
        "if the disclosure contradicts the mechanism, the hypothesis is rejected",
        observable ? "the next disclosure" : "",
        observable ? "the reported volume" : "",
        observable
          ? (timeBounded
            ? "below the pre-registered threshold by 2026-06-30"
            : "below the pre-registered threshold")
          : ""
      );
      return List.of(
        new Statement(
          ClaimType.INFERENCE,
          "if the disclosure contradicts the mechanism, the hypothesis is rejected",
          reasoning,
          0,
          List.of(),
          List.of(),
          List.of(),
          null,
          null
        )
      );
    }

    private List<CorroboratingSignal> signals(int level) {
      if (level < 5) return List.of();
      return List.of(
        new CorroboratingSignal(
          ClaimType.INFERENCE,
          "a second buyer reports the same price",
          "Expected observable, not yet observed.",
          0,
          List.of(),
          UUID.randomUUID(),
          "procurement records",
          "within two quarters",
          "NOT_OBSERVED"
        )
      );
    }

    private List<Indicator> planItems(int d2) {
      if (!plan) return List.of();
      return List.of(
        new Indicator(
          UUID.randomUUID(),
          null,
          "is the volume response visible?",
          "the company's next disclosure",
          "HIGH",
          ClaimType.INFERENCE,
          "Supporting: volume rises | Contradicting: volume falls",
          "decides whether the mechanism holds",
          30,
          List.of(),
          "the company's next disclosure",
          "volume rises with the documented elasticity",
          "volume falls or is unchanged",
          "HIGH",
          NOW.plusSeconds(86400L * 90),
          UUID.randomUUID()
        )
      );
    }

    private ConfidenceInputs assemble(
      SourceAssessment source,
      int methodCount,
      int contextual,
      List<Fact> facts,
      List<Variable> variables,
      List<CausalLink> mechanisms,
      List<Statement> counters,
      List<Statement> conditions,
      List<CorroboratingSignal> signals,
      List<Indicator> plan,
      int d2,
      int d5
    ) {
      int independent = d3 >= 2 ? (d3 >= 4 ? 2 : 1) : 0;
      int confirming = d3 >= 3 ? (d3 >= 4 ? 2 : 1) : 0;
      int nonIndependent = d3 == 1 ? 1 : 0;
      return new ConfidenceInputs(
        source,
        independent,
        confirming,
        methodCount,
        nonIndependent,
        contextual,
        facts,
        variables,
        mechanisms,
        counters,
        conditions,
        signals,
        plan,
        d2 == 3 ? List.of("demand is price elastic") : List.of(),
        d2 >= 5,
        d2 == 1,
        disputed,
        d5 >= 5 ? 1 : 0
      );
    }

    private SourceAssessment source(int level) {
      return switch (level) {
        case 0 -> new SourceAssessment(
          "UNKNOWN",
          "UNKNOWN",
          null,
          "UNKNOWN",
          "SINGLE_SOURCE",
          null,
          "PARTIAL",
          List.of()
        );
        case 1 -> new SourceAssessment(
          "NEWS_REPORT",
          "example.com",
          null,
          "SECONDARY",
          "SINGLE_SOURCE",
          null,
          "PARTIAL",
          List.of()
        );
        case 2 -> new SourceAssessment(
          "NEWS_REPORT",
          "example.com",
          NOW,
          "SECONDARY",
          "SINGLE_SOURCE",
          null,
          "COMPLETE",
          List.of()
        );
        default -> new SourceAssessment(
          "PRIMARY_DOCUMENT",
          "example.com",
          NOW,
          "PRIMARY",
          level >= 4 ? "INDEPENDENT_SET" : "SINGLE_SOURCE",
          null,
          "COMPLETE",
          List.of()
        );
      };
    }
  }
}
