package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Gate B/C/E over the assembled snapshot (ANALYSIS_PROTOCOL_V0_1 §7).
 *
 * <p>This stage authors nothing. It enforces that every protocol stage either
 * produced its artifact or an explicit UNKNOWN item (Gate E / PR-05), that refs
 * resolve inside the snapshot (Gate C / PR-09), and that an artifact the
 * protocol cannot accept is demoted to UNKNOWN rather than kept (EP-01/EP-02).
 * A hypothesis with no falsification condition or no alternative explanation is
 * dropped and reported — the protocol allows it to exist only as UNKNOWN.
 *
 * <p>Structural gates (Gate A) and the confidence gate (Gate D) run in the
 * projection stage, because they apply to the projected snapshot.
 */
@Component
public class EpistemicGateStage implements ProtocolStage {

  static final String NAME = "EpistemicGate";
  static final String STEP = "SynthesizeAnalysis";

  /**
   * Stages that must have run for the snapshot to be complete. A stage that did
   * not run is a job failure, because a silent `[]` would read as "evaluated,
   * nothing found" (PR-05).
   */
  static final List<String> MANDATORY_STAGES = List.of(
    "SourceAssessment",
    "FactExtraction",
    "DomainClassification",
    "NewsScoring",
    "VariableAnalysis",
    "MechanismAnalysis",
    "FirstOrderEffect",
    "SecondOrderEffect",
    "StakeholderAnalysis",
    "HypothesisGeneration",
    "AlternativeExplanation",
    "CounterArgument",
    "FalsificationCondition",
    "CorroboratingSignal",
    "PredictionGeneration",
    "VerificationPlan"
  );

