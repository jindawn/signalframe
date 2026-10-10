package com.signalframe.research.domain.hypotheses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Canonical rendering and reading of a hypothesis transition event.
 *
 * <p>The frozen {@code HypothesisEvent} record carries the epistemic facts of a
 * transition — {@code eventType}, {@code previousStatus}/{@code status},
 * {@code previousConfidence}/{@code confidence}, {@code reason}, {@code createdAt}
 * — but it has no field for the coordination and audit data a Wave 2B transition
 * must not lose:
 *
 * <ul>
 *   <li>the <b>dimension breakdown</b> and the dimensions that moved
 *       (CONFIDENCE_MODEL §10 Phase 2 requires the event to record them, and
 *       freeze §4.1 invariant 1 requires the moved ones to be named);</li>
 *   <li>the <b>rubric version</b> and the method ({@code RUBRIC} or the CF-06
 *       fail-closed {@code MODEL_JUDGMENT}) the score came from (PR-16);</li>
 *   <li>the <b>version pair</b> the optimistic lock moved through, and</li>
 *   <li>the <b>referenced evidence or prediction</b>, which freeze §2.2 needs for
 *       the idempotency fingerprint.</li>
 * </ul>
 *
 * <p>Two constraints forbid storing them anywhere else. Extra JSON properties are
 * impossible: {@code JsonCodec} enables {@code FAIL_ON_UNKNOWN_PROPERTIES}, so a
 * richer payload would break the existing {@code hypothesis_events} read path in
 * {@code JdbcResearchRepository}. And no migration may be added by this task. The
 * three parts therefore travel inside the event's {@code reason} in a fixed,
 * machine-readable trailer:
 *
 * <pre>{@code <user reason> | transition: cause=...; ref=...; ...}</pre>
 *
 * <p>This follows the existing precedent for exactly this class of gap:
 * {@code FalsificationConditionText} carries STG-12's three mandatory parts in a
 * statement's {@code reasoning} because SCH-06 nests a condition as a plain
 * {@code Statement}. The trailer is a rendering of values the transition already
 * validated, it is deterministic, and it is parsed by the same class that writes
 * it, so a replay can reproduce the originally recorded result exactly.
 *
 * <p>Values are sanitised so the trailer is unambiguous: {@code ;}, {@code =} and
 * line breaks cannot occur inside a value. A reason containing the marker itself
 * is neutralised by {@link #canonicalReason(String)} before comparison, so a
 * replay of the same command is never mistaken for a fingerprint mismatch.
 */
public final class TransitionEventText {

  /** Separates the human reason from the machine-readable trailer. */
  public static final String MARKER = " | transition: ";

  /** Placeholder for an absent optional value. */
  public static final String NONE = "none";

  private static final String CAUSE = "cause";
  private static final String REF = "ref";
  private static final String PREVIOUS_VERSION = "previousVersion";
  private static final String VERSION = "version";
  private static final String PREVIOUS_STATUS = "previousStatus";
  private static final String STATUS = "status";
  private static final String PREVIOUS_SCORE = "previousScore";
  private static final String SCORE = "score";
  private static final String BAND = "band";
  private static final String RUBRIC = "rubric";
  private static final String METHOD = "method";
  private static final String DIMENSIONS = "dimensions";
  private static final String MOVED = "moved";

  private TransitionEventText() {}

  /** One dimension as it appears in the trailer. */
  public record Dimension(String id, int level, int points) {}

  /**
   * The complete audit record of one applied transition. Every field is the value
   * the transition result reports, so a replay can be answered from the trailer
   * alone with no second lookup.
   */
  public record Facts(
    String cause,
    String ref,
    long previousVersion,
    long version,
    String previousStatus,
    String status,
    int previousScore,
    int score,
    String band,
    String rubric,
    String method,
    List<Dimension> dimensions,
    List<String> moved
  ) {
    public Facts {
      dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
      moved = moved == null ? List.of() : List.copyOf(moved);
    }
  }

  /**
   * Appends the trailer to a human reason. {@code previousStatus} and
   * {@code status} are the <em>protocol</em> values the result reports.
   */
  public static String append(String userReason, Facts facts) {
    if (facts == null) throw new IllegalArgumentException(
      "transition facts are required"
    );
    var parts = new ArrayList<String>();
    parts.add(CAUSE + "=" + clean(facts.cause()));
    parts.add(REF + "=" + clean(facts.ref() == null ? NONE : facts.ref()));
    parts.add(PREVIOUS_VERSION + "=" + facts.previousVersion());
    parts.add(VERSION + "=" + facts.version());
    parts.add(PREVIOUS_STATUS + "=" + clean(facts.previousStatus()));
    parts.add(STATUS + "=" + clean(facts.status()));
    parts.add(PREVIOUS_SCORE + "=" + facts.previousScore());
    parts.add(SCORE + "=" + facts.score());
    parts.add(BAND + "=" + clean(facts.band() == null ? NONE : facts.band()));
    parts.add(RUBRIC + "=" + clean(facts.rubric() == null ? NONE : facts.rubric()));
    parts.add(METHOD + "=" + clean(facts.method() == null ? NONE : facts.method()));
    parts.add(DIMENSIONS + "=" + renderDimensions(facts.dimensions()));
    parts.add(
      MOVED + "=" + (facts.moved().isEmpty() ? NONE : String.join(",", facts.moved()))
    );
    return canonicalReason(userReason) + MARKER + String.join("; ", parts);
  }

  /** The trailer of a stored reason, or empty when it carries none. */
  public static Optional<Facts> read(String storedReason) {
    if (storedReason == null) return Optional.empty();
    int at = storedReason.lastIndexOf(MARKER);
    if (at < 0) return Optional.empty();
    var values = new java.util.LinkedHashMap<String, String>();
    for (var pair : storedReason.substring(at + MARKER.length()).split(";")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      values.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
    }
    if (!values.containsKey(CAUSE)) return Optional.empty();
    return Optional.of(
      new Facts(
        values.get(CAUSE),
        noneToNull(values.get(REF)),
        longOr(values.get(PREVIOUS_VERSION), 0L),
        longOr(values.get(VERSION), 0L),
        values.get(PREVIOUS_STATUS),
        values.get(STATUS),
        intOr(values.get(PREVIOUS_SCORE), 0),
        intOr(values.get(SCORE), 0),
        noneToNull(values.get(BAND)),
        noneToNull(values.get(RUBRIC)),
        noneToNull(values.get(METHOD)),
        readDimensions(values.get(DIMENSIONS)),
        readList(values.get(MOVED))
      )
    );
  }

  /** The human part of a stored reason, trailer removed. */
  public static String userReason(String storedReason) {
    if (storedReason == null) return "";
    int at = storedReason.lastIndexOf(MARKER);
    return at < 0 ? storedReason : storedReason.substring(0, at);
  }

  /**
   * The form of a reason that is safe to embed and to compare. Neutralising the
   * marker keeps {@link #read(String)} unambiguous even when a caller writes the
   * marker inside its own reason text.
   */
  public static String canonicalReason(String userReason) {
    if (userReason == null) return "";
    return userReason.replace(MARKER, " | transition-: ");
  }

  private static String renderDimensions(List<Dimension> dimensions) {
    if (dimensions == null || dimensions.isEmpty()) return NONE;
    var parts = new ArrayList<String>();
    for (var dimension : dimensions) parts.add(
      clean(dimension.id()) + ":" + dimension.level() + ":" + dimension.points()
    );
    return String.join(",", parts);
  }

  private static List<Dimension> readDimensions(String value) {
    var out = new ArrayList<Dimension>();
    if (value == null || NONE.equals(value) || value.isBlank()) return out;
    for (var token : value.split(",")) {
      var fields = token.split(":");
      if (fields.length != 3) continue;
      try {
        out.add(
          new Dimension(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]))
        );
      } catch (NumberFormatException ignored) {
        // A malformed dimension token is skipped rather than failing the read:
        // the trailer is audit copy, never an input to a decision.
      }
    }
    return out;
  }

  private static List<String> readList(String value) {
    if (value == null || value.isBlank() || NONE.equals(value)) return List.of();
    var out = new ArrayList<String>();
    for (var token : value.split(",")) if (!token.isBlank()) out.add(token.trim());
    return out;
  }

  private static String noneToNull(String value) {
    return value == null || NONE.equals(value) || value.isBlank() ? null : value;
  }

  private static long longOr(String value, long fallback) {
    try {
      return Long.parseLong(value);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static int intOr(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String clean(String value) {
    return value == null
      ? ""
      : value
          .replaceAll("[;=\\r\\n]+", " ")
          .replaceAll("\\s+", " ")
          .trim();
  }
}
