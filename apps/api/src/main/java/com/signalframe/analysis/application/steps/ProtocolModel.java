package com.signalframe.analysis.application.steps;

import com.signalframe.contract.SourceRef;
import java.time.Instant;
import java.util.*;

/**
 * Internal, immutable representation of the Analysis Protocol v0.1 artifacts.
 *
 * <p>This is deliberately <em>not</em> the generated OpenAPI contract. The
 * protocol requires mandatory fields that the frozen v0.1 snapshot schema does
 * not carry yet (`sourceAssessment`, `Fact.verificationStatus`,
 * `Statement.factRefs`, `CausalLink.supportLevel`, nested falsification
 * conditions, `predictions`, rubric dimensions). Stage code therefore produces
 * and validates these artifacts, and {@link SnapshotProjection} projects the
 * validated result onto the v0.1 {@code AnalysisResult}. No generated record is
 * edited by the pipeline.
 *
 * <p>Every record normalises its collections so that downstream code never sees
 * {@code null} where the protocol says "empty is meaningful": an empty list must
 * mean "evaluated, none exist" (protocol PR-05), never "field omitted".
 * Epistemic type is carried by the stage that produced the artifact, which is
 * why no record here has a mutable {@code type} field.
 *
 * <p>Vocabulary values are enums so that an unsupported value from a model is a
 * controlled parse failure instead of a silently tolerated string.
 */
public final class ProtocolModel {

  private ProtocolModel() {}

  /** STG-02 `sourceType` vocabulary. */
  public enum SourceType {
    PRIMARY_DOCUMENT,
    OFFICIAL_STATEMENT,
    COMPANY_DISCLOSURE,
    PRESS_RELEASE,
    NEWS_REPORT,
    WIRE_REPUBLICATION,
    OPINION_ANALYSIS,
    SOCIAL_POST,
    UNKNOWN,
  }

  /** STG-02 `primaryOrSecondary` vocabulary. */
  public enum SourceClass {
    PRIMARY,
    SECONDARY,
    UNKNOWN,
  }

  /** STG-02 `independence` vocabulary. */
  public enum Independence {
    SINGLE_SOURCE,
    SAME_PUBLISHER_DUPLICATE,
    INDEPENDENT_SET,
    UNKNOWN,
  }

  /** STG-02 `contentCompleteness` vocabulary. */
  public enum Completeness {
    COMPLETE,
    PARTIAL,
    TRUNCATED,
    METADATA_ONLY,
    UNKNOWN,
  }

  /** EPISTEMIC_TYPES §2.1 `verificationStatus`. `CORROBORATED` needs a second source. */
  public enum ProofStatus {
    REPORTED,
    CORROBORATED,
    DISPUTED,
  }

  /** STG-04 `direction`. */
  public enum Direction {
    UP,
    DOWN,
    UNCHANGED,
    UNKNOWN,
  }

  /** STG-06 `direction`. */
  public enum StakeholderDirection {
    BENEFITS,
    HARMED,
    MIXED,
    UNKNOWN,
  }

  /** STG-05 `supportLevel`, exactly three values. */
  public enum SupportLevel {
    SUPPORTED,
    PLAUSIBLE,
    SPECULATIVE,
  }

  /** STG-13: signals are expected observables, never existing evidence. */
  public enum SignalStatus {
    NOT_OBSERVED,
  }

  /** STG-14: a prediction is created `OPEN`; outcomes arrive as verification records. */
  public enum PredictionStatus {
    OPEN,
  }

  /** STG-15 `priority`. */
  public enum PlanPriority {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
  }

  /**
   * STG-02 source assessment. Every mandatory field of the protocol is present;
   * `notes` records what could not be derived deterministically.
   */
  public record SourceAssessment(
    SourceType sourceType,
    String publisher,
    String publishedAt,
    SourceClass primaryOrSecondary,
    Independence independence,
    Completeness contentCompleteness,
    List<String> notes
  ) {
    public SourceAssessment {
      sourceType = sourceType == null ? SourceType.UNKNOWN : sourceType;
      primaryOrSecondary = primaryOrSecondary == null
        ? SourceClass.UNKNOWN
        : primaryOrSecondary;
      independence = independence == null
        ? Independence.UNKNOWN
        : independence;
      contentCompleteness = contentCompleteness == null
        ? Completeness.UNKNOWN
        : contentCompleteness;
      publisher = publisher == null ? "UNKNOWN" : publisher;
      notes = notes == null ? List.of() : List.copyOf(notes);
    }
  }

