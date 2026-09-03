package com.dst.v2xagent.analysis;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.analysis.model.AnalysisReport;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 分析引擎端点：异常分析（证据优先，LLM 只解释）
 */
@RestController
@RequestMapping("/ag-ui/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnomalyAnalysisService anomalyAnalysisService;

    /** 异常分析：告警 / 故障 / 里程基线对比 + 相关性，返回证据集 */
    @PostMapping("/anomaly")
    public AnalysisReport anomaly(@RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            throw ApiException.unauthorized("未认证");
        }
        Map<String, Object> filters = body == null ? Map.of() : body;
        return anomalyAnalysisService.analyze(filters, ctx);
    }
}
