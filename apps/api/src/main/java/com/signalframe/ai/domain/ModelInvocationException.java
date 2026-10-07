package com.signalframe.ai.domain;

import java.util.regex.Pattern;

/**
 * Vendor-neutral failure raised by a provider adapter or by local output
 * validation.
 *
 * <p>The message is always redacted before it is constructed: the resolved
 * credential is replaced, and key-shaped tokens are masked defensively. The
 * message is short, single line and truncated so that an accidental provider
 * echo cannot dump a request body into the audit table.
 */
public final class ModelInvocationException extends RuntimeException {

  private static final int MAX_DETAIL = 500;

  private static final Pattern BEARER = Pattern.compile(
    "(?i)bearer\\s+[A-Za-z0-9._\\-]+"
  );

  private static final Pattern KEY_SHAPED = Pattern.compile(
    "(?i)\\b(sk|api[_-]?key|access[_-]?token|token|secret)[-_:][A-Za-z0-9._\\-]{8,}"
  );

  private final ModelFailure failure;
  private final String detail;

  public ModelInvocationException(
    ModelFailure failure,
    String detail,
    ModelCredentials credentials
  ) {
    super(
      redact(failure.code() + ": " + detail, credentials == null
        ? null
        : credentials.value()),
      null,
      false,
      false
    );
    this.failure = failure;
    this.detail = redact(
      detail,
      credentials == null ? null : credentials.value()
    );
  }

  public static ModelInvocationException of(
    ModelFailure failure,
    String detail
  ) {
    return new ModelInvocationException(failure, detail, null);
  }

  public static ModelInvocationException of(
    ModelFailure failure,
    String detail,
    ModelCredentials credentials
  ) {
    return new ModelInvocationException(failure, detail, credentials);
  }

  public ModelFailure failure() {
    return failure;
  }

  public String code() {
    return failure.code();
  }

  public boolean retryable() {
    return failure.retryable();
  }

  /** Redacted, single-line detail safe for audit and HTTP error payloads. */
  public String detail() {
    return detail;
  }

  private static String redact(String text, String secret) {
    String value = text == null ? "" : text;
    if (secret != null && !secret.isBlank()) value = value.replace(
      secret,
      "[REDACTED]"
    );
    value = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
    value = KEY_SHAPED.matcher(value).replaceAll("[REDACTED]");
    value = value.replaceAll("\\s+", " ").trim();
    return value.length() <= MAX_DETAIL
      ? value
      : value.substring(0, MAX_DETAIL) + "...";
  }
}
