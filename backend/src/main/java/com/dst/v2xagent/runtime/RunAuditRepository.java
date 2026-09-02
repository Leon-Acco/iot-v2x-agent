package com.dst.v2xagent.runtime;

import com.dst.v2xagent.common.PermissionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * 运行审计仓储：每次 run 一条记录（append-only），从第一天接入，不等后台页面
 */
@Repository
@RequiredArgsConstructor
public class RunAuditRepository {

    private final JdbcTemplate controlJdbcTemplate;

    public void record(PermissionContext ctx, RunOrchestrator.RunInput input, String traceId,
                       String capabilityId, Map<String, Object> normalizedParams,
                       String status, String errorCode, String failedStage,
                       long elapsedMs, Integer rowCount) {
        try {
            String paramsJson = normalizedParams == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(normalizedParams);
            controlJdbcTemplate.update("""
                    INSERT INTO run_audit
                    (tenant_id, run_id, trace_id, user_id, profile_id, question, capability_id, params_json,
                     status, error_code, failed_stage, elapsed_ms, row_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE status = VALUES(status), error_code = VALUES(error_code),
                        failed_stage = VALUES(failed_stage), elapsed_ms = VALUES(elapsed_ms),
                        row_count = VALUES(row_count)
                    """, ctx.tenantId(), input.runId(), traceId, ctx.userId(), input.profileId(),
                    input.question(), capabilityId, paramsJson, status, errorCode, failedStage, elapsedMs, rowCount);
        } catch (Exception e) {
            // 审计写入失败不阻断已返回给用户的结果，但必须留日志
            org.slf4j.LoggerFactory.getLogger(getClass()).error("运行审计写入失败 runId={}", input.runId(), e);
        }
    }
}
