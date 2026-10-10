package com.signalframe.shared.confidence;

import com.signalframe.contract.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical rendering and reading of a falsification condition.
 *
 * <p>STG-12 makes three parts mandatory for every condition: the observable, the
 * comparison and the decision boundary. SCH-06 nests a falsification condition
 * as a {@link Statement}, which has no structured fields for them, so the three
 * parts travel inside the statement's {@code reasoning} in a fixed,
 * machine-parsable trailer:
 *
 * <pre>{@code <text> | protocol: observable=...; comparison=...; decisionBoundary=...}</pre>
 *
 * <p>This is a representation gap of the frozen schema, not a new claim: the
 * trailer is a rendering of values the producing stage already validated, and it
 * is deterministic, so Gate D can read the same values back from the stored
 * snapshot (CF-03). The alternative — deriving "observable" from a free-text
 * sentence — would make D5 level 3 unreachable and silently weaken the rubric.
 *
 * <p>Values are sanitised so the trailer is unambiguous: {@code ;}, {@code =} and
 * line breaks cannot occur inside a value. Absent trailer means "not recorded",
 * which reads as not observable and not time bounded (fail closed).
 */
public final class FalsificationConditionText {

  public static final String OBSERVABLE = "observable";
  public static final String COMPARISON = "comparison";
  public static final String DECISION_BOUNDARY = "decisionBoundary";

  private static final String MARKER = " | protocol: ";

  /** Appends the three parts to a human-readable condition text. */
  public static String append(
    String text,
    String observable,
    String comparison,
    String decisionBoundary
  ) {
    return (
      (text == null ? "" : text) +
      MARKER +
      OBSERVABLE +
      "=" +
      clean(observable) +
      "; " +
      COMPARISON +
      "=" +
      clean(comparison) +
      "; " +
      DECISION_BOUNDARY +
      "=" +
      clean(decisionBoundary)
    );
  }

  /** The three parts of a condition, empty when the trailer is absent. */
  public static Map<String, String> parts(Statement condition) {
    var found = new LinkedHashMap<String, String>();
    if (condition == null) return found;
    String reasoning = condition.reasoning();
    if (reasoning == null) return found;
    int at = reasoning.lastIndexOf(MARKER);
    if (at < 0) return found;
    for (var pair : reasoning.substring(at + MARKER.length()).split(";")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      found.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
    }
    return found;
  }

  /**
   * STG-12 observability: all three parts were recorded. A condition that names
   * only what to watch is not yet decidable and does not count.
   */
  public static boolean observable(Statement condition) {
    var parts = parts(condition);
    return (
      nonBlank(parts.get(OBSERVABLE)) &&
      nonBlank(parts.get(COMPARISON)) &&
      nonBlank(parts.get(DECISION_BOUNDARY))
    );
  }

  /** D5 level 5: the decision boundary names a date or an explicit time window. */
  public static boolean timeBounded(Statement condition) {
    var boundary = Optional.ofNullable(parts(condition))
      .map(p -> p.get(DECISION_BOUNDARY))
      .orElse(null);
    if (boundary == null) return false;
    return (
      boundary.matches(".*\\d{4}-\\d{2}-\\d{2}.*") ||
      boundary.matches(".*\\b(window|by|within|deadline)\\b.*")
    );
  }

  private static boolean nonBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String clean(String value) {
    return value == null
      ? ""
      : value.replaceAll("[;=\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
  }

  private FalsificationConditionText() {}
}
