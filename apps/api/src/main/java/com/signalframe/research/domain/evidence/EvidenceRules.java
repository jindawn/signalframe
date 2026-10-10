package com.signalframe.research.domain.evidence;

import com.signalframe.shared.ApplicationException;
import java.util.Set;

/**
 * Validation rules for an evidence item (TASK-07).
 *
 * <p>Evidence is <em>already existing</em> material — a document, record,
 * statement or dataset — that bears on a hypothesis with a source, a stance and a
 * strength (EPISTEMIC_TYPES §5.2). It is never a corroborating signal: a signal is
 * a hypothetical future observable and has no stance because nothing has been
 * observed yet.
 *
 * <p>These rules are pure and Spring-free so they can be exercised without a
 * container. The HTTP layer repeats the structural half through bean validation on
 * the generated request record; that is deliberate defence in depth, not a second
 * definition of the rule. The normative requirements are the freeze §6 ("Evidence
 * writes verify the source FK, the stance, the strength range and a non-empty
 * reason") and EPISTEMIC_TYPES §4.
 */
public final class EvidenceRules {

  /** The only permitted stances, exactly as the frozen {@code Evidence} schema declares them. */
  public static final Set<String> STANCES = Set.of(
    "SUPPORTS",
    "CONTRADICTS",
    "NEUTRAL"
  );

  /** Frozen contract bound on {@code strength} and on every free-text field. */
  public static final int MIN_STRENGTH = 0;

  public static final int MAX_STRENGTH = 100;
  public static final int MAX_TEXT = 20000;

  private EvidenceRules() {}

  /**
   * The stance must be one of {@code SUPPORTS}, {@code CONTRADICTS}, {@code NEUTRAL}.
   *
   * <p>A stance is a claim about the <em>relationship</em> between an existing item
   * and the hypothesis, never about truth: evidence can contradict a hypothesis
   * without making it false.
   */
  public static String requireStance(String stance) {
    if (stance == null || !STANCES.contains(stance)) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "stance must be one of SUPPORTS, CONTRADICTS or NEUTRAL"
      );
    }
    return stance;
  }

  /** Strength is an ordinal 0–100 judgement, never a probability (CONFIDENCE_MODEL §1). */
  public static int requireStrength(Integer strength) {
    if (strength == null) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "strength is required"
      );
    }
    if (strength < MIN_STRENGTH || strength > MAX_STRENGTH) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "strength must be between " +
        MIN_STRENGTH +
        " and " +
        MAX_STRENGTH +
        " but was " +
        strength
      );
    }
    return strength;
  }

  /**
   * A non-empty reason is mandatory.
   *
   * <p>PR-13: confidence and references travel together. An evidence item that moves
   * a hypothesis must be able to say why, in text a reviewer can audit.
   */
  public static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "reason is required and must not be blank"
      );
    }
    String trimmed = reason.trim();
    if (trimmed.length() > MAX_TEXT) {
      throw new ApplicationException(
        400,
        "INVALID_REQUEST",
        "reason must be at most " + MAX_TEXT + " characters"
      );
    }
    return trimmed;
  }
}
