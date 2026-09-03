package com.dst.v2xagent.observability.trace;

/** 追踪片段：agent / planner / memory / capability / sql / analysis / response */
public record TraceSpan(String spanType, String name, long startMs, long durationMs, String status, String detail) {}
