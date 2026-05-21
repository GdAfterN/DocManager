package com.javaee.docmanager.security;

import com.javaee.docmanager.common.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        // 白名单路径直接放行
        if (isWhiteListed(path)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        // 如果 Header 没有 token，尝试从查询参数获取（用于 window.open 预览场景）
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            String queryToken = request.getParameter("token");
            if (StringUtils.hasText(queryToken)) {
                authHeader = "Bearer " + queryToken;
            }
        }
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未授权访问\"}");
            return false;
        }

        try {
            String token = authHeader.substring(7);
            if (!JwtUtils.validateToken(token)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"无效的令牌\"}");
                return false;
            }

            Long userId = JwtUtils.getUserId(token);
            String username = JwtUtils.getUsername(token);
            String role = JwtUtils.getRole(token);

            UserContext.setCurrentUser(userId, username, role);
            return true;
        } catch (Exception e) {
            log.error("JWT验证失败", e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"令牌验证失败\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean isWhiteListed(String path) {
        return path.equals("/api/users/login")
                || path.equals("/api/users/register")
                || path.equals("/api/users/refresh")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api-docs")
                || path.startsWith("/actuator")
                || path.equals("/error");
    }
}
