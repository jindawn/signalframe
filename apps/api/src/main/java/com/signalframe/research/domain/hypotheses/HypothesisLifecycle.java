package com.signalframe.research.domain.hypotheses;

import com.signalframe.contract.HypothesisStatus;
import com.signalframe.contract.HypothesisTransitionCause;
import com.signalframe.contract.VerificationOutcome;
import java.util.List;
import java.util.Objects;

/**
 * The deterministic status rules of the hypothesis lifecycle (Wave 2B).
 *
 * <p>Pure and framework-free: {@link #nextStatus(Decision)} is a function of the
 * command's cause, the referenced artifact's stance or verification outcome, the
 * movement of the deterministic rubric's band, and whether a contradiction still
 * stands. There is no clock, no model, no network and no randomness, so the same
 * stored state and the same command always produce the same status
 * (CF-02/CF-04).
 *
 * <h2>The decision table</h2>
 * <pre>
 * FALSIFICATION_OBSERVED              -> REJECTED
 * DEADLINE_PASSED                     -> UNRESOLVED
 * PREDICTION_VERIFIED  CONFIRMED      -> CONFIRMED, unless a contradiction still
 *                                        stands, in which case STRENGTHENING
 *                      REJECTED       -> WEAKENING
 *                      PARTIAL        -> band rose: STRENGTHENING;
 *                                        band fell: WEAKENING;
 *                                        band flat: no status movement
 *                      UNRESOLVED     -> UNRESOLVED
 * EVIDENCE_*           CONTRADICTS    -> WEAKENING
 *                      SUPPORTS       -> STRENGTHENING
 *                      NEUTRAL        -> band movement, else no status movement
 * </pre>
 *
 * <p>Two properties of that table are deliberate and normative:
 *
 * <ol>
 *   <li><b>A score never decides by itself.</b> No branch reaches {@code CONFIRMED}
 *       except through a persisted verification outcome, so a high band can never
 *       promote a hypothesis on its own (EP-11, freeze §4.1 invariant 6).
 *   <li><b>A {@code CONFIRMED} prediction is an input, not a verdict.</b> It sets
 *       the hypothesis to {@code CONFIRMED} only when nothing contradicts it; with
 *       a standing contradiction the honest status is {@code STRENGTHENING}, and
 *       {@code CONFIRMED} still never means "true" (EPISTEMIC_TYPES §2.3).
 * </ol>
 *
 * <h2>Terminal state</h2>
 * {@code REJECTED} is terminal: EPISTEMIC_TYPES §2.3 defines it as "a
 * falsification condition was met", which is decisive for the hypothesis as
 * stated, and EP-10 says a differently stated claim belongs to a new snapshot
 * rather than to an in-place rewrite. {@code CONFIRMED} is explicitly <em>not</em>
 * terminal (§2.4: a hypothesis whose prediction later fails is weakened), so
 * contradicting evidence or a failed prediction moves it to {@code WEAKENING}.
 *
 * <h2>Legacy stored values</h2>
 * {@code SUPPORTED}/{@code CHALLENGED}/{@code ARCHIVED} remain readable and are
 * mapped for decision purposes only (EPISTEMIC_TYPES §6). They are never written
 * back: see {@link #storedStatus(HypothesisStatus, HypothesisStatus)}.
 */
public final class HypothesisLifecycle {

  /** The statuses a Wave 2B transition may write, in protocol order. */
  public static final List<HypothesisStatus> PROTOCOL_STATUSES = List.of(
    HypothesisStatus.OPEN,
    HypothesisStatus.STRENGTHENING,
    HypothesisStatus.WEAKENING,
    HypothesisStatus.CONFIRMED,
    HypothesisStatus.REJECTED,
    HypothesisStatus.UNRESOLVED
  );

  /**
   * Statuses that only stored payloads carry. They are read and interpreted, and
   * no transition ever writes one (EPISTEMIC_TYPES §6).
   */
  public static final List<HypothesisStatus> LEGACY_STATUSES = List.of(
    HypothesisStatus.SUPPORTED,
    HypothesisStatus.CHALLENGED,
    HypothesisStatus.ARCHIVED
  );

  private HypothesisLifecycle() {}

  /**
   * The protocol reading of a stored status (EPISTEMIC_TYPES §6):
   * {@code SUPPORTED→STRENGTHENING}, {@code CHALLENGED→WEAKENING},
   * {@code ARCHIVED→UNRESOLVED}. A protocol status maps to itself. A null status
   * is read as {@code OPEN}, the value every new snapshot starts from.
   */
  public static HypothesisStatus protocol(HypothesisStatus stored) {
    if (stored == null) return HypothesisStatus.OPEN;
    return switch (stored) {
      case SUPPORTED -> HypothesisStatus.STRENGTHENING;
      case CHALLENGED -> HypothesisStatus.WEAKENING;
      case ARCHIVED -> HypothesisStatus.UNRESOLVED;
      default -> stored;
    };
  }

  /** True when the stored value is one of the pre-Wave-2B statuses. */
  public static boolean isLegacy(HypothesisStatus stored) {
    return stored != null && LEGACY_STATUSES.contains(stored);
  }

