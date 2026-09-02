package com.dst.v2xagent.agui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AG-UI 事件模型（与前端契约一对一，见前端设计文档 §8）
 * 事件顺序红线：TOOL_CALL_RESULT（表格）必须先于 TEXT_MESSAGE_CONTENT（结论）。
 */
public record AgUiEvent(String type, Map<String, Object> payload) {

    public static AgUiEvent of(String type, Map<String, Object> payload) {
        return new AgUiEvent(type, payload);
    }

    public static AgUiEvent runStarted(String runId, String threadId, String traceId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        p.put("threadId", threadId);
        p.put("traceId", traceId);
        return of("RUN_STARTED", p);
    }

    public static AgUiEvent stepStarted(String stage, String label) {
        return of("STEP_STARTED", Map.of("stage", stage, "label", label));
    }

    public static AgUiEvent stepFinished(String stage) {
        return of("STEP_FINISHED", Map.of("stage", stage));
    }

    public static AgUiEvent toolCallStart(String capabilityId, String displayName) {
        return of("TOOL_CALL_START", Map.of("capabilityId", capabilityId, "displayName", displayName));
    }

    public static AgUiEvent toolCallArgs(Map<String, Object> normalizedArgs) {
        return of("TOOL_CALL_ARGS", Map.of("args", normalizedArgs));
    }

    public static AgUiEvent refuse(String reason, String message, Object suggestedCapabilities) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason);
        p.put("message", message);
        p.put("suggestedCapabilities", suggestedCapabilities);
        return of("REFUSE", p);
    }

    public static AgUiEvent clarify(String question, Object options) {
        return of("CLARIFY", Map.of("question", question, "options", options));
    }

    public static AgUiEvent runError(String errorCode, String failedStage, boolean retryable, String message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("errorCode", errorCode);
        p.put("failedStage", failedStage);
        p.put("retryable", retryable);
        p.put("message", message);
        return of("RUN_ERROR", p);
    }
}
