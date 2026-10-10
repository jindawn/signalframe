package com.signalframe.research.domain.evidence;

import com.signalframe.contract.VerificationOutcome;
import com.signalframe.shared.ApplicationException;
import java.time.Instant;

/**
 * Validation and vocabulary rules for a prediction (TASK-07).
 *
 * <p>A prediction is a dated, checkable judgement about a <em>future observable</em>
 * derived from a hypothesis (EPISTEMIC_TYPES §2.4, ANALYSIS_PROTOCOL STG-14). It is
 * created {@code OPEN} and is resolved only by a verification record. Its text is
 * immutable: an outcome is appended, and a failed prediction is never rewritten to
 * look better in hindsight.
 */
public final class PredictionRules {

  /** The only permitted initial status (SCH-07). */
  public static final String OPEN = "OPEN";

  /** Every resolved status, matching {@link VerificationOutcome} one for one. */
  public static final java.util.Set<String> RESOLVED = java.util.Set.of(
    "CONFIRMED",
    "REJECTED",
    "PARTIAL",
    "UNRESOLVED"
  );

  /** {@code Prediction.type} is fixed by the contract pattern, never model-chosen. */
  public static final String PREDICTION_TYPE = "PREDICTION";

  public static final int MAX_TEXT = 20000;

  private PredictionRules() {}

  /** True when the prediction has not been decided against reality yet. */
  public static boolean isOpen(String status) {
    return OPEN.equals(status);
  }

  /**
   * A prediction must be time bounded.
   *
   * <p>STG-14.1: {@code expectedBy} must be in the future relative to creation. The
   * boundary is strict — an instant equal to "now" is not in the future, so it is
   * rejected rather than rounded forward.
   *
   * <p>This is an {@code UNPROCESSABLE_TRANSITION} (422), not a structural 400: the
   * request is well formed but describes a prediction that could never be checked.
   */
  public static Instant requireFuture(Instant expectedBy, Instant now) {
    if (expectedBy == null) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "expectedBy is required"
      );
    }
    if (!expectedBy.isAfter(now)) {
      throw new ApplicationException(
        422,
        "UNPROCESSABLE_TRANSITION",
        "expectedBy must be in the future relative to creation (STG-14.1)"
      );
    }
    return expectedBy;
  }

  /**
   * Verification criteria must be able to tell confirmation, partial confirmation
   * and rejection apart (STG-14.2).
   *
   * <p>Whether a free-text criterion is genuinely discriminating cannot be decided
   * mechanically. The deterministic part of the rule that <em>can</em> be enforced
   * is that the criteria must add distinguishing content: restating the prediction
   * or its observable verbatim is not a verification criterion. Anything stronger
   * would be inventing policy the protocol does not state, so the remaining
   * judgement stays with the reviewer.
   */
  public static String requireDistinguishableCriteria(
    String verificationCriteria,
    String statement,
    String observable
  ) {
    String criteria = requireText(verificationCriteria, "verificationCriteria");
    if (
      normalize(criteria).equals(normalize(statement)) ||
      normalize(criteria).equals(normalize(observable))
    ) {
      throw new ApplicationException(
        422,
        "UNPROCESSABLE_TRANSITION",
        "verificationCriteria must distinguish confirmation from rejection and " +
        "must not simply restate the prediction or its observable (STG-14.2)"
      );
    }
    return criteria;
  }

  /** Outcome vocabulary of a verification: exactly the frozen {@link VerificationOutcome}. */
  public static VerificationOutcome requireOutcome(VerificationOutcome result) {
    if (result == null) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "result is required and must be CONFIRMED, PARTIAL, REJECTED or UNRESOLVED"
      );
    }
    return result;
  }

  /** A verification must record why the outcome was decided (§6, EPISTEMIC_TYPES §4). */
  public static String requireReason(String reason) {
    return requireText(reason, "reason");
  }

  /** A required, bounded, non-blank free-text field of the frozen contract. */
  public static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        field + " is required and must not be blank"
      );
    }
    String trimmed = value.trim();
    if (trimmed.length() > MAX_TEXT) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        field + " must be at most " + MAX_TEXT + " characters"
      );
    }
    return trimmed;
  }

  /** Whitespace- and case-insensitive comparison, so formatting is not the discriminator. */
  static String normalize(String value) {
    return value == null
      ? ""
      : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
  }
}
