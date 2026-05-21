package com.javaee.docmanager.limiter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流注解
 * 基于Redis ZSet实现
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 时间窗口（秒），默认60秒 */
    long timeWindow() default 60;

    /** 最大请求数，默认100 */
    long maxRequests() default 100;

    /** 限流维度 */
    Dimension dimension() default Dimension.IP;

    /** 限流key前缀 */
    String key() default "";

    enum Dimension {
        GLOBAL,  // 全局限流
        IP,      // IP限流
        USER     // 用户限流
    }
}
