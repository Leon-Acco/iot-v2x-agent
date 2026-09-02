package com.dst.v2xagent.agui.auth;

import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * 认证过滤器：从 Cookie 解析会话 → PermissionContext 放入请求属性
 * 未认证访问 /ag-ui/** 与 /admin/** 一律 401。
 */
@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_PERMISSION = "permissionContext";
    public static final String COOKIE_NAME = "V2X_SESSION";

    private final AuthService authService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/ag-ui/") || path.startsWith("/agui/") || path.startsWith("/admin/") || path.startsWith("/api/me"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = Arrays.stream(request.getCookies() == null ? new Cookie[0] : request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
        PermissionContext ctx = authService.resolve(token).orElse(null);
        if (ctx == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"未登录或会话已过期\"}");
            return;
        }
        request.setAttribute(ATTR_PERMISSION, ctx);
        chain.doFilter(request, response);
    }
}
