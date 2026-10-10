package com.signalframe.infrastructure.ai.providers;

import com.openai.errors.*;
import com.signalframe.ai.domain.ModelFailure;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Translates provider SDK exceptions into the vendor-neutral failure taxonomy.
 *
 * <p>All provider-specific knowledge stays in this package: the retry loop and
 * the audit record only see a {@link ModelFailure}.
 */
final class ProviderFailures {

  private ProviderFailures() {}

  static ModelFailure classify(Throwable error) {
    for (
      Throwable current = error;
      current != null;
      current = next(current)
    ) {
      if (current instanceof OpenAIServiceException service) return fromStatus(
        service.statusCode()
      );
      if (current instanceof OpenAIIoException) return chainIsTimeout(current)
        ? ModelFailure.TIMEOUT
        : ModelFailure.TRANSPORT;
      if (
        current instanceof OpenAIRetryableException
      ) return ModelFailure.PROVIDER_UNAVAILABLE;
      if (
        current instanceof OpenAIInvalidDataException
      ) return ModelFailure.PROVIDER_RESPONSE_INVALID;
      if (chainIsTimeout(current)) return ModelFailure.TIMEOUT;
      if (isTransport(current)) return ModelFailure.TRANSPORT;
    }
    return ModelFailure.UNKNOWN;
  }

  /** Short, non-echoing description used in the redacted error detail. */
  static String describe(Throwable error) {
    if (error instanceof OpenAIServiceException service) {
      var type = service.type();
      return (
        "provider rejected the call with HTTP " +
        service.statusCode() +
        (type.isPresent() ? " (" + type.get() + ")" : "")
      );
    }
    String message = error.getMessage();
    String name = error.getClass().getSimpleName();
    return message == null || message.isBlank()
      ? name
      : name + ": " + message;
  }

  private static ModelFailure fromStatus(int status) {
    return switch (status) {
      case 401 -> ModelFailure.AUTHENTICATION;
      case 402 -> ModelFailure.QUOTA_EXCEEDED;
      case 403 -> ModelFailure.PERMISSION_DENIED;
      case 404 -> ModelFailure.MODEL_NOT_FOUND;
      case 408 -> ModelFailure.TIMEOUT;
      case 409, 425, 429 -> ModelFailure.RATE_LIMITED;
      case 400, 413, 422 -> ModelFailure.INVALID_REQUEST;
      default -> status >= 500
        ? ModelFailure.PROVIDER_UNAVAILABLE
        : ModelFailure.INVALID_REQUEST;
    };
  }

  private static Throwable next(Throwable current) {
    return current.getCause() == current ? null : current.getCause();
  }

  private static boolean chainIsTimeout(Throwable error) {
    for (
      Throwable current = error;
      current != null;
      current = next(current)
    ) {
      if (
        current instanceof SocketTimeoutException ||
        current instanceof InterruptedIOException ||
        current instanceof TimeoutException ||
        current instanceof java.net.http.HttpTimeoutException
      ) return true;
      String message = current.getMessage();
      if (message != null) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) return true;
      }
    }
    return false;
  }

  private static boolean isTransport(Throwable error) {
    return (
      error instanceof java.io.IOException ||
      error instanceof ConnectException ||
      error instanceof UnknownHostException
    );
  }
}
