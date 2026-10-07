package com.signalframe.http;

import com.signalframe.contract.ApiError;
import com.signalframe.shared.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorAdvice {

  @ExceptionHandler(ApplicationException.class)
  ResponseEntity<ApiError> application(
    ApplicationException e,
    HttpServletRequest r
  ) {
    return error(e.status(), e.code(), e.getMessage(), r);
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    jakarta.validation.ConstraintViolationException.class,
    IllegalArgumentException.class,
  })
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest r) {
    return error(
      400,
      "INVALID_REQUEST",
      "Invalid request; check fields and identifiers.",
      r
    );
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest r) {
    org.slf4j.LoggerFactory.getLogger(getClass()).error(
      "Request failed: {}",
      e.getClass().getSimpleName()
    );
    return error(500, "INTERNAL_ERROR", "请求失败，请稍后重试。", r);
  }

  private ResponseEntity<ApiError> error(
    int status,
    String code,
    String message,
    HttpServletRequest r
  ) {
    return ResponseEntity.status(status).body(
      new ApiError(code, message, String.valueOf(r.getAttribute("requestId")))
    );
  }
}
