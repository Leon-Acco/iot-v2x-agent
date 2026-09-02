package com.dst.v2xagent.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常映射：ApiException -> 结构化 JSON（errorCode/message），
 * 避免落入 Spring 默认 whitelabel 500，前端可展示真实中文原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> apiException(ApiException e) {
        HttpStatus status = switch (e.errorCode()) {
            case "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "SCOPE_DENIED" -> HttpStatus.FORBIDDEN;
            case "CAPABILITY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DATA_TIMEOUT", "DATA_UNAVAILABLE" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.errorCode());
        body.put("message", e.getMessage());
        body.put("failedStage", e.failedStage());
        body.put("retryable", e.retryable());
        return ResponseEntity.status(status).body(body);
    }
}
