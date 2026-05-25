package com.javaee.docmanager.ai.aiops;

import com.javaee.docmanager.common.utils.RedisUtils;
import com.javaee.docmanager.security.UserContext;
import com.javaee.docmanager.user.entity.User;
import com.javaee.docmanager.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 监控服务 — Redis 热路径 + MySQL 持久化
 *
 * 写入：Redis Hash INCRBY（每次 AI 调用）
 * 读取：Redis 优先，回源 MySQL 兜底
 * 滑动窗口：Redis ZSet 记录请求时间戳，检测异常
 */
@Component
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final UserMapper userMapper;
    private final RedisUtils redisUtils;

    private static final String METRICS_PREFIX = "metrics:user:";
    private static final String GLOBAL_KEY = "metrics:global";
    private static final String WINDOW_PREFIX = "window:user:";
    private static final String ALERT_PREFIX = "alert:user:";

    private static final int WINDOW_HOURS = 24;
    private static final int ALERT_THRESHOLD_PER_HOUR = 100;

    // 计数器名称 → Redis field / MySQL 列名映射
    private static final Map<String, String> FIELD_MAP = Map.of(
            "rag.tokens.input", "ragTokensInput",
            "rag.tokens.output", "ragTokensOutput",
            "ppt.tokens.input", "pptTokensInput",
            "ppt.tokens.output", "pptTokensOutput",
            "rag.docs", "ragDocCount",
            "rag.slices", "ragSliceCount",
            "ppt.generated", "pptCount"
    );

    public MonitoringService(UserMapper userMapper, RedisUtils redisUtils) {
        this.userMapper = userMapper;
        this.redisUtils = redisUtils;
    }

    /**
     * 计数器递增（核心热路径，每次 AI 调用）
     */
    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }

    public void incrementCounter(String name, long delta) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            userId = 0L; // MCP等无用户上下文的请求，记到全局用户0
        }
        String field = FIELD_MAP.get(name);
        if (field == null) {
            log.warn("未知指标名称: {}", name);
            return;
        }
        // Redis 原子递增
        redisUtils.hIncrement(METRICS_PREFIX + userId, field, delta);
        // 全局聚合
        redisUtils.hIncrement(GLOBAL_KEY, field, delta);
        // 滑动窗口记录（仅记录 token 类指标）
        if (name.contains("tokens")) {
            recordWindow(userId);
        }
    }

    /**
     * 滑动窗口：记录每次请求的时间戳
     */
    private void recordWindow(Long userId) {
        String key = WINDOW_PREFIX + userId;
        double now = System.currentTimeMillis();
        redisUtils.zAdd(key, UUID.randomUUID().toString(), now);
        // 清理 24 小时前的记录
        long cutoff = System.currentTimeMillis() - (long) WINDOW_HOURS * 3600_000L;
        redisUtils.zRemoveRangeByScore(key, 0, cutoff);
        redisUtils.expire(key, WINDOW_HOURS + 1, TimeUnit.HOURS);
    }

    /**
     * 异常检测：1小时内请求数是否超过阈值
     * @return true 表示检测到异常
     */
    public boolean checkAnomaly(Long userId) {
        String key = WINDOW_PREFIX + userId;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        Long count = redisUtils.zCount(key, cutoff, Double.MAX_VALUE);
        if (count != null && count > ALERT_THRESHOLD_PER_HOUR) {
            // 限流告警：每用户每小时最多告警一次
            String alertKey = ALERT_PREFIX + userId;
            Boolean set = redisUtils.setIfAbsent(alertKey, "1", 1, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(set)) {
                log.warn("用户 {} 1小时内请求 {} 次，触发告警", userId, count);
            }
            return true;
        }
        return false;
    }

    /**
     * 获取用户最近1小时的请求数
     */
    public Long getRequestCountLastHour(Long userId) {
        String key = WINDOW_PREFIX + userId;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        Long count = redisUtils.zCount(key, cutoff, Double.MAX_VALUE);
        return count != null ? count : 0L;
    }

    /**
     * 获取单个计数器
     */
    public long getCounter(String name) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return 0;
        return getCounterForUser(userId, name);
    }

    public long getCounterForUser(Long userId, String name) {
        String field = FIELD_MAP.get(name);
        if (field == null) return 0;
        // 优先从 Redis 读
        Object val = redisUtils.hGet(METRICS_PREFIX + userId, field);
        if (val != null) {
            return toLong(val);
        }
        // Redis 没数据，回源 MySQL
        return loadFromDb(userId, name);
    }

    /**
     * 获取所有指标（Redis 优先，回源 MySQL）
     */
    public Map<String, Object> getAllMetrics() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return Collections.emptyMap();

        String key = METRICS_PREFIX + userId;
        Map<Object, Object> raw = redisUtils.hGetAll(key);

        if (!raw.isEmpty()) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("ragTokensInput", toLong(raw.getOrDefault("ragTokensInput", 0L)));
            metrics.put("ragTokensOutput", toLong(raw.getOrDefault("ragTokensOutput", 0L)));
            metrics.put("pptTokensInput", toLong(raw.getOrDefault("pptTokensInput", 0L)));
            metrics.put("pptTokensOutput", toLong(raw.getOrDefault("pptTokensOutput", 0L)));
            metrics.put("ragDocCount", toLong(raw.getOrDefault("ragDocCount", 0L)));
            metrics.put("ragSliceCount", toLong(raw.getOrDefault("ragSliceCount", 0L)));
            metrics.put("pptCount", toLong(raw.getOrDefault("pptCount", 0L)));
            long totalInput = toLong(raw.getOrDefault("ragTokensInput", 0L)) + toLong(raw.getOrDefault("pptTokensInput", 0L));
            long totalOutput = toLong(raw.getOrDefault("ragTokensOutput", 0L)) + toLong(raw.getOrDefault("pptTokensOutput", 0L));
            metrics.put("totalTokensInput", totalInput);
            metrics.put("totalTokensOutput", totalOutput);
            metrics.put("requestCountLastHour", getRequestCountLastHour(userId));
            return metrics;
        }

        // Redis 没数据，回源 MySQL 并回填 Redis
        return loadAllFromDbAndCache(userId);
    }

    /**
     * 获取全局聚合指标
     */
    public Map<String, Object> getGlobalMetrics() {
        Map<Object, Object> raw = redisUtils.hGetAll(GLOBAL_KEY);
        if (!raw.isEmpty()) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("totalRagTokensInput", toLong(raw.getOrDefault("ragTokensInput", 0L)));
            metrics.put("totalRagTokensOutput", toLong(raw.getOrDefault("ragTokensOutput", 0L)));
            metrics.put("totalPptTokensInput", toLong(raw.getOrDefault("pptTokensInput", 0L)));
            metrics.put("totalPptTokensOutput", toLong(raw.getOrDefault("pptTokensOutput", 0L)));
            return metrics;
        }
        return Collections.emptyMap();
    }

    /**
     * 重置用户指标
     */
    public void resetMetrics() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return;
        // 清 Redis
        redisUtils.delete(METRICS_PREFIX + userId);
        redisUtils.delete(WINDOW_PREFIX + userId);
        redisUtils.delete(ALERT_PREFIX + userId);
        // 清 MySQL
        userMapper.resetCounters(userId);
        log.info("用户指标已重置: userId={}", userId);
    }

    /**
     * 从 MySQL 加载单个指标并回填 Redis
     */
    private long loadFromDb(Long userId, String name) {
        User user = userMapper.selectById(userId);
        if (user == null) return 0;
        long val = switch (name) {
            case "rag.tokens.input" -> zeroIfNull(user.getRagTokensInput());
            case "rag.tokens.output" -> zeroIfNull(user.getRagTokensOutput());
            case "ppt.tokens.input" -> zeroIfNull(user.getPptTokensInput());
            case "ppt.tokens.output" -> zeroIfNull(user.getPptTokensOutput());
            case "rag.docs" -> zeroIfNull(user.getRagDocCount());
            case "rag.slices" -> zeroIfNull(user.getRagSliceCount());
            case "ppt.generated" -> zeroIfNull(user.getPptCount());
            default -> 0;
        };
        // 回填 Redis
        String field = FIELD_MAP.get(name);
        if (field != null) {
            redisUtils.hSet(METRICS_PREFIX + userId, field, val);
        }
        return val;
    }

    /**
     * 从 MySQL 加载全部指标并回填 Redis
     */
    private Map<String, Object> loadAllFromDbAndCache(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return Collections.emptyMap();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("ragTokensInput", zeroIfNull(user.getRagTokensInput()));
        metrics.put("ragTokensOutput", zeroIfNull(user.getRagTokensOutput()));
        metrics.put("pptTokensInput", zeroIfNull(user.getPptTokensInput()));
        metrics.put("pptTokensOutput", zeroIfNull(user.getPptTokensOutput()));
        metrics.put("ragDocCount", zeroIfNull(user.getRagDocCount()));
        metrics.put("ragSliceCount", zeroIfNull(user.getRagSliceCount()));
        metrics.put("pptCount", zeroIfNull(user.getPptCount()));
        metrics.put("requestCountLastHour", getRequestCountLastHour(userId));
        long totalInput = zeroIfNull(user.getRagTokensInput()) + zeroIfNull(user.getPptTokensInput());
        long totalOutput = zeroIfNull(user.getRagTokensOutput()) + zeroIfNull(user.getPptTokensOutput());
        metrics.put("totalTokensInput", totalInput);
        metrics.put("totalTokensOutput", totalOutput);

        // 回填 Redis
        String key = METRICS_PREFIX + userId;
        redisUtils.hSet(key, "ragTokensInput", user.getRagTokensInput() != null ? user.getRagTokensInput() : 0L);
        redisUtils.hSet(key, "ragTokensOutput", user.getRagTokensOutput() != null ? user.getRagTokensOutput() : 0L);
        redisUtils.hSet(key, "pptTokensInput", user.getPptTokensInput() != null ? user.getPptTokensInput() : 0L);
        redisUtils.hSet(key, "pptTokensOutput", user.getPptTokensOutput() != null ? user.getPptTokensOutput() : 0L);
        redisUtils.hSet(key, "ragDocCount", user.getRagDocCount() != null ? user.getRagDocCount() : 0L);
        redisUtils.hSet(key, "ragSliceCount", user.getRagSliceCount() != null ? user.getRagSliceCount() : 0L);
        redisUtils.hSet(key, "pptCount", user.getPptCount() != null ? user.getPptCount() : 0L);
        redisUtils.expire(key, 30, TimeUnit.DAYS);

        return metrics;
    }

    /**
     * 获取所有有指标数据的用户 key（供刷盘任务使用）
     */
    public Set<String> getAllUserMetricKeys() {
        return redisUtils.keys(METRICS_PREFIX + "*");
    }

    /**
     * 从 Redis key 提取 userId
     */
    public Long extractUserId(String key) {
        try {
            return Long.parseLong(key.substring(METRICS_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取用户的 Redis 指标原始数据
     */
    public Map<Object, Object> getUserMetricsRaw(Long userId) {
        return redisUtils.hGetAll(METRICS_PREFIX + userId);
    }

    /**
     * 获取所有用户的滑动窗口 key（供每日归档使用）
     */
    public Set<String> getAllWindowKeys() {
        return redisUtils.keys(WINDOW_PREFIX + "*");
    }

    private long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private long zeroIfNull(Long val) {
        return val != null ? val : 0;
    }
}
