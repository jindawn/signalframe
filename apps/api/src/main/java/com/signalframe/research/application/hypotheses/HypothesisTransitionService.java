package com.signalframe.research.application.hypotheses;

import com.signalframe.contract.ConfidenceBand;
import com.signalframe.contract.ConfidenceDimension;
import com.signalframe.contract.Hypothesis;
import com.signalframe.contract.HypothesisEvent;
import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;
import com.signalframe.research.domain.hypotheses.EvidenceStance;
import com.signalframe.research.domain.hypotheses.HypothesisLifecycle;
import com.signalframe.research.domain.hypotheses.HypothesisSnapshot;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionPort;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionRepository;
import com.signalframe.research.domain.hypotheses.RubricReasonText;
import com.signalframe.research.domain.hypotheses.StoredEvidence;
import com.signalframe.research.domain.hypotheses.StoredHypothesis;
import com.signalframe.research.domain.hypotheses.StoredPrediction;
import com.signalframe.research.domain.hypotheses.TransitionErrors;
import com.signalframe.research.domain.hypotheses.TransitionEventText;
import com.signalframe.shared.confidence.ConfidenceRubric;
import com.signalframe.shared.confidence.ConfidenceScore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Wave 2B hypothesis engine: one transaction that moves a hypothesis's status,
 * recomputes its confidence with the deterministic rubric, appends an immutable
 * timeline event and bumps the optimistic-lock version.
 *
 * <p>An external caller can come in two ways, and both end in the same private
 * {@link #apply(UUID, HypothesisTransitionCommand)}:
 *
 * <ul>
 *   <li>{@link #transition(HypothesisTransitionCommand)} — the frozen
 *       {@link HypothesisTransitionPort}. TASK-07 submits evidence changes and
 *       prediction verification outcomes here and never writes
 *       {@code hypotheses.confidence}, {@code .status} or {@code .version} itself.
 *       The command carries a reference to the evidence or prediction, and that
 *       reference is what identifies the hypothesis.</li>
 *   <li>{@link #transition(UUID, HypothesisTransitionCommand)} — the hypothesis is
 *       named by the caller. This is the HTTP path
 *       {@code POST /api/v1/hypotheses/{id}/transitions} and TASK-06's own
 *       falsification and deadline paths, where a path variable carries the
 *       identity the frozen command record has no field for.</li>
 * </ul>
 *
 * <h2>Why the reference identifies the hypothesis</h2>
 * The frozen {@code HypothesisTransitionCommand} has no {@code hypothesisId}
 * component: {@code HypothesisTransitions}' factories take one, validate it and
 * then drop it, and the port's javadoc names a field the generated record does not
 * carry. Adding it would need an OpenAPI change, and publishing a second DTO is
 * exactly what AGENTS.md forbids. Deriving identity from the reference is not a
 * workaround that loses safety: the reference must resolve to a row that already
 * belongs to a hypothesis, and {@code evidence.hypothesis_id} /
 * {@code predictions.hypothesis_id} are foreign keys, so the mapping is total and
 * single-valued. See the "Known gaps" note in the task report.
 *
 * <h2>Transaction and lock order</h2>
 * {@code SELECT ... FOR UPDATE} on the hypothesis row is the first statement, so
 * every read a decision depends on — the current version, the evidence set, the
 * snapshot — is already serialized against concurrent transitions of the same
 * hypothesis. The idempotency lookup comes after the lock, so two concurrent
 * replays of one {@code operationId} cannot both observe "not yet applied".
 */
@Service
public class HypothesisTransitionService implements HypothesisTransitionPort {

  /**
   * The single, canonical deterministic rubric. It is pure and carries no state,
   * so it is constructed rather than injected: the module cannot accidentally be
   * wired with a different implementation, and no second
   * {@code ConfidenceRubric} bean competes with the one
   * {@code AnalysisConfiguration} already declares.
   */
  private final ConfidenceRubric rubric = ConfidenceRubric.deterministic();

  private final HypothesisTransitionRepository repository;

  public HypothesisTransitionService(HypothesisTransitionRepository repository) {
    this.repository = repository;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The hypothesis is identified by the command's evidence or prediction
   * reference. A command carrying neither is rejected rather than guessed at.
   */
  @Override
  @Transactional
  public HypothesisTransitionResult transition(HypothesisTransitionCommand command) {
    return apply(resolveHypothesisId(command), command);
  }

  /** The HTTP and TASK-06-owned entry point, where the caller names the hypothesis. */
  @Transactional
  public HypothesisTransitionResult transition(
    UUID hypothesisId,
    HypothesisTransitionCommand command
  ) {
    return apply(hypothesisId, command);
  }

  // ---- the transition ----------------------------------------------------

  private HypothesisTransitionResult apply(
    UUID hypothesisId,
    HypothesisTransitionCommand command
  ) {
    Objects.requireNonNull(hypothesisId, "hypothesisId is required");
    if (command == null) throw TransitionErrors.invalid(
      "a transition command is required"
    );
    if (command.operationId() == null) throw TransitionErrors.invalid(
      "operationId (the idempotency key) is required"
    );
    if (command.reason() == null || command.reason().isBlank()) {
      throw TransitionErrors.invalid("a transition reason is required");
    }
    if (command.cause() == null) throw TransitionErrors.invalid(
      "a transition cause is required"
    );

    var stored = repository
      .lockById(hypothesisId)
      .orElseThrow(() -> TransitionErrors.notFound("hypothesis"));

    // Idempotency before anything else that could write: a replay is answered
    // from the recorded event and changes nothing (freeze §2.2).
    var recorded = repository.event(command.operationId());
    if (recorded.isPresent()) {
      return replay(recorded.get(), hypothesisId, command);
    }

    var previousStored = stored.payload().status();
    if (HypothesisLifecycle.isTerminal(previousStored)) {
      throw TransitionErrors.unprocessable(
        "The hypothesis was REJECTED by a met falsification condition; a decisive falsification is not revisited in place. State a new hypothesis in a new snapshot instead (EPISTEMIC_TYPES §2.3, EP-10)."
      );
    }

    if (
      command.expectedVersion() == null ||
      command.expectedVersion() != stored.version()
    ) {
      throw TransitionErrors.versionConflict(
        stored.version(),
        command.expectedVersion() == null ? -1L : command.expectedVersion()
      );
    }

    var reference = resolveReference(hypothesisId, stored, command);
    var evidence = repository.evidenceFor(hypothesisId);
    var snapshot = repository.snapshot(hypothesisId);
    var score = score(stored, snapshot, evidence);

    var previousProtocol = HypothesisLifecycle.protocol(previousStored);
    int previousScore = stored.confidence();
    int movement = Integer.compare(score.score(), previousScore);
    var decided = HypothesisLifecycle.nextStatus(
      new HypothesisLifecycle.Decision(
        previousStored,
        command.cause(),
        reference.stance(),
        command.verificationOutcome(),
        movement,
        standsAgainst(evidence)
      )
    );
    var nextProtocol = decided == null ? previousProtocol : decided;
    var nextStoredStatus = HypothesisLifecycle.storedStatus(
      previousStored,
      nextProtocol
    );

    var occurredAt = occurredAt(hypothesisId);
    long newVersion = stored.version() + 1;
    var updated = updatedPayload(
      stored.payload(),
      nextStoredStatus,
      score,
      occurredAt,
      command,
      reference
    );

    var moved = score.dimensions().isEmpty()
      ? List.<String>of()
      : movedDimensions(
        RubricReasonText.dimensions(stored.payload().confidenceReason()),
        score.dimensions()
      );
    if (moved.isEmpty() && score.score() != previousScore && score.isRubric()) {
      // The number moved but the previous breakdown was not recorded — a
      // pre-protocol payload whose confidenceReason is prose. NAME every
      // dimension rather than claim nothing moved: the whole basis was
      // re-derived, and freeze §4.1 invariant 1 forbids a moved number with no
      // dimension named.
      moved = score
        .dimensions()
        .stream()
        .map(ConfidenceDimension::dimension)
        .toList();
    }

    var event = new HypothesisEvent(
      command.operationId(),
      hypothesisId,
      HypothesisLifecycle.eventType(
        previousStored,
        decided,
        score.score() != previousScore,
        command.cause()
      ),
      previousScore,
      score.score(),
      TransitionEventText.append(
        command.reason(),
        new TransitionEventText.Facts(
          command.cause().name(),
          reference.reference(),
          stored.version(),
          newVersion,
          previousProtocol.name(),
          nextProtocol.name(),
          previousScore,
          score.score(),
          score.band() == null ? null : score.band().name(),
          score.rubricVersion(),
          score.method() == null ? null : score.method().name(),
          dimensions(score),
          moved
        )
      ),
      occurredAt,
      previousProtocol,
      nextProtocol
    );

    repository.append(event);
    if (
      !repository.update(
        hypothesisId,
        updated,
        score.score(),
        occurredAt,
        stored.version()
      )
    ) throw new IllegalStateException(
      "the hypothesis version moved under its own row lock"
    );

    return new HypothesisTransitionResult(
      command.operationId(),
      hypothesisId,
      true,
      stored.version(),
      newVersion,
      previousProtocol,
      nextProtocol,
      previousScore,
      score.score(),
      score.band(),
      score.rubricVersion(),
      event.id(),
      occurredAt
    );
  }

  // ---- identity, references and validation -------------------------------

  private UUID resolveHypothesisId(HypothesisTransitionCommand command) {
    if (command == null) throw TransitionErrors.invalid(
      "a transition command is required"
    );
    if (command.evidenceRef() != null) {
      return repository
        .evidence(command.evidenceRef())
        .map(StoredEvidence::hypothesisId)
        .orElseThrow(() -> TransitionErrors.notFound("evidence item"));
    }
    if (command.predictionRef() != null) {
      return repository
        .prediction(command.predictionRef())
        .map(StoredPrediction::hypothesisId)
        .orElseThrow(() -> TransitionErrors.notFound("prediction"));
    }
    throw TransitionErrors.invalid(
      "a transition command must reference an evidence item or a prediction, which is what identifies the hypothesis"
    );
  }

  /** The cause-specific reference a command carries, validated against its hypothesis. */
  private record Reference(
    String reference,
    UUID evidenceId,
    UUID predictionId,
    EvidenceStance stance
  ) {}

  private Reference resolveReference(
    UUID hypothesisId,
    StoredHypothesis stored,
    HypothesisTransitionCommand command
  ) {
    return switch (command.cause()) {
      case EVIDENCE_ADDED, EVIDENCE_CHANGED -> evidenceReference(
        hypothesisId,
        command,
        "EVIDENCE_ADDED and EVIDENCE_CHANGED"
      );
      case FALSIFICATION_OBSERVED -> {
        var conditions = stored.payload().falsificationConditions();
        if (conditions == null || conditions.isEmpty()) {
          throw TransitionErrors.unprocessable(
            "REJECTED requires a met falsification condition, and this hypothesis pre-registered none (STG-12, EPISTEMIC_TYPES §2.3)."
          );
        }
        yield evidenceReference(hypothesisId, command, "FALSIFICATION_OBSERVED");
      }
      case PREDICTION_VERIFIED -> {
        if (command.verificationOutcome() == null) {
          throw TransitionErrors.unprocessable(
            "PREDICTION_VERIFIED requires a verification outcome (CONFIRMED, PARTIAL, REJECTED or UNRESOLVED)."
          );
        }
        var prediction = prediction(hypothesisId, command);
        requireVerificationRecord(command, prediction);
        yield new Reference(
          prediction.id().toString(),
          null,
          prediction.id(),
          null
        );
      }
      case DEADLINE_PASSED -> {
        var prediction = prediction(hypothesisId, command);
        yield new Reference(
          prediction.id().toString(),
          null,
          prediction.id(),
          null
        );
      }
    };
  }

  private Reference evidenceReference(
    UUID hypothesisId,
    HypothesisTransitionCommand command,
    String cause
  ) {
    if (command.evidenceRef() == null) throw TransitionErrors.unprocessable(
      cause + " requires an evidence reference."
    );
    var evidence = repository
      .evidence(command.evidenceRef())
      .orElseThrow(() -> TransitionErrors.notFound("evidence item"));
    if (!evidence.hypothesisId().equals(hypothesisId)) {
      throw TransitionErrors.unprocessable(
        "The evidence item bears on a different hypothesis; a transition may only cite evidence of the hypothesis it moves."
      );
    }
    return new Reference(
      evidence.id().toString(),
      evidence.id(),
      null,
      evidence.stance()
    );
  }

  private StoredPrediction prediction(
    UUID hypothesisId,
    HypothesisTransitionCommand command
  ) {
    if (command.predictionRef() == null) throw TransitionErrors.unprocessable(
      command.cause() + " requires a prediction reference."
    );
    var prediction = repository
      .prediction(command.predictionRef())
      .orElseThrow(() -> TransitionErrors.notFound("prediction"));
    if (!prediction.hypothesisId().equals(hypothesisId)) {
      throw TransitionErrors.unprocessable(
        "The prediction belongs to a different hypothesis; a transition may only cite predictions of the hypothesis it moves."
      );
    }
    return prediction;
  }

  /**
   * The protocol basis of a verification-driven transition.
   *
   * <p>EPISTEMIC_TYPES §2.3 makes {@code CONFIRMED} require a verified prediction
   * snapshot, and §2.4 makes a prediction a record judged against reality rather
   * than against the analyst's confidence. So the command's outcome is only
   * accepted when the prediction row already carries that verification record —
   * its status and the timestamp V4 reserves — and nothing may assert
   * {@code CONFIRMED} merely by asking for it. A prediction the caller has not
   * actually verified is refused with 422, which is also the freeze §2.1 rule for
   * "CONFIRMED asserted with no verification record".
   */
  private static void requireVerificationRecord(
    HypothesisTransitionCommand command,
    StoredPrediction prediction
  ) {
    var outcome = command.verificationOutcome().name();
    if (!outcome.equals(prediction.status())) {
      throw TransitionErrors.unprocessable(
        "No verification record: the prediction is stored as " +
        prediction.status() +
        " but the command asserts " +
        outcome +
        ". Record the verification result first; a hypothesis is never confirmed by assertion (EPISTEMIC_TYPES §2.3/§2.4)."
      );
    }
    if (prediction.verifiedAt() == null) {
      throw TransitionErrors.unprocessable(
        "No verification record: the prediction carries no verification timestamp, so the outcome cannot be traced to a decision (freeze §6)."
      );
    }
  }

  // ---- deterministic confidence -----------------------------------------

  /**
   * The hypothesis-scope rubric score, recomputed from stored artifacts only.
   *
   * <p>When the producing snapshot is missing or unreadable — a legacy payload,
   * for instance — the scorer fails closed exactly as CF-06 prescribes: the score
   * is the previous number carried as advisory, the method is
   * {@code MODEL_JUDGMENT}, and there are no dimensions. The number therefore
   * never moves arbitrarily; it stops being rubric-derived and says so.
   */
  private ConfidenceScore score(
    StoredHypothesis stored,
    Optional<HypothesisSnapshot> snapshot,
    List<StoredEvidence> evidence
  ) {
    if (snapshot.isEmpty()) {
      return ConfidenceScore.failClosed(
        stored.confidence(),
        "RUBRIC " +
        ConfidenceRubric.VERSION +
        " | method=MODEL_JUDGMENT | the producing snapshot is missing or is not a readable protocol snapshot, so no rubric dimension can be derived (CF-06). The stored number is carried unchanged and is advisory."
      );
    }
    var computed = rubric.score(
      HypothesisScopeInputs.of(stored.payload(), snapshot.get(), evidence)
    );
    if (computed.isRubric()) return computed;
    // The snapshot read but is not scorable — a pre-SCH-01 row with no source
    // assessment, for instance. CF-06 says fail closed, and carrying the previous
    // number as advisory is the only fail-closed answer that does not silently
    // rewrite a stored judgment to zero.
    return ConfidenceScore.failClosed(stored.confidence(), computed.reason());
  }

  /**
   * Whether contradicting evidence still stands after this transition. The
   * referenced row is already stored by the caller, so the evidence set reflects
   * the command; this is what keeps a {@code CONFIRMED} prediction from promoting
   * a hypothesis that something contradicts (EPISTEMIC_TYPES §2.3).
   */
  private static boolean standsAgainst(List<StoredEvidence> evidence) {
    return evidence.stream().anyMatch(item ->
      item.stance() == EvidenceStance.CONTRADICTS
    );
  }

  private static List<TransitionEventText.Dimension> dimensions(
    ConfidenceScore score
  ) {
    var out = new ArrayList<TransitionEventText.Dimension>();
    for (ConfidenceDimension dimension : score.dimensions()) out.add(
      new TransitionEventText.Dimension(
        dimension.dimension(),
        dimension.level(),
        dimension.points()
      )
    );
    return List.copyOf(out);
  }

  /**
   * The dimensions whose level or points differ from the previous scoring, in the
   * new profile's order, followed by any dimension that disappeared. Empty when
   * the previous score was not rubric-derived: a {@code MODEL_JUDGMENT} number has
   * no dimensions to move.
   */
  private static List<String> movedDimensions(
    List<TransitionEventText.Dimension> previous,
    List<ConfidenceDimension> next
  ) {
    var before = new LinkedHashMap<String, TransitionEventText.Dimension>();
    for (var dimension : previous) before.put(dimension.id(), dimension);
    var moved = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (var dimension : next) {
      seen.add(dimension.dimension());
      var previousDimension = before.get(dimension.dimension());
      if (
        previousDimension == null ||
        previousDimension.level() != dimension.level() ||
        previousDimension.points() != dimension.points()
      ) moved.add(dimension.dimension());
    }
    for (var dimension : previous) if (!seen.contains(dimension.id())) moved.add(
      dimension.id()
    );
    return List.copyOf(moved);
  }

  // ---- the new payload and the timeline order ----------------------------

  private static Hypothesis updatedPayload(
    Hypothesis payload,
    HypothesisStatus status,
    ConfidenceScore score,
    Instant occurredAt,
    HypothesisTransitionCommand command,
    Reference reference
  ) {
    var supporting = payload.supportingEvidenceRefs();
    var contradicting = payload.contradictingEvidenceRefs();
    if (reference.evidenceId() != null) {
      if (command.cause() == HypothesisTransitionCause.FALSIFICATION_OBSERVED) {
        contradicting = withRef(contradicting, reference.evidenceId());
      } else if (reference.stance() == EvidenceStance.SUPPORTS) {
        supporting = withRef(supporting, reference.evidenceId());
      } else if (reference.stance() == EvidenceStance.CONTRADICTS) {
        contradicting = withRef(contradicting, reference.evidenceId());
      }
    }
    return new Hypothesis(
      payload.id(),
      payload.title(),
      payload.description(),
      status,
      score.reason(),
      payload.createdAt(),
      occurredAt,
      payload.type(),
      payload.statement(),
      payload.reasoning(),
      score.score(),
      payload.sourceRefs(),
      payload.supportingFactRefs(),
      supporting,
      contradicting,
      payload.assumptions(),
      payload.alternativeHypothesisRefs(),
      payload.falsificationConditions(),
      score.band()
    );
  }

  private static List<UUID> withRef(List<UUID> refs, UUID ref) {
    if (ref == null) return refs;
    var out = new ArrayList<>(refs == null ? List.<UUID>of() : refs);
    if (!out.contains(ref)) out.add(ref);
    return List.copyOf(out);
  }

  /**
   * A strictly increasing event timestamp.
   *
   * <p>{@code hypothesis_events} has no sequence column: the timeline is read with
   * {@code ORDER BY created_at}, and {@code created_at} is a {@code timestamptz}
   * with microsecond resolution. Two transitions inside one microsecond would then
   * have an ambiguous order, which would make "previous confidence traceable" a
   * matter of luck. Since the hypothesis row is already locked, one microsecond
   * beyond the newest event is the smallest step that keeps the order total and
   * leaves the existing read path correct.
   */
  private Instant occurredAt(UUID hypothesisId) {
    Instant now = Instant.now();
    var latest = repository.latestEventAt(hypothesisId);
    if (latest.isEmpty() || now.isAfter(latest.get())) return now;
    return latest.get().plusNanos(1_000);
  }

  // ---- idempotent replay -------------------------------------------------

  /**
   * Answers a replay from the recorded event (freeze §2.2): the same
   * {@code operationId} with the same request fingerprint returns the originally
   * recorded result with {@code applied = false} and appends nothing; the same key
   * with a different request is a conflict, never a silent second application.
   *
   * <p>The fingerprint is everything that identifies the request: the hypothesis,
   * the cause, the referenced artifact, the reason, and — for a verification — the
   * asserted outcome. Those are exactly the inputs that determine the recorded
   * outcome, so a command that reproduces them is a replay and a command that
   * differs is not.
   */
  private HypothesisTransitionResult replay(
    HypothesisEvent event,
    UUID hypothesisId,
    HypothesisTransitionCommand command
  ) {
    var facts = TransitionEventText
      .read(event.reason())
      .orElseThrow(() -> TransitionErrors.idempotencyMismatch(command.operationId()));
    boolean sameRequest =
      event.hypothesisId().equals(hypothesisId) &&
      facts.cause().equals(command.cause().name()) &&
      Objects.equals(recordedReference(facts.ref()), referenceOf(command)) &&
      TransitionEventText
        .userReason(event.reason())
        .equals(TransitionEventText.canonicalReason(command.reason())) &&
      sameVerificationOutcome(command);
    if (!sameRequest) throw TransitionErrors.idempotencyMismatch(
      command.operationId()
    );
    return new HypothesisTransitionResult(
      command.operationId(),
      hypothesisId,
      false,
      facts.previousVersion(),
      facts.version(),
      status(facts.previousStatus()),
      status(facts.status()),
      facts.previousScore(),
      facts.score(),
      facts.band() == null ? null : ConfidenceBand.valueOf(facts.band()),
      facts.rubric(),
      event.id(),
      event.createdAt()
    );
  }

  /**
   * The reference the recorded event actually cites, computed the way
   * {@link #resolveReference} builds it rather than the way a caller happened to
   * populate the command.
   *
   * <p>The two differ for a verification that also names the evidence it was
   * decided against: {@code resolveReference} records the <em>prediction</em>, so a
   * fingerprint that preferred {@code evidenceRef} would report a legitimate replay
   * of that command as an idempotency mismatch and never return the recorded
   * result.
   */
  private static String referenceOf(HypothesisTransitionCommand command) {
    UUID reference = switch (command.cause()) {
      case PREDICTION_VERIFIED, DEADLINE_PASSED -> command.predictionRef();
      case EVIDENCE_ADDED, EVIDENCE_CHANGED, FALSIFICATION_OBSERVED -> command.evidenceRef() !=
        null
        ? command.evidenceRef()
        : command.predictionRef();
    };
    return reference == null ? TransitionEventText.NONE : reference.toString();
  }

  /**
   * Whether the asserted verification outcome matches the one the recorded
   * transition was derived from.
   *
   * <p>The outcome is part of a request's identity — the same key with a different
   * asserted result is a different request (freeze §2.2) — but the event trailer
   * records the <em>resulting status</em>, not the raw outcome, so it cannot carry
   * the comparison. The stored prediction can: a prediction is decided exactly once
   * and its status is that one outcome, so it is the durable record of what the
   * original request asserted. A non-verification cause has no outcome to compare.
   */
  private boolean sameVerificationOutcome(HypothesisTransitionCommand command) {
    if (command.cause() != HypothesisTransitionCause.PREDICTION_VERIFIED) {
      return true;
    }
    if (command.predictionRef() == null || command.verificationOutcome() == null) {
      return false;
    }
    return repository
      .prediction(command.predictionRef())
      .map(prediction ->
        command.verificationOutcome().name().equals(prediction.status())
      )
      .orElse(false);
  }

  private static String recordedReference(String stored) {
    return stored == null ? TransitionEventText.NONE : stored;
  }

  private static HypothesisStatus status(String stored) {
    if (stored == null) throw new IllegalStateException(
      "the recorded transition event has no status"
    );
    return HypothesisStatus.valueOf(stored);
  }
}
