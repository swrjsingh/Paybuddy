package dev.paybuddy.common;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {

  public final HttpStatus status;

  public ApiError(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public static ApiError notFound() {
    return new ApiError(HttpStatus.NOT_FOUND, "Record not found");
  }

  public static ApiError bad(String m) {
    return new ApiError(HttpStatus.BAD_REQUEST, m);
  }

  public static ApiError conflict(String m) {
    return new ApiError(HttpStatus.CONFLICT, m);
  }

  public static ApiError forbidden() {
    return new ApiError(HttpStatus.FORBIDDEN, "Access denied");
  }
}
