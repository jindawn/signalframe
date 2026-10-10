package com.signalframe.analysis.application.steps;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.steps.ConfidenceInputs.Cap;
import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.SourceRef;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The deterministic confidence rubric (CONFIDENCE_MODEL_V0_1).
 *
 * <p>Covers the six worked examples as exact-value cases, the cap table, band
 * boundaries, Gate D recomputation, CF-06 fail-closed behaviour and a property
 * sweep over level combinations asserting the arithmetic invariants.
 */
class ConfidenceRubricTest {

  private final ConfidenceRubric rubric = ConfidenceRubric.deterministic();
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void workedExample1SingleReportNoCorroborationNoMechanism() {
    var score = rubric.score(at(1, 2, 0, 0, 2));
    assertEquals(1, score.dimensions().get(0).level());
    assertEquals(2, score.dimensions().get(1).level());
    assertEquals(0, score.dimensions().get(2).level());
    assertEquals(0, score.dimensions().get(3).level());
    assertEquals(2, score.dimensions().get(4).level());
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
      assertEquals(
        dimension.weight() * dimension.level() / 5,
        dimension.points(),
        dimension.id()
      );
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
    assertEquals(ConfidenceBand.VERY_LOW, ConfidenceBand.of(0));
    assertEquals(ConfidenceBand.VERY_LOW, ConfidenceBand.of(29));
    assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(30));
    assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(49));
    assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.of(50));
    assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.of(69));
    assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(70));
    assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(84));
    assertEquals(ConfidenceBand.VERY_HIGH, ConfidenceBand.of(85));
    assertEquals(ConfidenceBand.VERY_HIGH, ConfidenceBand.of(100));
    assertThrows(IllegalArgumentException.class, () -> ConfidenceBand.of(101));
    assertThrows(IllegalArgumentException.class, () -> ConfidenceBand.of(-1));
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
    assertEquals(ConfidenceScore.ConfidenceMethod.MODEL_JUDGMENT, score.method());
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
                ConfidenceBand.of(score.score()),
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
    return score.dimensions().stream().mapToInt(d -> d.points()).sum();
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
        d2 >= 5 ? "价格下降 30%" : "A reported claim",
        "Reported source claim.",
        List.of(new SourceRef(UUID.randomUUID(), "A reported claim", 0, 17)),
        disputed ? ProofStatus.DISPUTED : ProofStatus.REPORTED
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
          "UNKNOWN",
          "lower",
          Direction.DOWN,
          "30%",
          "changes the cost base of every buyer",
          "unit price fell",
          "the fact states a 30% decline",
          List.of(fact.id()),
          List.of(),
          40
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

    private List<Mechanism> mechanisms(int level) {
      if (level == 0) return List.of();
      var ref = UUID.randomUUID();
      return switch (level) {
        case 1 -> List.of(
          new Mechanism(
            UUID.randomUUID(),
            "unit price",
            "demand",
            SupportLevel.SPECULATIVE,
            "lower price may raise demand",
            "offered for testing",
            List.of(),
            List.of(),
            List.of(),
            "a downstream volume disclosure",
            20
          )
        );
        case 2 -> List.of(
          new Mechanism(
            UUID.randomUUID(),
            "unit price",
            "demand",
            SupportLevel.PLAUSIBLE,
            "price elasticity",
            "consistent but undocumented",
            List.of("demand is price elastic"),
            List.of(),
            List.of(),
            "UNKNOWN",
            20
          )
        );
        case 3 -> List.of(
          new Mechanism(
            UUID.randomUUID(),
            "unit price",
            "demand",
            SupportLevel.PLAUSIBLE,
            "price elasticity",
            "consistent but undocumented",
            List.of("demand is price elastic"),
            List.of(ref),
            List.of(),
            "UNKNOWN",
            20
          )
        );
        default -> List.of(
          new Mechanism(
            UUID.randomUUID(),
            "unit price",
            "demand",
            SupportLevel.SUPPORTED,
            "the source documents the volume response",
            "documented at both ends",
            List.of(),
            List.of(ref, UUID.randomUUID()),
            List.of(),
            "UNKNOWN",
            40
          )
        );
      };
    }

    private List<CounterArgument> counters(int level) {
      if (level == 0) return List.of();
      var ref = UUID.randomUUID();
      boolean withRef = level >= 3;
      return List.of(
        new CounterArgument(
          UUID.randomUUID(),
          "the disclosure is self-selected",
          "a definitional change could explain the move",
          UUID.randomUUID(),
          withRef ? List.of(ref) : List.of(),
          List.of(),
          20
        )
      );
    }

    private List<FalsificationCondition> conditions(int level) {
      if (level <= 1) return List.of();
      boolean observable = level >= 4;
      boolean timeBounded = level >= 5;
      return List.of(
        new FalsificationCondition(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "the next disclosure",
          observable ? "the reported volume" : "",
          observable
            ? (timeBounded ? "below the pre-registered threshold by 2026-06-30" : "below the pre-registered threshold")
            : "",
          "if the disclosure contradicts the mechanism, the hypothesis is rejected"
        )
      );
    }

    private List<Signal> signals(int level) {
      if (level < 5) return List.of();
      return List.of(
        new Signal(
          UUID.randomUUID(),
          "a second buyer reports the same price",
          "procurement records",
          UUID.randomUUID(),
          "within two quarters",
          SignalStatus.NOT_OBSERVED
        )
      );
    }

    private List<PlanItem> planItems(int d2) {
      if (!plan) return List.of();
      return List.of(
        new PlanItem(
          UUID.randomUUID(),
          "is the volume response visible?",
          "the company's next disclosure",
          "volume rises with the documented elasticity",
          "volume falls or is unchanged",
          PlanPriority.HIGH,
          NOW.plusSeconds(86400L * 90),
          UUID.randomUUID(),
          null,
          "verify the mechanism",
          "decides whether the mechanism holds",
          List.of(),
          30
        )
      );
    }

    private ConfidenceInputs assemble(
      SourceAssessment source,
      int methodCount,
      int contextual,
      List<Fact> facts,
      List<Variable> variables,
      List<Mechanism> mechanisms,
      List<CounterArgument> counters,
      List<FalsificationCondition> conditions,
      List<Signal> signals,
      List<PlanItem> plan,
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
          SourceType.UNKNOWN,
          "UNKNOWN",
          "UNKNOWN",
          SourceClass.UNKNOWN,
          Independence.SINGLE_SOURCE,
          Completeness.PARTIAL,
          List.of()
        );
        case 1 -> new SourceAssessment(
          SourceType.NEWS_REPORT,
          "example.com",
          "UNKNOWN",
          SourceClass.SECONDARY,
          Independence.SINGLE_SOURCE,
          Completeness.PARTIAL,
          List.of()
        );
        case 2 -> new SourceAssessment(
          SourceType.NEWS_REPORT,
          "example.com",
          "2026-01-01T00:00:00Z",
          SourceClass.SECONDARY,
          Independence.SINGLE_SOURCE,
          Completeness.COMPLETE,
          List.of()
        );
        default -> new SourceAssessment(
          SourceType.PRIMARY_DOCUMENT,
          "example.com",
          "2026-01-01T00:00:00Z",
          SourceClass.PRIMARY,
          level >= 4
            ? Independence.INDEPENDENT_SET
            : Independence.SINGLE_SOURCE,
          Completeness.COMPLETE,
          List.of()
        );
      };
    }
  }
}
