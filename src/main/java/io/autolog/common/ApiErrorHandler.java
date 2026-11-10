package io.autolog.common;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> handle(ApiException ex) {
    return ResponseEntity.status(ex.status()).body(body(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    HandlerMethodValidationException.class
  })
  ResponseEntity<?> invalid(Exception ex) {
    return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "입력값의 형식과 필수 항목을 확인하세요."));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate(DataIntegrityViolationException ex) {
    return ResponseEntity.status(409).body(body("DATA_CONFLICT", "중복되거나 연결할 수 없는 데이터입니다."));
  }

  public static Map<String, Object> body(String code, String message) {
    return Map.of("code", code, "message", message, "timestamp", Instant.now().toString());
  }
}
