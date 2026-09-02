package com.dst.v2xagent.memory;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.memory.model.MemoryDraft;
import com.dst.v2xagent.memory.model.MemoryItem;
import com.dst.v2xagent.memory.model.MemoryProposal;
import com.dst.v2xagent.memory.model.RecallQuery;
import com.dst.v2xagent.memory.model.RecallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MemoryGateway 的 MySQL 实现（设计文档 §8.5 降级版：FULLTEXT ngram + ACL EXISTS）。
 * 权限不依赖索引及时一致；二次验权才是权威判断，任何异常 fail closed。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlMemoryGateway implements MemoryGateway {

    private final JdbcTemplate controlJdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public RecallResult recall(PermissionContext ctx, RecallQuery query) {
        requireScope(ctx, "memory.read.org");
        String text = sanitize(query.text());
        if (text.isBlank()) {
            return new RecallResult(List.of());
        }
        List<String> tokens = principalTokens(ctx);
        StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.scope_type, m.scope_key, m.content, m.source_trace_id, m.source_version, m.expire_at, m.updated_at,"
                + " MATCH(m.content_index) AGAINST (? IN BOOLEAN MODE) AS text_score"
                + " FROM memory_item m"
                + " WHERE m.tenant_id = ? AND m.status = 'ACTIVE'"
                + " AND (m.expire_at IS NULL OR m.expire_at > CURRENT_TIMESTAMP(3))"
                + " AND MATCH(m.content_index) AGAINST (? IN BOOLEAN MODE)");
        List<Object> args = new ArrayList<>();
        args.add(text);
        args.add(ctx.tenantId());
        args.add(text);
        if (query.scopeTypes() != null && !query.scopeTypes().isEmpty()) {
            sql.append(" AND m.scope_type IN (")
               .append(query.scopeTypes().stream()
                       .map(t -> "'" + t.replace("'", "") + "'")
                       .collect(Collectors.joining(",")))
               .append(")");
        }
        sql.append(" AND EXISTS (SELECT 1 FROM memory_acl a WHERE a.memory_id = m.id"
                + " AND a.tenant_id = m.tenant_id AND a.principal_token IN (")
                .append(String.join(", ", Collections.nCopies(tokens.size(), "?")))
                .append(")) ORDER BY text_score DESC, m.updated_at DESC LIMIT ?");
        args.addAll(tokens);
        args.add(query.effectiveTopK());
        List<MemoryItem> items = controlJdbcTemplate.query(sql.toString(), this::mapItem, args.toArray());
        return new RecallResult(items);
    }

    @Override
    public List<MemoryItem> loadExplicit(PermissionContext ctx, List<String> memoryRefs) {
        if (memoryRefs == null || memoryRefs.isEmpty()) {
            return List.of();
        }
        // 显式引用也要逐条验权：tenant + ACTIVE + 未过期 + ACL 命中
        List<Long> ids = memoryRefs.stream().limit(20).map(this::parseId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> tokens = principalTokens(ctx);
        String sql = "SELECT m.id, m.scope_type, m.scope_key, m.content, m.source_trace_id, m.source_version, m.expire_at, m.updated_at"
                + " FROM memory_item m"
                + " WHERE m.tenant_id = ? AND m.status = 'ACTIVE'"
                + " AND (m.expire_at IS NULL OR m.expire_at > CURRENT_TIMESTAMP(3))"
                + " AND m.id IN (" + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")"
                + " AND EXISTS (SELECT 1 FROM memory_acl a WHERE a.memory_id = m.id"
                + " AND a.tenant_id = m.tenant_id AND a.principal_token IN ("
                + String.join(", ", Collections.nCopies(tokens.size(), "?")) + "))";
        List<Object> args = new ArrayList<>();
        args.add(ctx.tenantId());
        args.addAll(ids);
        args.addAll(tokens);
        return controlJdbcTemplate.query(sql, this::mapItem, args.toArray());
    }

    @Override
    public MemoryProposal propose(PermissionContext ctx, MemoryDraft draft) {
        requireScope(ctx, "memory.write.proposal");
        String evidenceJson;
        try {
            evidenceJson = draft.evidence() == null ? null : mapper.writeValueAsString(draft.evidence());
        } catch (Exception e) {
            evidenceJson = null;
        }
        // 必附失效时间：默认 180 天（陈旧经验比没经验更危险）
        controlJdbcTemplate.update(
                "INSERT INTO memory_proposal (tenant_id, scope_type, scope_key, content, source_trace_id,"
                + " proposed_by, evidence, expire_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? DAY))",
                ctx.tenantId(), draft.scopeType(), draft.scopeKey() == null ? "" : draft.scopeKey(),
                draft.content(), draft.sourceTraceId(), ctx.username(), evidenceJson,
                draft.effectiveExpireDays());
        Long id = controlJdbcTemplate.queryForObject(
                "SELECT id FROM memory_proposal WHERE tenant_id = ? AND proposed_by = ?"
                + " ORDER BY id DESC LIMIT 1",
                Long.class, ctx.tenantId(), ctx.username());
        log.info("记忆提议已入待审队列: id={} by={}", id, ctx.username());
        return new MemoryProposal(id == null ? -1 : id, draft.scopeType(), draft.scopeKey(),
                draft.content(), ctx.username(), "PENDING", null);
    }

    @Override
    public void deleteOwn(PermissionContext ctx, String memoryId) {
        requireScope(ctx, "memory.delete.own");
        Long id = parseId(memoryId);
        if (id == null) {
            throw ApiException.paramInvalid("记忆 id 非法: " + memoryId);
        }
        // 只能删自己提交的（管理员也不例外，走治理台流程）
        int updated = controlJdbcTemplate.update(
                "UPDATE memory_item SET status = 'ARCHIVED', source_version = source_version + 1"
                + " WHERE tenant_id = ? AND id = ? AND proposed_by = ?",
                ctx.tenantId(), id, ctx.username());
        if (updated == 0) {
            throw ApiException.scopeDenied("只能删除自己提交的记忆");
        }
    }

    /** 权限检查：管理员免检，否则需命中对应 scope */
    private void requireScope(PermissionContext ctx, String scope) {
        if (ctx == null) {
            throw ApiException.unauthorized("缺少权限上下文");
        }
        if (ctx.isAdmin()) {
            return;
        }
        if (ctx.scopes() == null || !ctx.scopes().contains(scope)) {
            throw ApiException.scopeDenied("缺少权限: " + scope);
        }
    }

    /** principal token 集合：user:{id} + role:{*} + fleet:{*} */
    private List<String> principalTokens(PermissionContext ctx) {
        List<String> tokens = new ArrayList<>();
        tokens.add("user:" + ctx.userId());
        if (ctx.roles() != null) {
            ctx.roles().forEach(r -> tokens.add("role:" + r));
        }
        if (ctx.fleetIds() != null) {
            ctx.fleetIds().forEach(f -> tokens.add("fleet:" + f));
        }
        return tokens;
    }

    /** 关键词清洗：去 FULLTEXT 运算符，拼 "+词" 形式（全部必须命中） */
    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[-+<>()~*\"@]+", " ").trim();
        String[] terms = cleaned.split("\s+");
        List<String> kept = new ArrayList<>();
        for (String t : terms) {
            if (t.length() >= 2 && kept.size() < 8) {
                kept.add("+" + t);
            }
        }
        return String.join(" ", kept);
    }

    private Long parseId(String ref) {
        try {
            return Long.parseLong(String.valueOf(ref).replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private MemoryItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        Timestamp expire = rs.getTimestamp("expire_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new MemoryItem(
                rs.getLong("id"),
                rs.getString("scope_type"),
                rs.getString("scope_key"),
                rs.getString("content"),
                rs.getString("source_trace_id"),
                rs.getLong("source_version"),
                expire == null ? null : expire.toLocalDateTime(),
                updated == null ? null : updated.toLocalDateTime());
    }
}
