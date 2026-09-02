package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.agui.auth.AuthService;
import com.dst.v2xagent.common.PermissionContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextRequest;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextResolver;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 运行上下文解析：PermissionContext 注入 RuntimeContext。
 * 权限只从服务端用户记录生成，forwardedProps 永不能扩大权限。
 */
public class V2xAguiRuntimeContextResolver implements AguiRuntimeContextResolver {

    private final AuthService authService;

    public V2xAguiRuntimeContextResolver(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest request) {
        // 优先取 AuthFilter 已验证的权限上下文（避免重复解析 token）
        PermissionContext perm = null;
        HttpServletRequest nativeReq = request.getNativeRequest(HttpServletRequest.class);
        if (nativeReq != null) {
            Object attr = nativeReq.getAttribute(AuthFilter.ATTR_PERMISSION);
            if (attr instanceof PermissionContext p) {
                perm = p;
            }
        }
        if (perm == null) {
            String auth = request.firstHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                perm = authService.resolve(auth.substring(7)).orElse(null);
            }
        }
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (request.getInput() != null) {
            builder.sessionId(request.getInput().getThreadId());
        }
        if (perm != null) {
            builder.userId(String.valueOf(perm.userId()));
            builder.put(PermissionContext.class, perm);
        }
        return builder.build();
    }
}