  @Override
  public PipelineState execute(PipelineState in) {
    for (var stage : MANDATORY_STAGES) {
      if (!in.executedStages().contains(stage)) throw new StageFailure(
        NAME,
        StageFailure.Kind.MISSING_ARTIFACT,
        "mandatory protocol stage '" +
        stage +
        "' did not run, so the snapshot cannot claim to be complete (PR-05)"
      );
    }
    if (in.sourceAssessment() == null) throw new StageFailure(
      NAME,
      StageFailure.Kind.MISSING_ARTIFACT,
      "the source assessment is missing (STG-02)"
    );
    if (in.facts().isEmpty()) throw new StageFailure(
      NAME,
      StageFailure.Kind.MISSING_ARTIFACT,
      "an analysis without extracted facts cannot support anything downstream (STG-03)"
    );

    var unknown = new ArrayList<>(in.unknowns());

    // EP-06 first: an UNKNOWN item must never be cited as support.
    assertUnknownsAreNotCited(in);

    // Gate C: every ref must resolve inside the snapshot.
    for (var v : in.variables()) requireFactRefs(v.factRefs(), in, "variables");
    for (var m : in.mechanisms()) requireFactRefs(m.factRefs(), in, "mechanisms");
    for (var s : in.stakeholders()) requireFactRefs(
      s.factRefs(),
      in,
      "stakeholders"
    );
    for (var e : in.firstOrderEffects()) requireFactRefs(
      e.factRefs(),
      in,
      "firstOrderEffects"
    );
    for (var e : in.secondOrderEffects()) requireFactRefs(
      e.factRefs(),
      in,
      "secondOrderEffects"
    );
    for (var h : in.hypotheses()) requireFactRefs(
      h.supportingFactRefs(),
      in,
      "hypotheses"
    );
    for (var a : in.alternativeExplanations()) requireFactRefs(
      a.factRefs(),
      in,
      "alternatives"
    );
    for (var c : in.counterArguments()) requireFactRefs(
      c.factRefs(),
      in,
      "counterArguments"
    );

    // STG-09/STG-12: a hypothesis that cannot be falsified or has no rival
    // explanation is not emitted as a hypothesis.
    var hypotheses = new ArrayList<Hypothesis>();
    for (var h : in.hypotheses()) {
      var conditions = in
        .falsificationConditions()
        .stream()
        .filter(c -> h.id().equals(c.hypothesisRef()))
        .toList();
      var rivals = in
        .alternativeExplanations()
        .stream()
        .filter(a -> h.id().equals(a.rivalsHypothesisRef()))
        .toList();
      if (h.supportingFactRefs().isEmpty()) {
        unknown.add(
          demotedHypothesis(
            h,
            "no fact inside the snapshot supports it (EP-05)"
          )
        );
        continue;
      }
      if (conditions.isEmpty()) {
        unknown.add(
          demotedHypothesis(
            h,
            "no pre-registered falsification condition exists, so it is not falsifiable (STG-12.3)"
          )
        );
        continue;
      }
      if (rivals.isEmpty()) {
        unknown.add(
          demotedHypothesis(
            h,
            "no alternative explanation of the same facts was produced (STG-09.2)"
          )
        );
        continue;
      }
      hypotheses.add(
        new Hypothesis(
          h.id(),
          h.title(),
          h.statement(),
          h.reasoning(),
          h.supportingFactRefs(),
          h.supportingEvidenceRefs(),
          h.contradictingEvidenceRefs(),
          h.assumptions(),
          rivals.stream().map(Alternative::id).toList(),
          conditions.stream().map(FalsificationCondition::id).toList(),
          h.confidenceReason(),
          h.advisoryConfidence()
        )
      );
    }
    var hypothesisIds = new HashSet<>(
      hypotheses.stream().map(Hypothesis::id).toList()
    );

    var alternatives = in
      .alternativeExplanations()
      .stream()
      .filter(a -> hypothesisIds.contains(a.rivalsHypothesisRef()))
      .toList();
    var counters = in
      .counterArguments()
      .stream()
      .filter(c -> hypothesisIds.contains(c.targetHypothesisRef()))
      .toList();
    var conditions = in
      .falsificationConditions()
      .stream()
      .filter(c -> hypothesisIds.contains(c.hypothesisRef()))
      .toList();
    var signals = in
      .corroboratingSignals()
      .stream()
      .filter(s -> hypothesisIds.contains(s.hypothesisRef()))
      .toList();

    // STG-15.1: every OPEN prediction needs a plan item with deadline <= expectedBy.
    var plan = new ArrayList<PlanItem>();
    var predictions = new ArrayList<Prediction>();
    for (var p : in.predictions()) {
      if (!hypothesisIds.contains(p.hypothesisRef())) {
        unknown.add(
          demoted(p.statement(), "its hypothesis is not part of this snapshot")
        );
        continue;
      }
      boolean covered = in
        .verificationPlan()
        .stream()
        .anyMatch(i ->
          p.id().equals(i.predictionRef()) &&
          i.deadline() != null &&
          !i.deadline().isAfter(p.expectedBy())
        );
      if (!covered) {
        unknown.add(
          new Unknown(
            UUID.randomUUID(),
            "Dropped prediction without a verification plan item that resolves by its expectedBy date: " +
            abbreviate(p.statement()),
            "STG-15.1 requires an OPEN prediction to have a plan item with deadline <= expectedBy",
            "a plan item naming the venue, the two distinguishable outcomes and a deadline"
          )
        );
        continue;
      }
      predictions.add(p);
    }
    var predictionIds = new HashSet<>(
      predictions.stream().map(Prediction::id).toList()
    );
    for (var item : in.verificationPlan()) {
      if (item.hypothesisRef() != null && !hypothesisIds.contains(item.hypothesisRef())) continue;
      if (
        item.predictionRef() != null && !predictionIds.contains(item.predictionRef())
      ) continue;
      plan.add(item);
    }

    // EP-07: alternatives and counter-arguments are different artifacts. The
    // structure already keeps them separate; identical wording is reported rather
    // than hidden.
    var counterStatements = new HashSet<>(
      counters
        .stream()
        .map(c -> c.statement().trim().toLowerCase(Locale.ROOT))
        .toList()
    );
    for (var a : alternatives) {
      if (
        counterStatements.contains(a.statement().trim().toLowerCase(Locale.ROOT))
      ) {
        unknown.add(
          new Unknown(
            UUID.randomUUID(),
            "The same statement appears as an alternative explanation and as a counter-argument: " +
            abbreviate(a.statement()),
            "EP-07 keeps 'what else could be going on' and 'why might I be wrong' as distinct artifacts",
            "a rival explanatory model and a separate objection"
          )
        );
      }
    }

    // Protocol gaps that must be visible rather than silently empty.
    if (in.mechanisms().isEmpty()) unknown.add(
      new Unknown(
        UUID.randomUUID(),
        "No causal mechanism was documented for this input",
        "EP-03: a causal link may not be invented to make the narrative cohere",
        "a source that documents the causal step with evidence at both ends"
      )
    );
    if (hypotheses.isEmpty()) unknown.add(
      new Unknown(
        UUID.randomUUID(),
        "No hypothesis was emitted for this input",
        "STG-09.1: at least one hypothesis is expected whenever the input invites explanation; unfalsifiable candidates are dropped",
        "facts that support a falsifiable explanation with at least one rival and one condition"
      )
    );
    for (var h : hypotheses) {
      boolean watchable =
        predictions.stream().anyMatch(p -> h.id().equals(p.hypothesisRef())) ||
        signals.stream().anyMatch(s -> h.id().equals(s.hypothesisRef()));
      if (!watchable) unknown.add(
        new Unknown(
          UUID.randomUUID(),
          "Hypothesis '" +
          h.title() +
          "' has neither a prediction nor a corroborating signal",
          "STG-14.4: with zero predictions there must still be something to watch",
          "a dated prediction or an expected observable with a place and a window"
        )
      );
    }
    if (!counters.isEmpty() && counters.stream().noneMatch(c -> !c.factRefs().isEmpty())) unknown.add(
      new Unknown(
        UUID.randomUUID(),
        "No counter-argument rests on a fact inside this snapshot",
        "STG-11: the strongest objection should be attempted, not a list of generic caveats",
        "an objection grounded in a reported fact or an independent record"
      )
    );
    unknown.add(
      new Unknown(
        UUID.randomUUID(),
        "No independent source corroborates the input of this snapshot",
        "only one source is present, so no independent confirmation exists (EPISTEMIC_TYPES §2.1)",
        "an independent source or record asserting the same substance"
      )
    );
    if (
      "UNKNOWN".equals(in.sourceAssessment().publishedAt()) ||
      in.sourceAssessment().publishedAt() == null
    ) unknown.add(
      new Unknown(
        UUID.randomUUID(),
        "The publication date of the input is unknown",
        "STG-02: the ingestion payload carries no publication timestamp and inferring one is forbidden",
        "the publisher's own dated page or an archival record"
      )
    );

    return in
      .withHypotheses(List.copyOf(hypotheses))
      .withAlternativeExplanations(alternatives)
      .withCounterArguments(counters)
      .withFalsificationConditions(conditions)
      .withCorroboratingSignals(signals)
      .withPredictions(List.copyOf(predictions))
      .withVerificationPlan(List.copyOf(plan))
      .withUnknowns(dedupe(unknown))
      .executed(NAME, null);
  }

