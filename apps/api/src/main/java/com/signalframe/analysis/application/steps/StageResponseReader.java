package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.SourceRef;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Parses and validates one stage's typed output.
 *
 * <p>Two shapes are accepted, and they are treated differently on purpose:
 *
 * <ol>
 *   <li><b>Stage envelope</b> — the narrowed object the stage prompt requires
 *       (for example {@code {"facts":[...]}}). It carries the protocol's own
 *       fields, so validation is strict: a missing fact reference, a dangling ref,
 *       an unobservable falsification condition or a prediction that is not time
 *       bounded is an invalid response that gets one bounded repair attempt and
 *       then fails the job (protocol PR-09/PR-18).
 *   <li><b>Snapshot envelope</b> — a complete {@code AnalysisResult}, which is how
 *       the offline reference fixture answers every purpose. The v0.1 contract
 *       cannot express fact refs, support levels or predictions, so refs are
 *       derived by span overlap and anything the protocol cannot accept is
 *       dropped with an explicit UNKNOWN item instead of being silently kept or
 *       fabricated (EP-01/PR-05/PR-11).
 * </ol>
 *
 * <p>An unknown JSON field, an unsupported vocabulary value or a decoder failure
 * is a controlled {@link StageOutputException}; nothing is swallowed.
 */
@Component
public class StageResponseReader {

  /** Parsed artifact list plus the UNKNOWN items and demo flag the payload declared. */
  public record Artifacts<T>(
    List<T> value,
    List<Unknown> unknowns,
    boolean demo
  ) {
    public static <T> Artifacts<T> of(List<T> value) {
      return new Artifacts<>(value, List.of(), false);
    }
  }

  private final JsonCodec json;

  public StageResponseReader(JsonCodec json) {
    this.json = json;
  }

  // ---- stage entry points -------------------------------------------------

