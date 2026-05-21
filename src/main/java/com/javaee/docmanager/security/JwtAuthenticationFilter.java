package com.javaee.docmanager.security;

import com.javaee.docmanager.common.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String authHeader = request.getHeader("Authorization");

        log.info("JWT Filter - Path: {}, AuthHeader: {}", path, authHeader != null ? "present" : "null");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                boolean valid = JwtUtils.validateToken(token);
                log.info("JWT Filter - Token valid: {}", valid);
                if (valid) {
                    String username = JwtUtils.getUsername(token);
                    log.info("JWT Filter - Authenticated user: {}", username);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(username, null, AuthorityUtils.NO_AUTHORITIES);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                log.error("JWT Filter - Token validation error: {}", e.getMessage(), e);
            }
        }

        filterChain.doFilter(request, response);
    }
}
