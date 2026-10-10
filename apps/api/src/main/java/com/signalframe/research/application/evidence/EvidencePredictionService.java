package com.signalframe.research.application.evidence;

import com.signalframe.contract.DuePredictions;
import com.signalframe.contract.Evidence;
import com.signalframe.contract.EvidenceCreateRequest;
import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.Prediction;
import com.signalframe.contract.PredictionCreateRequest;
import com.signalframe.contract.PredictionVerificationCommand;
import com.signalframe.contract.PredictionVerificationResult;
import com.signalframe.contract.VerificationOutcome;
import com.signalframe.research.domain.evidence.EvidenceRepository;
import com.signalframe.research.domain.evidence.EvidenceRules;
import com.signalframe.research.domain.evidence.PredictionRepository;
import com.signalframe.research.domain.evidence.PredictionRules;
import com.signalframe.research.domain.evidence.ResearchReferences;
import com.signalframe.research.domain.hypotheses.HypothesisTransitions;
import com.signalframe.shared.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for sourced evidence and verifiable dated predictions (TASK-07).
 *
 * <p>Three properties govern every method here.
 *
 * <ol>
 *   <li><b>Evidence is sourced and unchanged.</b> A write verifies the hypothesis,
 *       the source foreign key and (when given) the analysis, then records the item.
 *       Nothing is updated in place.
 *   <li><b>A prediction is a judgement, not a result.</b> It is created {@code OPEN}
 *       with a deadline, an observable and criteria; its text is never rewritten by a
 *       later verification, which records an outcome instead.
 *   <li><b>No automatic truth.</b> A {@code CONFIRMED} verification does not confirm a
 *       hypothesis. It is reported to TASK-06's port, which decides the transition; a
 *       passed deadline is reported by {@link #duePredictions()} and never turned into
 *       a failure automatically.
 * </ol>
 *
 * <p>Writes are transactional and the hypothesis notification happens inside the same
 * transaction, so evidence or a verification can never be stored without the
 * transition that explains it (freeze §4.2).
 */
@Service
public class EvidencePredictionService {

  /** The repository list endpoints cap at 100 recent entries; the due query follows suit. */
  static final int DUE_LIMIT = 100;

  /** Namespace for the deterministic verification id (see {@link #verificationId}). */
  private static final String VERIFICATION_NAMESPACE =
    "signalframe-prediction-verification:";

  private final EvidenceRepository evidenceRepository;
  private final PredictionRepository predictionRepository;
  private final ResearchReferences references;
  private final HypothesisTransitionGateway transitions;

  public EvidencePredictionService(
    EvidenceRepository evidenceRepository,
    PredictionRepository predictionRepository,
    ResearchReferences references,
    HypothesisTransitionGateway transitions
  ) {
    this.evidenceRepository = evidenceRepository;
    this.predictionRepository = predictionRepository;
    this.references = references;
    this.transitions = transitions;
  }

  /**
   * Records one sourced evidence item against a hypothesis and notifies the engine.
   *
   * <p>Order matters: every structural check and every foreign key is verified
   * <em>before</em> the row is written and before any transition is attempted (freeze
   * §6). If the transition fails, the transaction rolls back and no evidence exists.
   */
  @Transactional
  public Evidence addEvidence(
    UUID hypothesisId,
    EvidenceCreateRequest request
  ) {
    Objects.requireNonNull(hypothesisId, "hypothesisId");
    Objects.requireNonNull(request, "request");

    if (!references.hypothesisExists(hypothesisId)) {
      throw ApplicationException.missing();
    }
    String stance = EvidenceRules.requireStance(request.stance());
    int strength = EvidenceRules.requireStrength(request.strength());
    String reason = EvidenceRules.requireReason(request.reason());

    if (request.sourceId() == null || !references.sourceExists(request.sourceId())) {
      throw ApplicationException.missing();
    }
    UUID analysisId = request.analysisId();
    if (analysisId != null && !references.analysisExists(analysisId)) {
      throw ApplicationException.missing();
    }

    // PR-09: a reference that cannot be resolved is a validation failure. The scope is
    // the analysis the evidence is linked to, defaulting to the hypothesis's own
    // snapshot (PR-01).
    List<UUID> factRefs = copy(request.factRefs());
    if (!factRefs.isEmpty()) {
      UUID scope = analysisId != null
        ? analysisId
        : references.hypothesisAnalysisId(hypothesisId);
      requireResolvableRefs(factRefs, references.factIds(scope), "factRefs");
    }

    var created = new Evidence(
      UUID.randomUUID(),
      hypothesisId,
      stance,
      strength,
      request.sourceId(),
      reason,
      Instant.now(),
      factRefs,
      analysisId
    );
    evidenceRepository.save(created);

    transitions.transition(
      HypothesisTransitions.evidenceAdded(
        UUID.randomUUID(),
        hypothesisId,
        references.hypothesisVersion(hypothesisId),
        created.id(),
        reason
      )
    );
    return created;
  }

  /**
   * Creates a dated, checkable prediction from a hypothesis.
   *
   * <p>The prediction starts {@code OPEN} and is never authorable in another state:
   * an outcome must come from a verification record (STG-14, SCH-07).
   */
  @Transactional
  public Prediction createPrediction(
    UUID hypothesisId,
    PredictionCreateRequest request
  ) {
    Objects.requireNonNull(hypothesisId, "hypothesisId");
    Objects.requireNonNull(request, "request");

    if (!references.hypothesisExists(hypothesisId)) {
      throw ApplicationException.missing();
    }
    String statement = PredictionRules.requireText(
      request.statement(),
      "statement"
    );
    String observable = PredictionRules.requireText(
      request.observable(),
      "observable"
    );
    String criteria = PredictionRules.requireDistinguishableCriteria(
      request.verificationCriteria(),
      statement,
      observable
    );
    String whereToCheck = PredictionRules.requireText(
      request.whereToCheck(),
      "whereToCheck"
    );
    Instant expectedBy = PredictionRules.requireFuture(
      request.expectedBy(),
      Instant.now()
    );

    List<UUID> basisFactRefs = copy(request.basisFactRefs());
    if (!basisFactRefs.isEmpty()) {
      requireResolvableRefs(
        basisFactRefs,
        references.factIds(references.hypothesisAnalysisId(hypothesisId)),
        "basisFactRefs"
      );
    }

    return predictionRepository.save(
      new Prediction(
        UUID.randomUUID(),
        hypothesisId,
        statement,
        PredictionRules.PREDICTION_TYPE,
        expectedBy,
        PredictionRules.OPEN,
        criteria,
        observable,
        whereToCheck,
        basisFactRefs
      )
    );
  }

  /**
   * Predictions that are still {@code OPEN} with a passed deadline.
   *
   * <p>Read-only. A passed deadline is <em>not</em> a failure and does not resolve the
   * prediction: the deadline passing is an observation, and deciding that the window
   * closed with no data — {@code UNRESOLVED} — is an explicit action with its own
   * reason (freeze §6).
   */
  @Transactional(readOnly = true)
  public DuePredictions duePredictions() {
    Instant asOf = Instant.now();
    return new DuePredictions(
      asOf,
      predictionRepository.dueOpen(asOf, DUE_LIMIT)
    );
  }

  /**
   * Records a verification outcome against reality and reports it to the engine.
   *
   * <p>Verification is idempotent and the {@code operationId} is its idempotency key
   * (freeze §2.2). The port is the single authority for whether a command is a first
   * application or a replay, because it owns the durable idempotency record; this
   * method adds the prediction-side guard that a decided prediction is never decided
   * twice.
   *
   * <p>Replay (same {@code operationId}, same request): the port returns the recorded
   * result with {@code applied = false}, nothing is written, and the same
   * {@code verificationId} and {@code hypothesisTransition} come back. A <em>new</em>
   * attempt against an already-decided prediction is rejected with {@code 422} and the
   * port's just-applied transition rolls back with this transaction, so the prediction
   * is decided exactly once.
   */
  @Transactional
  public PredictionVerificationResult verifyPrediction(
    UUID predictionId,
    PredictionVerificationCommand command
  ) {
    Objects.requireNonNull(predictionId, "predictionId");
    Objects.requireNonNull(command, "command");

    Prediction prediction = predictionRepository
      .findPrediction(predictionId)
      .orElseThrow(ApplicationException::missing);

    if (command.operationId() == null) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "operationId is required: it is the idempotency key of a verification"
      );
    }
    var outcome = PredictionRules.requireOutcome(command.result());
    String reason = PredictionRules.requireReason(command.reason());
    if (
      command.evidenceRef() != null &&
      !references.evidenceExists(command.evidenceRef())
    ) {
      throw ApplicationException.missing();
    }

    Instant verifiedAt = command.verifiedAt() != null
      ? command.verifiedAt()
      : Instant.now();
    boolean alreadyDecided = !PredictionRules.isOpen(prediction.status());

    var transition = transitions.transition(
      predictionVerifiedCommand(
        command.operationId(),
        prediction.hypothesisId(),
        references.hypothesisVersion(prediction.hypothesisId()),
        predictionId,
        outcome,
        command.evidenceRef(),
        reason
      )
    );
    boolean applied = Boolean.TRUE.equals(transition.applied());

    if (applied) {
      if (alreadyDecided) {
        // A second, genuinely new verification of a decided prediction. The transition
        // just applied inside this transaction, so throwing here rolls it back too.
        throw new ApplicationException(
          422,
          "UNPROCESSABLE_TRANSITION",
          "the prediction already has an outcome; a verification is recorded once " +
          "and is never rewritten (STG-14.3)"
        );
      }
      predictionRepository
        .resolve(predictionId, outcome.name(), verifiedAt)
        .orElseThrow(() ->
          new ApplicationException(
            409,
            "VERSION_CONFLICT",
            "the prediction was decided concurrently; re-read it and retry"
          )
        );
    } else if (!alreadyDecided) {
      // The port reported a replay but the prediction is still open, which means the
      // original application did not commit as a whole. Never guess a state.
      throw new ApplicationException(
        409,
        "VERSION_CONFLICT",
        "the transition was reported as a replay but the prediction is still open; " +
        "re-read it and retry"
      );
    }

    Prediction stored = predictionRepository
      .findPrediction(predictionId)
      .orElseThrow(ApplicationException::missing);
    return new PredictionVerificationResult(
      stored,
      applied,
      verificationId(predictionId, command.operationId()),
      transition
    );
  }

  /**
   * A verification id that is stable across replays.
   *
   * <p>Freeze §2.2 requires a replay to return "the same {@code verificationId}". The
   * frozen schema has no verification table, so rather than inventing a second store
   * the id is derived deterministically from the two things that identify the attempt:
   * the prediction and the idempotency key. The same replay therefore yields the same
   * id, and a different attempt yields a different one.
   */
  static UUID verificationId(UUID predictionId, UUID operationId) {
    return UUID.nameUUIDFromBytes(
      (VERIFICATION_NAMESPACE + predictionId + ":" + operationId).getBytes(
        StandardCharsets.UTF_8
      )
    );
  }

  /**
   * Builds the frozen {@code PREDICTION_VERIFIED} command, including the optional
   * evidence citation when the verification names one.
   *
   * <p>The canonical factory is {@code HypothesisTransitions.predictionVerified} and is
   * used wherever the command carries only the prediction reference. It cannot carry a
   * citation, because it passes {@code null} for {@code evidenceRef}. The frozen
   * command record itself declares {@code evidenceRef} independently nullable of the
   * cause, and requirement 6 is that a verification records its reason, its time and
   * its evidence reference. This method therefore honours the <em>same guards</em> as
   * the shared factory and produces the same shape, plus the citation. No shared
   * signature is changed and no second command vocabulary is introduced.
   */
  private static HypothesisTransitionCommand predictionVerifiedCommand(
    UUID operationId,
    UUID hypothesisId,
    long expectedVersion,
    UUID predictionId,
    VerificationOutcome outcome,
    UUID evidenceRef,
    String reason
  ) {
    Objects.requireNonNull(operationId, "operationId (idempotency key) is required");
    Objects.requireNonNull(hypothesisId, "hypothesisId is required");
    Objects.requireNonNull(outcome, "verification outcome is required");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("a transition reason is required");
    }
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
    return new HypothesisTransitionCommand(
      operationId,
      expectedVersion,
      HypothesisTransitionCause.PREDICTION_VERIFIED,
      reason,
      evidenceRef,
      predictionId,
      outcome
    );
  }

  private static void requireResolvableRefs(
    List<UUID> refs,
    java.util.Set<UUID> known,
    String field
  ) {
    List<UUID> unresolved = refs
      .stream()
      .filter(ref -> ref == null || !known.contains(ref))
      .distinct()
      .toList();
    if (!unresolved.isEmpty()) {
      throw new ApplicationException(
        422,
        "UNPROCESSABLE_TRANSITION",
        field +
        " must resolve inside the same analysis snapshot (PR-09); unresolved: " +
        unresolved
      );
    }
  }

  private static List<UUID> copy(List<UUID> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
