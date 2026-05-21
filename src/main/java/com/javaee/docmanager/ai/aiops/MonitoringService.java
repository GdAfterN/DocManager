package com.javaee.docmanager.ai.aiops;

import com.javaee.docmanager.security.UserContext;
import com.javaee.docmanager.user.entity.User;
import com.javaee.docmanager.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 监控服务
 * 基于数据库的用户级指标持久化
 */
@Component
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final UserMapper userMapper;

    // 计数器名称 → 数据库字段名映射
    private static final Map<String, String> FIELD_MAP = Map.of(
            "rag.tokens.input", "rag_tokens_input",
            "rag.tokens.output", "rag_tokens_output",
            "ppt.tokens.input", "ppt_tokens_input",
            "ppt.tokens.output", "ppt_tokens_output",
            "rag.docs", "rag_doc_count",
            "rag.slices", "rag_slice_count",
            "ppt.generated", "ppt_count"
    );

    public MonitoringService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }

    public void incrementCounter(String name, long delta) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            log.warn("无法记录指标，用户未登录: name={}", name);
            return;
        }
        String field = FIELD_MAP.get(name);
        if (field == null) {
            log.warn("未知指标名称: {}", name);
            return;
        }
        try {
            userMapper.incrementField(userId, field, delta);
        } catch (Exception e) {
            log.error("更新用户指标失败: userId={}, field={}", userId, field, e);
        }
    }

    public long getCounter(String name) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return 0;
        return getCounterForUser(userId, name);
    }

    public long getCounterForUser(Long userId, String name) {
        User user = userMapper.selectById(userId);
        if (user == null) return 0;
        return switch (name) {
            case "rag.tokens.input" -> zeroIfNull(user.getRagTokensInput());
            case "rag.tokens.output" -> zeroIfNull(user.getRagTokensOutput());
            case "ppt.tokens.input" -> zeroIfNull(user.getPptTokensInput());
            case "ppt.tokens.output" -> zeroIfNull(user.getPptTokensOutput());
            case "rag.docs" -> zeroIfNull(user.getRagDocCount());
            case "rag.slices" -> zeroIfNull(user.getRagSliceCount());
            case "ppt.generated" -> zeroIfNull(user.getPptCount());
            default -> 0;
        };
    }

    public Map<String, Object> getAllMetrics() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return Collections.emptyMap();
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
        return metrics;
    }

    public void resetMetrics() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return;
        userMapper.resetCounters(userId);
        log.info("用户指标已重置: userId={}", userId);
    }

    private long zeroIfNull(Long val) {
        return val != null ? val : 0;
    }
}
