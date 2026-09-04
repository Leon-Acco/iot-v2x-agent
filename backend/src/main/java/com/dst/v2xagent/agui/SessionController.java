package com.dst.v2xagent.agui;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.memory.SessionExportService;
import com.dst.v2xagent.memory.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话管理端点：列表 / 重命名 / 删除 / 消息读取 / 会话级 PDF 导出。
 * 会话数据源为 agent_session + agent_session_message（MySQL 事实库），
 * /ag-ui/** 前缀由 AuthFilter 自动鉴权，PermissionContext 从请求属性取。
 */
@RestController
@RequestMapping("/ag-ui/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionStore sessionStore;
    private final SessionExportService exportService;

    /** 本人会话列表（updated_at 倒序，默认 50 条） */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "fleet_copilot") String profileId,
                                          @RequestParam(defaultValue = "50") int limit,
                                          HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        return sessionStore.listSessions(ctx.tenantId(), ctx.userId(), profileId, Math.min(limit, 100));
    }

    /** 重命名请求体 */
    public record RenameRequest(@NotBlank @Size(max = 128) String title) {}

    /** 重命名会话（仅本人） */
    @PostMapping("/{id}/rename")
    public Map<String, Object> rename(@PathVariable String id, @Valid @RequestBody RenameRequest req,
                                      HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        sessionStore.rename(ctx.tenantId(), id, ctx.userId(), req.title().trim());
        return Map.of("success", true);
    }

    /** 删除会话（session + messages 两表物理删，仅本人） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        sessionStore.delete(ctx.tenantId(), id, ctx.userId());
        return Map.of("success", true);
    }

    /** 会话全部消息（含 payload 帧快照，供前端恢复完整体验） */
    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id, HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        Map<String, Object> session = sessionStore.findSession(ctx.tenantId(), id,
                ctx.isAdmin() ? null : ctx.userId());
        if (session == null) {
            throw ApiException.paramInvalid("会话不存在或无权访问: " + id);
        }
        return sessionStore.listMessages(ctx.tenantId(), id);
    }

    /** 会话级 PDF 导出（多轮对话快照，内存生成直接下载） */
    @GetMapping(value = "/{id}/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id, HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        byte[] pdf = exportService.export(ctx, id);
        String fileName = "session-" + id + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private PermissionContext permission(HttpServletRequest request) {
        return (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
    }
}
