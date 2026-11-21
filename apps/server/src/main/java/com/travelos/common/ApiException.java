package com.travelos.common;

public class ApiException extends RuntimeException {

  private final int statusCode;
  /** 可选：429 等场景的 Retry-After（秒）；null 表示不下发该头。 */
  private final Integer retryAfterSeconds;

  public ApiException(int statusCode, String message) {
    this(statusCode, message, null);
  }

  public ApiException(int statusCode, String message, Integer retryAfterSeconds) {
    super(message);
    this.statusCode = statusCode;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int getStatusCode() {
    return statusCode;
  }

  /** 限流类错误的 Retry-After（秒）；非限流错误为 null。 */
  public Integer getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(400, message);
  }

  public static ApiException unauthorized(String message) {
    return new ApiException(401, message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(403, message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(404, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, message);
  }

  /** 触发限流：统一 429 JSON + Retry-After（由 GlobalExceptionHandler 下发）。 */
  public static ApiException tooManyRequests(String message, int retryAfterSeconds) {
    return new ApiException(429, message, Math.max(1, retryAfterSeconds));
  }

  public static ApiException serviceUnavailable(String message) {
    return new ApiException(503, message);
  }
}
