package com.javaee.docmanager.limiter;

import com.javaee.docmanager.common.exception.BusinessException;
import com.javaee.docmanager.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Collections;

/**
 * 滑动窗口限流AOP切面
 * 使用Redis ZSet + Lua脚本保证原子性
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<Long> rateLimitScript;

    @Autowired
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rate_limit.lua")));
        rateLimitScript.setResultType(Long.class);
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit);
        long now = Instant.now().toEpochMilli();
        long windowStart = now - rateLimit.timeWindow() * 1000;

        // Lua脚本原子执行：清理+计数+判断+记录
        Long count = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(windowStart),
                String.valueOf(now),
                String.valueOf(rateLimit.maxRequests()),
                String.valueOf(rateLimit.timeWindow() + 1)
        );

        if (count != null && count > rateLimit.maxRequests()) {
            log.warn("触发限流: key={}, count={}, limit={}", key, count, rateLimit.maxRequests());
            throw new BusinessException("请求过于频繁，请稍后再试");
        }

        return joinPoint.proceed();
    }

    private String buildKey(RateLimit rateLimit) {
        StringBuilder sb = new StringBuilder("rate-limit:");
        if (rateLimit.key() != null && !rateLimit.key().isEmpty()) {
            sb.append(rateLimit.key()).append(":");
        }

        switch (rateLimit.dimension()) {
            case IP:
                sb.append("ip:").append(getClientIp());
                break;
            case USER:
                Long userId = UserContext.getCurrentUserId();
                sb.append("user:").append(userId != null ? userId : "anonymous");
                break;
            case GLOBAL:
            default:
                sb.append("global");
                break;
        }
        return sb.toString();
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty()) {
                    ip = request.getRemoteAddr();
                }
                return ip;
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
}
