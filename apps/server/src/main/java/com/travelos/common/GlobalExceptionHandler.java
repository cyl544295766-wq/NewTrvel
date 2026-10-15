package com.travelos.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mirrors the NestJS error body shape: {message, error, statusCode}.
 * Validation failures return {message: string[], error: "Bad Request", statusCode: 400}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", ex.getMessage());
    body.put("error", HttpStatus.resolve(ex.getStatusCode()).getReasonPhrase());
    body.put("statusCode", ex.getStatusCode());
    return ResponseEntity.status(ex.getStatusCode()).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    List<String> messages = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", messages);
    body.put("error", "Bad Request");
    body.put("statusCode", 400);
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
    return errorBody(400, "Bad Request", ex.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
    return errorBody(400, "Bad Request", "请求体格式不正确");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return errorBody(400, "Bad Request", "参数格式不正确");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
    return errorBody(ex.getStatus().value(), ex.getStatus().getReasonPhrase(), ex.getReason());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
        .error("Unhandled exception", ex);
    return errorBody(500, "Internal Server Error", "Internal server error");
  }

  private ResponseEntity<Map<String, Object>> errorBody(int status, String error, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", message);
    body.put("error", error);
    body.put("statusCode", status);
    return ResponseEntity.status(status).body(body);
  }
}
