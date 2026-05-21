package com.javaee.docmanager.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RedisDistributedLock {

    @Autowired
    private StringRedisTemplate redisTemplate;

    public boolean tryLock(String key, long expireSeconds) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "1", expireSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    public void unlock(String key) {
        redisTemplate.delete(key);
    }
}
