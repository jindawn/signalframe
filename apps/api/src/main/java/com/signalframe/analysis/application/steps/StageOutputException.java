package com.signalframe.analysis.application.steps;

/**
 * Raised by a stage output parser when a response cannot be accepted.
 *
 * <p>It carries a {@link StageFailure.Kind} so the bounded repair loop knows
 * whether a re-prompt can help (`MALFORMED_OUTPUT`, `SCHEMA_INVALID`) and the
 * final failure classification is accurate. The {@code hint} is authored by the
 * parser and names the offending field path; raw model output is never echoed
 * into it.
 */
public final class StageOutputException extends RuntimeException {

  private final StageFailure.Kind kind;
  private final String hint;

  public StageOutputException(StageFailure.Kind kind, String hint) {
    super(hint);
    this.kind = kind;
    this.hint = hint;
  }

  public static StageOutputException malformed(String hint) {
    return new StageOutputException(StageFailure.Kind.MALFORMED_OUTPUT, hint);
  }

  public static StageOutputException schema(String hint) {
    return new StageOutputException(StageFailure.Kind.SCHEMA_INVALID, hint);
  }

  public StageFailure.Kind kind() {
    return kind;
  }

  public String hint() {
    return hint;
  }
}
