package com.javaee.docmanager.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 通用缓存服务
 * 实现缓存穿透（空值缓存）、缓存击穿（逻辑过期+异步重建）、缓存雪崩（TTL随机偏移）
 */
@Slf4j
@Service
public class CacheService {

    private static final String CACHE_NULL_VALUE = "NULL";
    private static final long NULL_VALUE_TTL = 5; // 空值缓存5分钟
    private static final long LOGICAL_EXPIRE_DEFAULT = 30; // 逻辑过期默认30分钟

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisDistributedLock distributedLock;

    private final Random random = new Random();

    /**
     * 查询缓存（防穿透：缓存空值）
     * @param key 缓存key
     * @param type 返回类型
     * @param dbFallback 数据库查询函数
     * @param ttl 过期时间（分钟）
     * @return 查询结果
     */
    public <T> T queryWithPassThrough(String key, Class<T> type, Supplier<T> dbFallback, long ttl) {
        // 1. 查询Redis缓存
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            if (CACHE_NULL_VALUE.equals(json)) {
                return null; // 空值缓存命中
            }
            try {
                return objectMapper.readValue(json, type);
            } catch (JsonProcessingException e) {
                log.error("缓存反序列化失败: key={}", key, e);
            }
        }

        // 2. 缓存未命中，查询数据库
        T data = dbFallback.get();
        if (data == null) {
            // 缓存空值（短TTL），防止缓存穿透
            redisTemplate.opsForValue().set(key, CACHE_NULL_VALUE, NULL_VALUE_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 3. 写入缓存（加随机偏移防止雪崩）
        try {
            long actualTtl = ttl + random.nextInt((int) (ttl / 2 + 1));
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(data), actualTtl, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.error("缓存序列化失败: key={}", key, e);
        }
        return data;
    }

    /**
     * 查询缓存（防击穿：逻辑过期+异步重建）
     * @param key 缓存key
     * @param type 返回类型
     * @param dbFallback 数据库查询函数
     * @param expireMinutes 逻辑过期时间（分钟）
     * @return 查询结果
     */
    public <T> T queryWithLogicalExpire(String key, Class<T> type, Supplier<T> dbFallback, long expireMinutes) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }

        try {
            RedisData<T> redisData = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructParametricType(RedisData.class, type));

            // 检查逻辑过期时间
            if (redisData.getExpireTime().isAfter(java.time.LocalDateTime.now())) {
                // 未过期，直接返回
                return redisData.getData();
            }

            // 已过期，尝试获取分布式锁重建缓存
            String lockKey = "lock:" + key;
            boolean locked = distributedLock.tryLock(lockKey, 10);
            if (locked) {
                try {
                    // 异步重建缓存
                    new Thread(() -> {
                        try {
                            T newData = dbFallback.get();
                            RedisData<T> newRedisData = new RedisData<>();
                            newRedisData.setData(newData);
                            newRedisData.setExpireTime(
                                    java.time.LocalDateTime.now().plusMinutes(expireMinutes));
                            redisTemplate.opsForValue().set(key,
                                    objectMapper.writeValueAsString(newRedisData));
                        } catch (Exception e) {
                            log.error("异步重建缓存失败: key={}", key, e);
                        } finally {
                            distributedLock.unlock(lockKey);
                        }
                    }).start();
                } catch (Exception e) {
                    distributedLock.unlock(lockKey);
                }
            }

            // 返回旧数据（过期但还能用）
            return redisData.getData();
        } catch (Exception e) {
            log.error("逻辑过期缓存处理失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 设置缓存（带逻辑过期）
     */
    public <T> void setWithLogicalExpire(String key, T data, long expireMinutes) {
        try {
            RedisData<T> redisData = new RedisData<>();
            redisData.setData(data);
            redisData.setExpireTime(java.time.LocalDateTime.now().plusMinutes(expireMinutes));
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(redisData));
        } catch (Exception e) {
            log.error("设置逻辑过期缓存失败: key={}", key, e);
        }
    }

    /**
     * 删除缓存（更新DB后调用）
     */
    public void deleteCache(String key) {
        redisTemplate.delete(key);
    }
}