  /** STG-03 fact: an exact source span, never independently verified truth. */
  public record Fact(
    UUID id,
    String statement,
    String reasoning,
    List<SourceRef> sourceRefs,
    ProofStatus verificationStatus
  ) {
    public Fact {
      id = id == null ? UUID.randomUUID() : id;
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      verificationStatus = verificationStatus == null
        ? ProofStatus.REPORTED
        : verificationStatus;
    }
  }

  /** STG-04 variable: an INFERENCE with fact references. */
  public record Variable(
    UUID id,
    String name,
    String previousState,
    String currentState,
    Direction direction,
    String magnitude,
    String whyItMatters,
    String statement,
    String reasoning,
    List<UUID> factRefs,
    List<SourceRef> sourceRefs,
    Integer confidence
  ) {
    public Variable {
      id = id == null ? UUID.randomUUID() : id;
      direction = direction == null ? Direction.UNKNOWN : direction;
      previousState = blankToUnknown(previousState);
      currentState = blankToUnknown(currentState);
      magnitude = blankToUnknown(magnitude);
      factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /** STG-05 mechanism: an INFERENCE with a normative support level. */
  public record Mechanism(
    UUID id,
    String from,
    String to,
    SupportLevel supportLevel,
    String explanation,
    String reasoning,
    List<String> assumptions,
    List<UUID> factRefs,
    List<SourceRef> sourceRefs,
    String corroboratingSignal,
    Integer confidence
  ) {
    public Mechanism {
      id = id == null ? UUID.randomUUID() : id;
      supportLevel = supportLevel == null
        ? SupportLevel.SPECULATIVE
        : supportLevel;
      assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
      factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /** STG-06 stakeholder exposure, traceable to facts. */
  public record Stakeholder(
    UUID id,
    String stakeholder,
    StakeholderDirection direction,
    String statement,
    String reasoning,
    List<UUID> factRefs,
    List<SourceRef> sourceRefs,
    Integer confidence
  ) {
    public Stakeholder {
      id = id == null ? UUID.randomUUID() : id;
      direction = direction == null
        ? StakeholderDirection.UNKNOWN
        : direction;
      factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /**
   * STG-07/STG-08 effect. `derivedFromRefs` is mandatory for second-order
   * effects and empty for first-order effects.
   */
  public record Effect(
    UUID id,
    String statement,
    String reasoning,
    List<UUID> factRefs,
    List<UUID> derivedFromRefs,
    List<SourceRef> sourceRefs,
    Integer confidence
  ) {
    public Effect {
      id = id == null ? UUID.randomUUID() : id;
      factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
      derivedFromRefs = derivedFromRefs == null
        ? List.of()
        : List.copyOf(derivedFromRefs);
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /** STG-09 hypothesis: provisional explanation with refs, rivals and assumptions. */
  public record Hypothesis(
    UUID id,
    String title,
    String statement,
    String reasoning,
    List<UUID> supportingFactRefs,
    List<UUID> supportingEvidenceRefs,
    List<UUID> contradictingEvidenceRefs,
    List<String> assumptions,
    List<UUID> alternativeRefs,
    List<UUID> falsificationRefs,
    String confidenceReason,
    Integer advisoryConfidence
  ) {
    public Hypothesis {
      id = id == null ? UUID.randomUUID() : id;
      supportingFactRefs = supportingFactRefs == null
        ? List.of()
        : List.copyOf(supportingFactRefs);
      supportingEvidenceRefs = supportingEvidenceRefs == null
        ? List.of()
        : List.copyOf(supportingEvidenceRefs);
      contradictingEvidenceRefs = contradictingEvidenceRefs == null
        ? List.of()
        : List.copyOf(contradictingEvidenceRefs);
      assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
      alternativeRefs = alternativeRefs == null
        ? List.of()
        : List.copyOf(alternativeRefs);
      falsificationRefs = falsificationRefs == null
        ? List.of()
        : List.copyOf(falsificationRefs);
      advisoryConfidence = advisory(advisoryConfidence);
    }
  }

  /** STG-10 alternative explanation of the same facts. */
  public record Alternative(
    UUID id,
    String statement,
    String reasoning,
    UUID rivalsHypothesisRef,
    List<UUID> factRefs,
    List<SourceRef> sourceRefs,
    Integer confidence
  ) {
    public Alternative {
      id = id == null ? UUID.randomUUID() : id;
      factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /** STG-11 counter argument: the strongest objection to a hypothesis. */
  public record CounterArgument(
    UUID id,
    String statement,
    String reasoning,
    UUID targetHypothesisRef,
    List<UUID> factRefs,
    List<SourceRef> sourceRefs,
    Integer confidence
  ) {
    public CounterArgument {
      id = id == null ? UUID.randomUUID() : id;
      factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /** STG-12 falsification condition: observable, comparison, decision boundary. */
  public record FalsificationCondition(
    UUID id,
    UUID hypothesisRef,
    String observable,
    String comparison,
    String decisionBoundary,
    String statement
  ) {
    public FalsificationCondition {
      id = id == null ? UUID.randomUUID() : id;
    }
  }

  /** STG-13 corroborating signal: a hypothetical future observable. */
  public record Signal(
    UUID id,
    String signal,
    String where,
    UUID hypothesisRef,
    String window,
    SignalStatus status
  ) {
    public Signal {
      id = id == null ? UUID.randomUUID() : id;
      status = status == null ? SignalStatus.NOT_OBSERVED : status;
    }
  }

  /** STG-14 prediction: observable, time bounded, verifiable, linked. */
  public record Prediction(
    UUID id,
    UUID hypothesisRef,
    String statement,
    String observable,
    Instant expectedBy,
    String verificationCriteria,
    String whereToCheck,
    PredictionStatus status
  ) {
    public Prediction {
      id = id == null ? UUID.randomUUID() : id;
      status = status == null ? PredictionStatus.OPEN : status;
    }
  }

  /** STG-15 verification plan item. */
  public record PlanItem(
    UUID id,
    String whatToCheck,
    String whereToCheck,
    String supportingResult,
    String contradictingResult,
    PlanPriority priority,
    Instant deadline,
    UUID hypothesisRef,
    UUID predictionRef,
    String statement,
    String reasoning,
    List<SourceRef> sourceRefs,
    Integer confidence
  ) {
    public PlanItem {
      id = id == null ? UUID.randomUUID() : id;
      priority = priority == null ? PlanPriority.UNKNOWN : priority;
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
      confidence = advisory(confidence);
    }
  }

  /** UNKNOWN: a first-class output carrying why and what would resolve it. */
  public record Unknown(
    UUID id,
    String statement,
    String unknownReason,
    String wouldResolveWith,
    List<SourceRef> sourceRefs
  ) {
    public Unknown {
      id = id == null ? UUID.randomUUID() : id;
      sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }

    /** Convenience for a gap that has no source span to point at. */
    public Unknown(
      UUID id,
      String statement,
      String unknownReason,
      String wouldResolveWith
    ) {
      this(id, statement, unknownReason, wouldResolveWith, List.of());
    }
  }

  // ---- stage envelopes: what each model-backed stage must return ---------
  //
  // Every envelope may additionally carry `unknowns`: the protocol requires an
  // explicit UNKNOWN item when a stage cannot produce its artifact, so silence is
  // never a valid answer (protocol principle 3, PR-05).

  public record FactsEnvelope(List<Fact> facts, List<Unknown> unknowns) {}

  public record VariablesEnvelope(
    List<Variable> variables,
    List<Unknown> unknowns
  ) {}

  public record MechanismsEnvelope(
    List<Mechanism> mechanisms,
    List<Unknown> unknowns
  ) {}

  public record StakeholdersEnvelope(
    List<Stakeholder> stakeholders,
    List<Unknown> unknowns
  ) {}

  public record EffectsEnvelope(
    List<Effect> effects,
    List<Unknown> unknowns
  ) {}

  public record HypothesesEnvelope(
    List<Hypothesis> hypotheses,
    List<Unknown> unknowns
  ) {}

  public record AlternativesEnvelope(
    List<Alternative> alternatives,
    List<Unknown> unknowns
  ) {}

  public record CounterArgumentsEnvelope(
    List<CounterArgument> counterArguments,
    List<Unknown> unknowns
  ) {}

  public record FalsificationEnvelope(
    List<FalsificationCondition> falsificationConditions,
    List<Unknown> unknowns
  ) {}

  public record SignalsEnvelope(List<Signal> signals, List<Unknown> unknowns) {}

  public record PredictionsEnvelope(
    List<Prediction> predictions,
    List<Unknown> unknowns
  ) {}

  public record PlanEnvelope(List<PlanItem> plan, List<Unknown> unknowns) {}

  static <T> List<T> orEmpty(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }

  static String blankToUnknown(String value) {
    return value == null || value.isBlank() ? "UNKNOWN" : value;
  }

  static Integer advisory(Integer value) {
    if (value == null) return 0;
    return Math.max(0, Math.min(100, value));
  }
}
