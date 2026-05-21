package com.javaee.docmanager.limiter;

import com.javaee.docmanager.common.exception.BusinessException;
import com.javaee.docmanager.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * 滑动窗口限流AOP切面
 * 使用Redis ZSet存储请求时间戳，ZRANGEBYSCORE清理窗口外记录
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit);
        long now = Instant.now().toEpochMilli();
        long windowStart = now - rateLimit.timeWindow() * 1000;

        // 清理窗口外的旧记录
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // 获取当前窗口内的请求数
        Long count = redisTemplate.opsForZSet().zCard(key);

        if (count != null && count >= rateLimit.maxRequests()) {
            log.warn("触发限流: key={}, count={}, limit={}", key, count, rateLimit.maxRequests());
            throw new BusinessException("请求过于频繁，请稍后再试");
        }

        // 添加当前请求
        redisTemplate.opsForZSet().add(key, String.valueOf(now), now);
        // 设置key过期时间（略大于窗口时间）
        redisTemplate.expire(key, rateLimit.timeWindow() + 1, java.util.concurrent.TimeUnit.SECONDS);

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
