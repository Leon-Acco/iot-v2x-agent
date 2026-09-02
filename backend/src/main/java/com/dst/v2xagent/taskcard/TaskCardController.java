package com.dst.v2xagent.taskcard;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/**
 * 任务卡端点（R1 草稿级：可自动生成，不产生外部副作用）。
 * POST /ag-ui/task-card 创建；GET /ag-ui/task-card/{id}/pdf 下载（二次验权）。
 */
@Slf4j
@RestController
@RequestMapping("/ag-ui/task-card")
@RequiredArgsConstructor
public class TaskCardController {

    private final TaskCardService taskCardService;

    public record CreateRequest(String title, @NotBlank String capabilityId,
                                Map<String, Object> params, String runId, String traceId,
                                String conclusion) {}

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateRequest req, HttpServletRequest request) throws Exception {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        return taskCardService.create(ctx, new TaskCardService.CreateRequest(
                req.title(), req.capabilityId(),
                req.params() != null ? req.params() : Map.of(),
                req.runId(), req.traceId(), req.conclusion()));
    }

    /** 我的任务卡列表（最近 50 条） */
    @GetMapping("/mine")
    public java.util.List<java.util.Map<String, Object>> mine(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        return taskCardService.listMine(ctx);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<FileSystemResource> download(@PathVariable long id, HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Map<String, Object> row = taskCardService.loadForDownload(ctx, id);
        FileSystemResource resource = new FileSystemResource(Path.of(String.valueOf(row.get("file_path"))));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=task-card-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }
}
