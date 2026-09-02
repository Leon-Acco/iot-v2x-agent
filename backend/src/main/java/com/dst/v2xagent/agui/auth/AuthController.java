package com.dst.v2xagent.agui.auth;

import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证端点：登录 / 登出 / 当前用户
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest req, HttpServletResponse response) {
        return authService.login(req.username(), req.password())
                .map(token -> {
                    Cookie cookie = new Cookie(AuthFilter.COOKIE_NAME, token);
                    cookie.setHttpOnly(true);
                    cookie.setPath("/");
                    cookie.setMaxAge(12 * 3600);
                    response.addCookie(cookie);
                    return Map.<String, Object>of("success", true);
                })
                .orElse(Map.of("success", false, "message", "用户名或密码错误"));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (AuthFilter.COOKIE_NAME.equals(c.getName())) authService.logout(c.getValue());
            }
        }
        Cookie cookie = new Cookie(AuthFilter.COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Map.of("success", true);
    }

    /** 当前登录用户（前端顶栏展示与车队上下文） */
    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        return Map.of(
                "username", ctx.username(),
                "roles", ctx.roles(),
                "fleetIds", ctx.fleetIds(),
                "admin", ctx.isAdmin());
    }
}
