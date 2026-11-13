package dev.paybuddy.common;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> domain(ApiError e) {
    return ResponseEntity.status(e.status).body(
      Map.of("message", e.getMessage())
    );
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ResponseEntity<?> upstream(Exception e) {
    return ResponseEntity.status(503).body(
      Map.of(
        "message",
        "A required service is temporarily unavailable. Retry safely with the same request key."
      )
    );
  }
}
