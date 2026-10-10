package com.signalframe.research.domain.hypotheses;

import com.signalframe.shared.ApplicationException;
import java.util.UUID;

/**
 * The error vocabulary of a hypothesis transition (freeze §2.1).
 *
 * <p>{@code ErrorAdvice} already maps {@code ApplicationException(status, code,
 * message)} onto the {@code ApiError} envelope, so every case below reaches the
 * client with the status and code the freeze allocated, with no new handler and no
 * change to the error shape:
 *
 * <table>
 *   <caption>Status and code per failure</caption>
 *   <tr><th>status</th><th>code</th><th>when</th></tr>
 *   <tr><td>400</td><td>INVALID_REQUEST</td><td>structurally impossible command</td></tr>
 *   <tr><td>404</td><td>NOT_FOUND</td><td>unknown hypothesis, evidence or prediction</td></tr>
 *   <tr><td>409</td><td>VERSION_CONFLICT</td><td>stale {@code expectedVersion}; nothing written</td></tr>
 *   <tr><td>409</td><td>IDEMPOTENCY_MISMATCH</td><td>the {@code operationId} already exists with a different request</td></tr>
 *   <tr><td>422</td><td>UNPROCESSABLE_TRANSITION</td><td>well formed but not allowed</td></tr>
 * </table>
 */
public final class TransitionErrors {

  private TransitionErrors() {}

  /** A referenced hypothesis, evidence item or prediction does not exist. */
  public static ApplicationException notFound(String what) {
    return new ApplicationException(404, "NOT_FOUND", "Unknown " + what + ".");
  }

  /**
   * {@code expectedVersion} no longer matches the stored version. The message
   * names both so a caller can re-read and retry without guessing.
   */
  public static ApplicationException versionConflict(
    long storedVersion,
    long expectedVersion
  ) {
    return new ApplicationException(
      409,
      "VERSION_CONFLICT",
      "The hypothesis moved from version " +
      expectedVersion +
      " to " +
      storedVersion +
      "; nothing was written. Re-read and retry."
    );
  }

  /**
   * The {@code operationId} was already applied to a different request. Never a
   * silent second application (freeze §2.2).
   */
  public static ApplicationException idempotencyMismatch(UUID operationId) {
    return new ApplicationException(
      409,
      "IDEMPOTENCY_MISMATCH",
      "Operation " +
      operationId +
      " was already applied with a different request; it is an idempotency key, not a reusable token."
    );
  }

  /** Well formed but not allowed for the current state. */
  public static ApplicationException unprocessable(String message) {
    return new ApplicationException(422, "UNPROCESSABLE_TRANSITION", message);
  }

  /** Structurally impossible: the command does not describe a transition at all. */
  public static ApplicationException invalid(String message) {
    return new ApplicationException(400, "INVALID_REQUEST", message);
  }
}
