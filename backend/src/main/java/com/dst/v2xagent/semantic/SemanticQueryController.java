package com.dst.v2xagent.semantic;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.semantic.model.SemanticQuery;
import com.dst.v2xagent.semantic.model.SemanticResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语义查询端点：Semantic Query 唯一 HTTP 入口（Agent / 前端 / 测试共用）
 * 权限由 AuthFilter + Capability 管线层层把关，本类不做业务逻辑。
 */
@RestController
@RequestMapping("/ag-ui/semantic")
@RequiredArgsConstructor
public class SemanticQueryController {

    private final SemanticQueryService semanticQueryService;
    private final SemanticRegistry semanticRegistry;

    /** 执行语义查询 */
    @PostMapping("/query")
    public SemanticResult query(@RequestBody SemanticQuery query, HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            throw ApiException.unauthorized("未认证");
        }
        return semanticQueryService.execute(query, ctx);
    }

    /** 语义层目录：指标 / 实体 / 维度（给模型与前端发现用） */
    @GetMapping("/catalog")
    public Map<String, Object> catalog(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            throw ApiException.unauthorized("未认证");
        }
        return Map.of(
                "metrics", semanticRegistry.listMetrics(),
                "entities", semanticRegistry.entities().values(),
                "dimensions", semanticRegistry.dimensions().values());
    }
}