  /** {@code REJECTED} is terminal; every other protocol status may move again. */
  public static boolean isTerminal(HypothesisStatus stored) {
    return protocol(stored) == HypothesisStatus.REJECTED;
  }

  /**
   * The status to <em>store</em> for {@code next}: the previous value verbatim
   * while the protocol reading has not moved, so a stored legacy value is never
   * rewritten, and {@code next} as soon as the status genuinely moves. A missing
   * previous status (a legacy payload without one) is written as {@code next}
   * rather than preserved as null.
   */
  public static HypothesisStatus storedStatus(
    HypothesisStatus previousStored,
    HypothesisStatus next
  ) {
    if (previousStored == null) return next;
    return protocol(previousStored) == next ? previousStored : next;
  }

  /**
   * The status a transition produces, or {@code null} for "no status movement" —
   * which the caller reads as {@link #protocol(HypothesisStatus)} of the previous
   * value.
   */
  public static HypothesisStatus nextStatus(Decision decision) {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decision.cause(), "transition cause");
    if (isTerminal(decision.previous())) throw new IllegalArgumentException(
      "a REJECTED hypothesis is terminal and has no next status"
    );
    return switch (decision.cause()) {
      case FALSIFICATION_OBSERVED -> HypothesisStatus.REJECTED;
      case DEADLINE_PASSED -> HypothesisStatus.UNRESOLVED;
      case PREDICTION_VERIFIED -> verificationStatus(decision);
      case EVIDENCE_ADDED, EVIDENCE_CHANGED -> evidenceStatus(decision);
    };
  }

  /**
   * The timeline event type for a transition. A status transition is reported as
   * {@code STATUS_CHANGED} so the timeline can show "the hypothesis moved" rather
   * than only "the number moved"; a confidence-only movement keeps
   * {@code CONFIDENCE_CHANGED}; otherwise the event names its cause.
   *
   * @param nextStatus the status the decision produced, or {@code null} when the
   *     decision found no movement — which is not a status change
   */
  public static String eventType(
    HypothesisStatus previousStored,
    HypothesisStatus nextStatus,
    boolean confidenceMoved,
    HypothesisTransitionCause cause
  ) {
    Objects.requireNonNull(cause, "transition cause");
    if (nextStatus != null && protocol(previousStored) != nextStatus) {
      return "STATUS_CHANGED";
    }
    if (confidenceMoved) return "CONFIDENCE_CHANGED";
    return switch (cause) {
      case EVIDENCE_ADDED, EVIDENCE_CHANGED -> "EVIDENCE_ADDED";
      case PREDICTION_VERIFIED -> "PREDICTION_VERIFIED";
      // Both causes always move the status, so this arm is unreachable in
      // practice; STATUS_CHANGED is still the only honest label for them.
      case FALSIFICATION_OBSERVED, DEADLINE_PASSED -> "STATUS_CHANGED";
    };
  }

  private static HypothesisStatus verificationStatus(Decision decision) {
    var outcome = decision.verification();
    if (outcome == null) throw new IllegalArgumentException(
      "PREDICTION_VERIFIED requires a verification outcome"
    );
    return switch (outcome) {
      case CONFIRMED -> decision.contradictionStands()
        ? HypothesisStatus.STRENGTHENING
        : HypothesisStatus.CONFIRMED;
      // A failed prediction is evidence against the hypothesis, not a met
      // falsification condition: REJECTED is reserved for that separate path
      // (EPISTEMIC_TYPES §2.3/§2.4).
      case REJECTED -> HypothesisStatus.WEAKENING;
      case PARTIAL -> movement(decision);
      case UNRESOLVED -> HypothesisStatus.UNRESOLVED;
    };
  }

  private static HypothesisStatus evidenceStatus(Decision decision) {
    var stance = decision.stance();
    if (stance == null) return movement(decision);
    return switch (stance) {
      case CONTRADICTS -> HypothesisStatus.WEAKENING;
      case SUPPORTS -> HypothesisStatus.STRENGTHENING;
      case NEUTRAL -> movement(decision);
    };
  }

  /** Band-led movement, or {@code null} when the band did not move. */
  private static HypothesisStatus movement(Decision decision) {
    if (decision.bandMovement() > 0) return HypothesisStatus.STRENGTHENING;
    if (decision.bandMovement() < 0) return HypothesisStatus.WEAKENING;
    return null;
  }

  /**
   * The frozen inputs of one transition decision. Every component is stored data
   * or a validated command field; none of it is produced by a model.
   *
   * @param previous            the stored status, protocol or legacy
   * @param cause               why the transition was submitted
   * @param stance              the referenced evidence's stance, else null
   * @param verification        the verification outcome, else null
   * @param bandMovement        sign of the rubric band movement (-1, 0, +1)
   * @param contradictionStands whether contradicting evidence still stands
   */
  public record Decision(
    HypothesisStatus previous,
    HypothesisTransitionCause cause,
    EvidenceStance stance,
    VerificationOutcome verification,
    int bandMovement,
    boolean contradictionStands
  ) {}
}
