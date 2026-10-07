package com.signalframe.shared;

public class ApplicationException extends RuntimeException {

  private final int status;
  private final String code;

  public ApplicationException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApplicationException missing() {
    return new ApplicationException(404, "NOT_FOUND", "Resource not found");
  }
}
