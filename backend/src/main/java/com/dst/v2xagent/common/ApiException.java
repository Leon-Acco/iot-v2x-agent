package com.dst.v2xagent.common;

/**
 * 业务异常：携带错误码与失败阶段，直接映射前端 FailCard / RefuseCard
 */
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final String failedStage;
    private final boolean retryable;

    public ApiException(String errorCode, String failedStage, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.failedStage = failedStage;
        this.retryable = retryable;
    }

    public static ApiException paramInvalid(String msg) {
        return new ApiException("PARAM_INVALID", "resolve", false, msg);
    }

    public static ApiException scopeDenied(String msg) {
        return new ApiException("SCOPE_DENIED", "resolve", false, msg);
    }

    public static ApiException capabilityNotFound(String msg) {
        return new ApiException("CAPABILITY_NOT_FOUND", "understand", false, msg);
    }

    public static ApiException dataTimeout(String msg) {
        return new ApiException("DATA_TIMEOUT", "invoke", true, msg);
    }

    public static ApiException dataUnavailable(String msg) {
        return new ApiException("DATA_UNAVAILABLE", "invoke", true, msg);
    }

    public static ApiException schemaInvalid(String msg) {
        return new ApiException("SCHEMA_INVALID", "understand", true, msg);
    }

    public static ApiException unauthorized(String msg) {
        return new ApiException("UNAUTHORIZED", "auth", false, msg);
    }

    public String errorCode() { return errorCode; }
    public String failedStage() { return failedStage; }
    public boolean retryable() { return retryable; }
}
