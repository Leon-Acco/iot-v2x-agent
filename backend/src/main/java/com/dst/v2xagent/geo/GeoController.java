package com.dst.v2xagent.geo;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地理智能端点：省 / 市 / 区车辆聚合（geo.vehicle.distribution）
 */
@RestController
@RequestMapping("/ag-ui/geo")
@RequiredArgsConstructor
public class GeoController {

    private final GeoService geoService;

    /** 区域聚合：level=province|city|district，city/district 需传 parent（上级区域名） */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "province") String level,
                                     @RequestParam(required = false) String parent,
                                     HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            throw ApiException.unauthorized("未认证");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("parent", parent);
        out.put("regions", geoService.regionStats(level, parent, ctx));
        return out;
    }
}
