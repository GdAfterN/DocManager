package com.javaee.docai.ai.aiops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控服务
 * 收集和管理系统监控数据
 * 支持性能指标的采集和分析
 */
@Component
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final String METRIC_PREFIX = "metric:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final ConcurrentHashMap<String, Long> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Long>> timers = new ConcurrentHashMap<>();

    /**
     * 记录指标计数
     * @param name 指标名称
     */
    public void incrementCounter(String name) {
        counters.merge(name, 1L, Long::sum);
        log.debug("计数器增量: name={}", name);
    }

    /**
     * 记录指标计数（带增量）
     * @param name 指标名称
     * @param delta 增量
     */
    public void incrementCounter(String name, long delta) {
        counters.merge(name, delta, Long::sum);
        log.debug("计数器增量: name={}, delta={}", name, delta);
    }

    /**
     * 记录耗时指标
     * @param name 指标名称
     * @param durationMs 耗时（毫秒）
     */
    public void recordTimer(String name, long durationMs) {
        timers.computeIfAbsent(name, k -> new ArrayList<>()).add(durationMs);
        log.debug("记录耗时: name={}, duration={}ms", name, durationMs);
    }

    /**
     * 获取计数器值
     * @param name 指标名称
     * @return 计数值
     */
    public long getCounter(String name) {
        return counters.getOrDefault(name, 0L);
    }

    /**
     * 获取计时器统计
     * @param name 指标名称
     * @return 统计信息
     */
    public Map<String, Object> getTimerStats(String name) {
        List<Long> values = timers.get(name);
        if (values == null || values.isEmpty()) {
            return Map.of("count", 0, "min", 0, "max", 0, "avg", 0);
        }

        long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        double avg = values.stream().mapToLong(Long::longValue).average().orElse(0);

        return Map.of(
            "count", values.size(),
            "min", min,
            "max", max,
            "avg", avg
        );
    }

    /**
     * 获取所有指标
     * @return 指标映射
     */
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            metrics.put(entry.getKey() + ".count", entry.getValue());
        }
        
        for (String name : timers.keySet()) {
            metrics.put(name + ".stats", getTimerStats(name));
        }
        
        return metrics;
    }

    /**
     * 保存指标到Redis
     */
    public void saveMetrics() {
        String key = METRIC_PREFIX + System.currentTimeMillis();
        redisTemplate.opsForHash().putAll(key, getAllMetrics());
        redisTemplate.expire(key, java.time.Duration.ofHours(24));
        log.debug("指标已保存: key={}", key);
    }

    /**
     * 重置指标
     */
    public void resetMetrics() {
        counters.clear();
        timers.clear();
        log.info("监控指标已重置");
    }

    /**
     * 健康检查
     * @return 健康状态
     */
    public Map<String, Object> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("metrics", getAllMetrics());
        return health;
    }
}