  public Artifacts<Fact> facts(String raw, PipelineState state) {
    var envelope = envelope(raw, FactsEnvelope.class);
    if (envelope.present()) return factsEnvelope(envelope.get(), state);
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var facts = snapshot.get().facts();
      return new Artifacts<>(
        validateFacts(facts, state),
        snapshot.get().unknowns(),
        snapshot.get().demo()
      );
    }
    throw wrongShape(raw, "facts");
  }

  public Artifacts<Variable> variables(String raw, PipelineState state) {
    var envelope = envelope(raw, VariablesEnvelope.class);
    if (envelope.present()) return variablesEnvelope(envelope.get(), state);
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var values = new ArrayList<Variable>();
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      for (var v : snapshot.get().variables(state.facts())) {
        if (v.direction() != Direction.UNKNOWN && v.factRefs().isEmpty()) {
          unknown.add(
            demoted(
              "variables",
              v.name(),
              "unsupported inference: no fact inside this snapshot supports the stated direction (EP-01)"
            )
          );
          continue;
        }
        values.add(v);
      }
      return new Artifacts<>(List.copyOf(values), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "variables");
  }

  public Artifacts<Mechanism> mechanisms(String raw, PipelineState state) {
    var envelope = envelope(raw, MechanismsEnvelope.class);
    if (envelope.present()) return mechanismsEnvelope(envelope.get(), state);
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      return new Artifacts<>(
        strictMechanisms(snapshot.get().mechanisms(state.facts()), state),
        snapshot.get().unknowns(),
        snapshot.get().demo()
      );
    }
    throw wrongShape(raw, "mechanisms");
  }

  public Artifacts<Stakeholder> stakeholders(String raw, PipelineState state) {
    var envelope = envelope(raw, StakeholdersEnvelope.class);
    if (envelope.present()) {
      var unknown = new ArrayList<Unknown>();
      var values = new ArrayList<Stakeholder>();
      for (var s : orEmpty(envelope.get().stakeholders())) {
        requireText(s.statement(), "stakeholders[].statement");
        requireText(s.reasoning(), "stakeholders[].reasoning");
        requireText(s.stakeholder(), "stakeholders[].stakeholder");
        requireRefs(s.factRefs(), state, "stakeholders[].factRefs");
        requireSourceRefs(s.sourceRefs(), state, "stakeholders[].sourceRefs");
        if (
          s.direction() != StakeholderDirection.UNKNOWN &&
          s.factRefs().isEmpty()
        ) {
          throw StageOutputException.schema(
            "stakeholders[]: a non-UNKNOWN direction requires a fact reference (STG-06)"
          );
        }
        values.add(s);
      }
      unknown.addAll(orEmpty(envelope.get().unknowns()));
      return new Artifacts<>(List.copyOf(values), unknown, false);
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var values = new ArrayList<Stakeholder>();
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      for (var s : snapshot.get().stakeholders(state.facts())) {
        if (
          s.direction() != StakeholderDirection.UNKNOWN &&
          s.factRefs().isEmpty()
        ) {
          unknown.add(
            demoted(
              "stakeholders",
              s.statement(),
              "a non-UNKNOWN exposure direction has no resolvable fact reference in this snapshot"
            )
          );
          continue;
        }
        values.add(s);
      }
      return new Artifacts<>(List.copyOf(values), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "stakeholders");
  }

  public Artifacts<Effect> firstOrderEffects(String raw, PipelineState state) {
    return effects(raw, state, EffectsOrder.FIRST);
  }

  public Artifacts<Effect> secondOrderEffects(String raw, PipelineState state) {
    return effects(raw, state, EffectsOrder.SECOND);
  }

  public Artifacts<Hypothesis> hypotheses(String raw, PipelineState state) {
    var envelope = envelope(raw, HypothesesEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<Hypothesis>();
      for (var h : orEmpty(envelope.get().hypotheses())) {
        requireText(h.title(), "hypotheses[].title");
        requireText(h.statement(), "hypotheses[].statement");
        requireText(h.reasoning(), "hypotheses[].reasoning");
        if (h.supportingFactRefs().isEmpty()) throw StageOutputException.schema(
          "hypotheses[]: a hypothesis needs at least one supporting fact reference (EP-05)"
        );
        requireRefs(
          h.supportingFactRefs(),
          state,
          "hypotheses[].supportingFactRefs"
        );
        if (
          h.contradictingEvidenceRefs().isEmpty() && h.assumptions().isEmpty()
        ) throw StageOutputException.schema(
          "hypotheses[]: an empty contradicting list requires a written assumption note (STG-09.3)"
        );
        values.add(h);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var values = new ArrayList<Hypothesis>();
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      for (var h : snapshot.get().hypotheses(state.facts())) {
        if (h.supportingFactRefs().isEmpty()) {
          unknown.add(
            demoted(
              "hypotheses",
              h.title(),
              "no fact inside this snapshot supports the hypothesis (EP-01)"
            )
          );
          continue;
        }
        values.add(h);
      }
      return new Artifacts<>(List.copyOf(values), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "hypotheses");
  }

  public Artifacts<Alternative> alternativeExplanations(
    String raw,
    PipelineState state
  ) {
    var envelope = envelope(raw, AlternativesEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<Alternative>();
      var seen = new HashSet<String>();
      for (var a : orEmpty(envelope.get().alternatives())) {
        requireText(a.statement(), "alternatives[].statement");
        requireText(a.reasoning(), "alternatives[].reasoning");
        requireHypothesisRef(a.rivalsHypothesisRef(), state, "alternatives[]");
        if (a.factRefs().isEmpty()) throw StageOutputException.schema(
          "alternatives[]: an alternative must state the facts it explains (STG-10)"
        );
        requireRefs(a.factRefs(), state, "alternatives[].factRefs");
        requireSourceRefs(a.sourceRefs(), state, "alternatives[].sourceRefs");
        var rival = hypothesis(state, a.rivalsHypothesisRef());
        if (
          rival != null &&
          rival.statement().equalsIgnoreCase(a.statement().trim())
        ) throw StageOutputException.schema(
          "alternatives[]: a stylistic restatement of the hypothesis is not an alternative (STG-10)"
        );
        if (!seen.add(a.statement().trim())) throw StageOutputException.schema(
          "alternatives[]: duplicate alternative statement"
        );
        values.add(a);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var values = new ArrayList<Alternative>();
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      for (var a : snapshot.get().alternatives(state.facts(), state.hypotheses())) {
        if (a.factRefs().isEmpty()) {
          unknown.add(
            demoted(
              "alternatives",
              a.statement(),
              "the rival explanation rests on no resolvable fact in this snapshot"
            )
          );
          continue;
        }
        values.add(a);
      }
      if (snapshot.get().rivalPairingAmbiguous()) unknown.add(
        new Unknown(
          UUID.randomUUID(),
          "Which hypothesis each alternative rivals is not declared by the v0.1 snapshot (SCH-03 pending)",
          "the snapshot carries more than one hypothesis and no rivalsHypothesisRef field",
          "the v0.2 snapshot schema with alternative refs"
        )
      );
      return new Artifacts<>(List.copyOf(values), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "alternatives");
  }

  public Artifacts<CounterArgument> counterArguments(
    String raw,
    PipelineState state
  ) {
    var envelope = envelope(raw, CounterArgumentsEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<CounterArgument>();
      for (var c : orEmpty(envelope.get().counterArguments())) {
        requireText(c.statement(), "counterArguments[].statement");
        requireText(c.reasoning(), "counterArguments[].reasoning");
        requireHypothesisRef(c.targetHypothesisRef(), state, "counterArguments[]");
        requireRefs(c.factRefs(), state, "counterArguments[].factRefs");
        requireSourceRefs(c.sourceRefs(), state, "counterArguments[].sourceRefs");
        values.add(c);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var values = new ArrayList<CounterArgument>();
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      if (state.hypotheses().isEmpty() && !snapshot.get().result().counterArguments().isEmpty()) {
        unknown.add(
          demoted(
            "counterArguments",
            "counter-arguments with no hypothesis to target",
            "the snapshot has no hypothesis, so an objection has no target (STG-11)"
          )
        );
      } else {
        values.addAll(
          snapshot.get().counterArguments(state.facts(), state.hypotheses())
        );
      }
      return new Artifacts<>(List.copyOf(values), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "counterArguments");
  }

  public Artifacts<FalsificationCondition> falsificationConditions(
    String raw,
    PipelineState state
  ) {
    var envelope = envelope(raw, FalsificationEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<FalsificationCondition>();
      for (var c : orEmpty(envelope.get().falsificationConditions())) {
        requireHypothesisRef(c.hypothesisRef(), state, "falsificationConditions[]");
        requireText(c.observable(), "falsificationConditions[].observable");
        requireText(c.comparison(), "falsificationConditions[].comparison");
        requireText(c.decisionBoundary(), "falsificationConditions[].decisionBoundary");
        if (!decidable(c.decisionBoundary())) throw StageOutputException.schema(
          "falsificationConditions[]: the decision boundary must be observable and decidable by a third party (STG-12)"
        );
        values.add(c);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var values = snapshot.get().falsificationConditions(state.hypotheses());
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      if (!values.isEmpty()) unknown.add(
        new Unknown(
          UUID.randomUUID(),
          "The v0.1 snapshot states falsification conditions without a comparison or decision boundary (SCH-06 pending)",
          "the snapshot schema has only a free-text statement per condition",
          "pre-registered conditions carrying observable, comparison and decision boundary"
        )
      );
      return new Artifacts<>(values, unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "falsificationConditions");
  }

  public Artifacts<Signal> corroboratingSignals(
    String raw,
    PipelineState state
  ) {
    var envelope = envelope(raw, SignalsEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<Signal>();
      for (var s : orEmpty(envelope.get().signals())) {
        requireText(s.signal(), "signals[].signal");
        requireText(s.where(), "signals[].where");
        requireText(s.window(), "signals[].window");
        requireHypothesisRef(s.hypothesisRef(), state, "signals[]");
        values.add(s);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      return new Artifacts<>(
        snapshot.get().signals(state.hypotheses()),
        snapshot.get().unknowns(),
        snapshot.get().demo()
      );
    }
    throw wrongShape(raw, "signals");
  }

  public Artifacts<Prediction> predictions(String raw, PipelineState state) {
    var envelope = envelope(raw, PredictionsEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<Prediction>();
      for (var p : orEmpty(envelope.get().predictions())) {
        requireText(p.statement(), "predictions[].statement");
        requireText(p.observable(), "predictions[].observable");
        requireText(p.verificationCriteria(), "predictions[].verificationCriteria");
        requireText(p.whereToCheck(), "predictions[].whereToCheck");
        requireHypothesisRef(p.hypothesisRef(), state, "predictions[]");
        if (p.expectedBy() == null) throw StageOutputException.schema(
          "predictions[]: expectedBy is required; a prediction must be time bounded (STG-14)"
        );
        if (!p.expectedBy().isAfter(state.snapshotCreatedAt())) throw StageOutputException.schema(
          "predictions[]: expectedBy must be in the future relative to snapshot creation (STG-14.1)"
        );
        if (
          p.verificationCriteria().equalsIgnoreCase(p.statement().trim())
        ) throw StageOutputException.schema(
          "predictions[]: verificationCriteria must separate confirmation, partial confirmation and rejection (STG-14.2)"
        );
        values.add(p);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      unknown.add(
        new Unknown(
          UUID.randomUUID(),
          "Predictions are not part of the v0.1 snapshot schema (SCH-07 pending)",
          "the contract cannot carry a PREDICTION artifact, so no dated prediction can be persisted",
          "the v0.2 snapshot schema with predictions"
        )
      );
      return new Artifacts<>(List.of(), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "predictions");
  }

  public Artifacts<PlanItem> verificationPlan(
    String raw,
    PipelineState state
  ) {
    var envelope = envelope(raw, PlanEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<PlanItem>();
      for (var p : orEmpty(envelope.get().plan())) {
        requireText(p.whatToCheck(), "plan[].whatToCheck");
        requireText(p.whereToCheck(), "plan[].whereToCheck");
        requireText(p.supportingResult(), "plan[].supportingResult");
        requireText(p.contradictingResult(), "plan[].contradictingResult");
        if (p.supportingResult().equals(p.contradictingResult())) throw StageOutputException.schema(
          "plan[]: supportingResult and contradictingResult must be distinguishable in advance (STG-15.2)"
        );
        if (p.deadline() == null) throw StageOutputException.schema(
          "plan[]: a verification plan item needs a deadline (STG-15)"
        );
        if (genericVenue(p.whereToCheck())) throw StageOutputException.schema(
          "plan[]: whereToCheck must name a checkable venue or dataset (STG-15.3)"
        );
        if (p.hypothesisRef() == null && p.predictionRef() == null) throw StageOutputException.schema(
          "plan[]: a plan item must serve a hypothesis or a prediction (STG-15)"
        );
        if (p.hypothesisRef() != null) requireHypothesisRef(
          p.hypothesisRef(),
          state,
          "plan[]"
        );
        if (p.predictionRef() != null && state.predictions().stream().noneMatch(x -> x.id().equals(p.predictionRef()))) throw StageOutputException.schema(
          "plan[]: predictionRef does not resolve inside this snapshot (PR-09)"
        );
        values.add(p);
      }
      return new Artifacts<>(
        List.copyOf(values),
        orEmpty(envelope.get().unknowns()),
        false
      );
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      var delivered = snapshot.get().plan(state.facts(), state.hypotheses(), state.predictions());
      if (!delivered.isEmpty()) unknown.add(
        new Unknown(
          UUID.randomUUID(),
          "The v0.1 snapshot's indicators cannot serve as verification plan items (SCH-08 pending)",
          "an indicator carries no distinguishable supporting/contradicting result and no deadline",
          "plan items with supportingResult, contradictingResult and deadline"
        )
      );
      return new Artifacts<>(List.of(), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, "plan");
  }

  // ---- shared validation -------------------------------------------------

  private Artifacts<Fact> factsEnvelope(FactsEnvelope envelope, PipelineState state) {
    var facts = orEmpty(envelope.facts());
    if (facts.isEmpty()) throw StageOutputException.schema(
      "facts: an analysis with no extracted fact cannot support anything downstream (STG-03)"
    );
    return new Artifacts<>(
      validateFacts(facts, state),
      orEmpty(envelope.unknowns()),
      false
    );
  }

  private List<Fact> validateFacts(List<Fact> facts, PipelineState state) {
    var validated = new ArrayList<Fact>();
    var spans = new HashSet<String>();
    for (var fact : facts) {
      requireText(fact.statement(), "facts[].statement");
      requireText(fact.reasoning(), "facts[].reasoning");
      if (fact.sourceRefs().isEmpty()) throw StageOutputException.schema(
        "facts[]: every FACT requires at least one sourceRef (EP-05)"
      );
      for (var ref : fact.sourceRefs()) {
        validateSpan(ref, state, "facts[].sourceRefs");
      }
      boolean quotesVerbatim = fact
        .sourceRefs()
        .stream()
        .anyMatch(ref -> ref.quote().equals(fact.statement()));
      if (!quotesVerbatim) throw StageOutputException.schema(
        "facts[]: the statement must be an exact quoted source span (STG-03)"
      );
      boolean independentSet =
        state.sourceAssessment() != null &&
        state.sourceAssessment().independence() == Independence.INDEPENDENT_SET;
      if (
        fact.verificationStatus() == ProofStatus.CORROBORATED && !independentSet
      ) throw StageOutputException.schema(
        "facts[]: CORROBORATED requires an independent source, which this snapshot does not have (EP-04)"
      );
      var key = fact
        .sourceRefs()
        .stream()
        .map(r -> r.sourceId() + ":" + r.startOffset() + ":" + r.endOffset())
        .sorted()
        .reduce("", (a, b) -> a + "|" + b);
      if (!spans.add(key)) continue; // duplicate span: the same fact twice is one fact
      validated.add(fact);
    }
    if (validated.isEmpty()) throw StageOutputException.schema(
      "facts: no distinct fact span was produced (STG-03.2)"
    );
    return List.copyOf(validated);
  }

  private Artifacts<Variable> variablesEnvelope(
    VariablesEnvelope envelope,
    PipelineState state
  ) {
    var values = new ArrayList<Variable>();
    for (var v : orEmpty(envelope.variables())) {
      requireText(v.name(), "variables[].name");
      requireText(v.statement(), "variables[].statement");
      requireText(v.reasoning(), "variables[].reasoning");
      requireText(v.whyItMatters(), "variables[].whyItMatters");
      if (v.whyItMatters().trim().equalsIgnoreCase(v.name().trim())) throw StageOutputException.schema(
        "variables[]: whyItMatters must state the consequence chain, not restate the variable (STG-04.3)"
      );
      if (v.direction() != Direction.UNKNOWN) {
        if (!"UNKNOWN".equals(v.currentState())) {
          // currentState is present as required.
        } else throw StageOutputException.schema(
          "variables[]: a direction other than UNKNOWN requires currentState (STG-04.1)"
        );
        if (v.factRefs().isEmpty()) throw StageOutputException.schema(
          "variables[]: a direction other than UNKNOWN requires a fact reference (STG-04.1)"
        );
      }
      requireRefs(v.factRefs(), state, "variables[].factRefs");
      requireSourceRefs(v.sourceRefs(), state, "variables[].sourceRefs");
      values.add(v);
    }
    return new Artifacts<>(List.copyOf(values), orEmpty(envelope.unknowns()), false);
  }

  private Artifacts<Mechanism> mechanismsEnvelope(
    MechanismsEnvelope envelope,
    PipelineState state
  ) {
    var values = new ArrayList<Mechanism>();
    for (var m : orEmpty(envelope.mechanisms())) {
      requireText(m.explanation(), "mechanisms[].explanation");
      requireText(m.from(), "mechanisms[].from");
      requireText(m.to(), "mechanisms[].to");
      requireRefs(m.factRefs(), state, "mechanisms[].factRefs");
      requireSourceRefs(m.sourceRefs(), state, "mechanisms[].sourceRefs");
      requireResolvableConcept(m.from(), state, "mechanisms[].from");
      requireResolvableConcept(m.to(), state, "mechanisms[].to");
      switch (m.supportLevel()) {
        case SUPPORTED -> {
          if (m.factRefs().size() < 2) throw StageOutputException.schema(
            "mechanisms[]: SUPPORTED requires fact refs for both the cause premise and the effect observation (STG-05)"
          );
        }
        case PLAUSIBLE -> {
          if (m.factRefs().isEmpty()) throw StageOutputException.schema(
            "mechanisms[]: PLAUSIBLE requires at least one fact reference (STG-05)"
          );
          if (m.assumptions().isEmpty()) throw StageOutputException.schema(
            "mechanisms[]: PLAUSIBLE requires stated assumptions (STG-05)"
          );
        }
        case SPECULATIVE -> {
          if (
            m.corroboratingSignal() == null ||
            m.corroboratingSignal().isBlank() ||
            "UNKNOWN".equals(m.corroboratingSignal())
          ) throw StageOutputException.schema(
            "mechanisms[]: SPECULATIVE must name a corroborating signal (STG-05)"
          );
        }
      }
      values.add(m);
    }
    return new Artifacts<>(List.copyOf(values), orEmpty(envelope.unknowns()), false);
  }

  private List<Mechanism> strictMechanisms(
    List<Mechanism> values,
    PipelineState state
  ) {
    var kept = new ArrayList<Mechanism>();
    for (var m : values) {
      if (m.supportLevel() == SupportLevel.SPECULATIVE && "UNKNOWN".equals(m.corroboratingSignal())) {
        // Legacy snapshot mechanism with no declared support level: it cannot be
        // treated as documented, and it names no signal, so it is not kept.
        continue;
      }
      kept.add(m);
    }
    return List.copyOf(kept);
  }

  private enum EffectsOrder {
    FIRST,
    SECOND,
  }

  private Artifacts<Effect> effects(
    String raw,
    PipelineState state,
    EffectsOrder order
  ) {
    var envelope = envelope(raw, EffectsEnvelope.class);
    if (envelope.present()) {
      var values = new ArrayList<Effect>();
      for (var e : orEmpty(envelope.get().effects())) {
        requireText(e.statement(), "effects[].statement");
        requireText(e.reasoning(), "effects[].reasoning");
        if (e.factRefs().isEmpty()) throw StageOutputException.schema(
          "effects[]: an effect must reference the facts it follows from (EP-05)"
        );
        requireRefs(e.factRefs(), state, "effects[].factRefs");
        requireSourceRefs(e.sourceRefs(), state, "effects[].sourceRefs");
        if (order == EffectsOrder.SECOND) {
          if (e.derivedFromRefs().isEmpty()) throw StageOutputException.schema(
            "effects[]: a second-order effect requires its first-order parent (STG-08)"
          );
          for (var parent : e.derivedFromRefs()) {
            if (state.firstOrderEffects().stream().noneMatch(x -> x.id().equals(parent))) throw StageOutputException.schema(
              "effects[]: derivedFromRefs does not resolve to a first-order effect inside this snapshot (STG-08)"
            );
          }
        }
        values.add(e);
      }
      return new Artifacts<>(List.copyOf(values), orEmpty(envelope.get().unknowns()), false);
    }
    var snapshot = snapshot(raw);
    if (snapshot.isPresent()) {
      var unknown = new ArrayList<>(snapshot.get().unknowns());
      var delivered = order == EffectsOrder.FIRST
        ? snapshot.get().firstOrderEffects(state.facts())
        : snapshot.get().secondOrderEffects(state.facts());
      var values = new ArrayList<Effect>();
      for (var e : delivered) {
        if (e.factRefs().isEmpty()) {
          unknown.add(
            demoted(
              order == EffectsOrder.FIRST ? "firstOrderEffects" : "secondOrderEffects",
              e.statement(),
              "no fact inside this snapshot supports the effect (EP-01)"
            )
          );
          continue;
        }
        if (order == EffectsOrder.SECOND) {
          unknown.add(
            demoted(
              "secondOrderEffects",
              e.statement(),
              "the v0.1 snapshot does not link a second-order effect to its first-order parent (SCH-03 pending)"
            )
          );
          continue;
        }
        values.add(e);
      }
      return new Artifacts<>(List.copyOf(values), unknown, snapshot.get().demo());
    }
    throw wrongShape(raw, order == EffectsOrder.FIRST ? "effects" : "effects");
  }

  // ---- primitives ---------------------------------------------------------

  private Optional<SnapshotEnvelope> snapshot(String raw) {
    return SnapshotEnvelope.tryParse(raw, json);
  }

  private <T> Present<T> envelope(String raw, Class<T> type) {
    try {
      return new Present<>(json.read(raw, type));
    } catch (RuntimeException notTheEnvelopeShape) {
      return new Present<>(null);
    }
  }

  private record Present<T>(T value) {
    boolean present() {
      return value != null;
    }

    T get() {
      return value;
    }
  }

  private static <T> List<T> orEmpty(List<T> value) {
    return ProtocolModel.orEmpty(value);
  }

  /**
   * Distinguishes an undecodable response from a decodable but wrongly shaped one:
   * both are controlled failures with different classifications, and neither is
   * silently coerced.
   */
  private StageOutputException wrongShape(String raw, String key) {
    try {
      json.read(raw, Map.class);
    } catch (RuntimeException notJson) {
      return StageOutputException.malformed(
        "the model response is not decodable JSON"
      );
    }
    return StageOutputException.schema(
      "expected a JSON object with a '" +
      key +
      "' array (or a complete AnalysisResult snapshot)"
    );
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw StageOutputException.schema(
      field + " must not be blank"
    );
  }

  private static void requireHypothesisRef(
    UUID ref,
    PipelineState state,
    String field
  ) {
    if (ref == null) throw StageOutputException.schema(
      field + " requires a hypothesis reference"
    );
    if (state.hypotheses().stream().noneMatch(h -> h.id().equals(ref))) throw StageOutputException.schema(
      field + ": hypothesisRef does not resolve inside this snapshot (PR-09)"
    );
  }

  private static Hypothesis hypothesis(PipelineState state, UUID id) {
    return state
      .hypotheses()
      .stream()
      .filter(h -> h.id().equals(id))
      .findFirst()
      .orElse(null);
  }

  private static void requireRefs(
    List<UUID> refs,
    PipelineState state,
    String field
  ) {
    for (var ref : refs) {
      if (state.facts().stream().noneMatch(f -> f.id().equals(ref))) throw StageOutputException.schema(
        field + ": a fact ref does not resolve inside this snapshot (PR-09)"
      );
    }
  }

  private static void requireSourceRefs(
    List<SourceRef> refs,
    PipelineState state,
    String field
  ) {
    for (var ref : refs) validateSpan(ref, state, field);
  }

  private static void validateSpan(
    SourceRef ref,
    PipelineState state,
    String field
  ) {
    String text = state.news().source().text();
    if (!ref.sourceId().equals(state.news().source().id())) throw StageOutputException.schema(
      field + ": sourceId does not match the analyzed source (PR-01)"
    );
    if (
      ref.startOffset() < 0 ||
      ref.endOffset() > text.length() ||
      ref.endOffset() <= ref.startOffset()
    ) throw StageOutputException.schema(
      field + ": offsets are outside the source text (PR-07)"
    );
    if (!text.substring(ref.startOffset(), ref.endOffset()).equals(ref.quote())) throw StageOutputException.schema(
      field + ": the quote is not a verbatim substring of the source text (PR-07/PR-10)"
    );
  }

  private static void requireResolvableConcept(
    String concept,
    PipelineState state,
    String field
  ) {
    if (resolvableConcept(concept, state)) return;
    throw StageOutputException.schema(
      field +
      ": a causal link endpoint must resolve to a variable or fact already in the snapshot (STG-05.1)"
    );
  }

  private static boolean resolvableConcept(String concept, PipelineState state) {
    String needle = concept == null ? "" : concept.trim().toLowerCase(Locale.ROOT);
    if (needle.isEmpty()) return false;
    for (var v : state.variables()) {
      if (v.name().toLowerCase(Locale.ROOT).equals(needle)) return true;
      if (v.id().toString().equals(concept.trim())) return true;
    }
    for (var f : state.facts()) {
      if (f.id().toString().equals(concept.trim())) return true;
      if (f.statement().toLowerCase(Locale.ROOT).contains(needle)) return true;
    }
    return false;
  }

  private static boolean decidable(String boundary) {
    if (boundary == null) return false;
    String text = boundary.toLowerCase(Locale.ROOT);
    if (text.contains("circumstances change") || text.contains("情况变化")) return false;
    return (
      text.matches(".*\\d.*") ||
      text.contains(">") ||
      text.contains("<") ||
      text.contains("=") ||
      text.contains("within") ||
      text.contains("before") ||
      text.contains("after") ||
      text.contains("超过") ||
      text.contains("低于") ||
      text.contains("之前") ||
      text.contains("之后")
    );
  }

  private static boolean genericVenue(String venue) {
    if (venue == null) return true;
    String text = venue.trim().toLowerCase(Locale.ROOT);
    return (
      text.equals("further research") ||
      text.equals("more research") ||
      text.equals("进一步研究") ||
      text.equals("继续研究") ||
      text.equals("unknown") ||
      text.isBlank()
    );
  }

  private static Unknown demoted(String artifact, String statement, String reason) {
    return new Unknown(
      UUID.randomUUID(),
      "Dropped " + artifact + " item: " + abbreviate(statement),
      reason,
      "evidence that resolves the missing reference inside the snapshot"
    );
  }

  private static String abbreviate(String value) {
    if (value == null || value.isBlank()) return "(empty statement)";
    String compact = value.replaceAll("\\s+", " ").trim();
    return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
  }
}
