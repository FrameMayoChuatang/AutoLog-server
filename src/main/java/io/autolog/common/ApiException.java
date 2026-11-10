package io.autolog.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다."); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message); }
    public static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."); }
    public static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 없거나 유효하지 않습니다."); }
}
