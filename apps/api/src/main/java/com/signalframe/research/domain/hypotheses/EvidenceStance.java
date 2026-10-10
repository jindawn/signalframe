package com.signalframe.research.domain.hypotheses;

/**
 * The stance an evidence item takes toward a hypothesis (EPISTEMIC_TYPES §5.2).
 *
 * <p>Evidence is <em>already existing</em> material: a document, record, statement
 * or dataset that supports or contradicts the hypothesis. It is never a
 * corroborating signal, which is a hypothetical future observable and carries no
 * stance.
 *
 * <p>The canonical contract declares {@code Evidence.stance} as an inline enum, so
 * the generated field is a {@code String} with a pattern. This enum is the
 * research module's typed reading of that same vocabulary — not a second DTO: it
 * is never serialized and never persisted, and {@link #parse(String)} is the only
 * way a stored value becomes one.
 */
public enum EvidenceStance {
  SUPPORTS,
  CONTRADICTS,
  NEUTRAL;

  /** The stored contract value, or {@code null} when it is absent or unknown. */
  public static EvidenceStance parse(String value) {
    if (value == null) return null;
    for (var stance : values()) if (stance.name().equals(value)) return stance;
    return null;
  }
}