  private static void requireFactRefs(
    List<UUID> refs,
    PipelineState state,
    String artifact
  ) {
    for (var ref : refs) {
      if (state.facts().stream().noneMatch(f -> f.id().equals(ref))) throw new StageFailure(
        NAME,
        StageFailure.Kind.VALIDATION_FAILED,
        artifact +
        ": a fact reference does not resolve inside the snapshot (PR-09)"
      );
    }
  }

  /** EP-06: an UNKNOWN item may never be cited as support. */
  private static void assertUnknownsAreNotCited(PipelineState state) {
    var unknownIds = new HashSet<>(
      state.unknowns().stream().map(Unknown::id).toList()
    );
    if (unknownIds.isEmpty()) return;
    for (var ref : allFactRefs(state)) {
      if (unknownIds.contains(ref)) throw new StageFailure(
        NAME,
        StageFailure.Kind.VALIDATION_FAILED,
        "an UNKNOWN item is cited as supporting evidence (EP-06)"
      );
    }
  }

  private static List<UUID> allFactRefs(PipelineState state) {
    var refs = new ArrayList<UUID>();
    state.variables().forEach(v -> refs.addAll(v.factRefs()));
    state.mechanisms().forEach(m -> refs.addAll(m.factRefs()));
    state.stakeholders().forEach(s -> refs.addAll(s.factRefs()));
    state.firstOrderEffects().forEach(e -> refs.addAll(e.factRefs()));
    state.secondOrderEffects().forEach(e -> refs.addAll(e.factRefs()));
    state.hypotheses().forEach(h -> refs.addAll(h.supportingFactRefs()));
    state.alternativeExplanations().forEach(a -> refs.addAll(a.factRefs()));
    state.counterArguments().forEach(c -> refs.addAll(c.factRefs()));
    return refs;
  }

  private static Unknown demotedHypothesis(Hypothesis h, String reason) {
    return new Unknown(
      UUID.randomUUID(),
      "Dropped hypothesis '" + h.title() + "': " + abbreviate(h.statement()),
      reason,
      "at least one supporting fact, one alternative explanation and one pre-registered falsification condition"
    );
  }

  private static Unknown demoted(String statement, String reason) {
    return new Unknown(
      UUID.randomUUID(),
      "Dropped PREDICTION: " + abbreviate(statement),
      reason,
      "a verifiable prediction whose plan item resolves by its expectedBy date"
    );
  }

  private static List<Unknown> dedupe(List<Unknown> unknowns) {
    var seen = new LinkedHashSet<String>();
    var result = new ArrayList<Unknown>();
    for (var u : unknowns) {
      String key = u.statement().trim() + "::" + u.unknownReason().trim();
      if (seen.add(key)) result.add(u);
    }
    return List.copyOf(result);
  }

  private static String abbreviate(String value) {
    if (value == null || value.isBlank()) return "(empty)";
    String compact = value.replaceAll("\\s+", " ").trim();
    return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.SYNTHESIZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
