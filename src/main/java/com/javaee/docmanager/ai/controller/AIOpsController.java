package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.aiops.MetricsDaily;
import com.javaee.docmanager.ai.aiops.MetricsDailyMapper;
import com.javaee.docmanager.ai.aiops.MonitoringService;
import com.javaee.docmanager.common.model.Result;
import com.javaee.docmanager.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 系统监控控制器 — 基于 Redis 的实时指标 + MySQL 归档
 */
@RestController
@RequestMapping("/api/ai/aiops")
@Tag(name = "系统监控", description = "RAG/PPT用量统计与AI分析")
public class AIOpsController {

    private static final Logger log = LoggerFactory.getLogger(AIOpsController.class);

    private final MonitoringService monitoringService;
    private final MetricsDailyMapper metricsDailyMapper;

    public AIOpsController(MonitoringService monitoringService, MetricsDailyMapper metricsDailyMapper) {
        this.monitoringService = monitoringService;
        this.metricsDailyMapper = metricsDailyMapper;
    }

    @Value("${ai.anthropic.api-key:}")
    private String apiKey;

    @Value("${ai.anthropic.base-url:https://api.anthropic.com}")
    private String apiBaseUrl;

    @Value("${ai.anthropic.chat.model:claude-sonnet-4-20250514}")
    private String chatModel;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/monitor")
    @Operation(summary = "获取当前用户监控指标")
    public Result<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = monitoringService.getAllMetrics();
        return Result.success(metrics);
    }

    @GetMapping("/monitor/global")
    @Operation(summary = "获取全局聚合指标")
    public Result<Map<String, Object>> getGlobalMetrics() {
        Map<String, Object> metrics = monitoringService.getGlobalMetrics();
        return Result.success(metrics);
    }

    @GetMapping("/monitor/anomaly")
    @Operation(summary = "异常检测", description = "检测当前用户1小时内请求是否异常")
    public Result<Map<String, Object>> checkAnomaly() {
        Long userId = UserContext.getCurrentUserId();
        boolean isAnomaly = monitoringService.checkAnomaly(userId);
        long requestCount = monitoringService.getRequestCountLastHour(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anomaly", isAnomaly);
        result.put("requestCountLastHour", requestCount);
        result.put("threshold", 100);
        return Result.success(result);
    }

    @GetMapping("/monitor/daily")
    @Operation(summary = "获取每日趋势数据", description = "返回最近30天的每日指标快照")
    public Result<List<MetricsDaily>> getDailyTrends() {
        Long userId = UserContext.getCurrentUserId();
        List<MetricsDaily> data = metricsDailyMapper.selectByUserId(userId);
        return Result.success(data);
    }

    @GetMapping("/monitor/daily/range")
    @Operation(summary = "按日期范围查询趋势")
    public Result<List<MetricsDaily>> getDailyTrendsByRange(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        Long userId = UserContext.getCurrentUserId();
        List<MetricsDaily> data = metricsDailyMapper.selectByUserIdAndDateRange(userId, startDate, endDate);
        return Result.success(data);
    }

    @PostMapping("/reset")
    @Operation(summary = "重置指标")
    public Result<Void> resetMetrics() {
        monitoringService.resetMetrics();
        return Result.success();
    }

    @GetMapping("/analyze")
    @Operation(summary = "AI智能分析", description = "用AI分析当前系统使用情况并给出建议")
    public Result<Map<String, String>> analyze() {
        Long userId = UserContext.getCurrentUserId();

        long ragSlices = monitoringService.getCounterForUser(userId, "rag.slices");
        long ragDocs = monitoringService.getCounterForUser(userId, "rag.docs");
        long ragIn = monitoringService.getCounterForUser(userId, "rag.tokens.input");
        long ragOut = monitoringService.getCounterForUser(userId, "rag.tokens.output");
        long pptCount = monitoringService.getCounterForUser(userId, "ppt.generated");
        long pptIn = monitoringService.getCounterForUser(userId, "ppt.tokens.input");
        long pptOut = monitoringService.getCounterForUser(userId, "ppt.tokens.output");
        long reqLastHour = monitoringService.getRequestCountLastHour(userId);

        // 获取最近7天趋势
        List<MetricsDaily> recent = metricsDailyMapper.selectByUserId(userId);
        StringBuilder trendSb = new StringBuilder();
        if (!recent.isEmpty()) {
            trendSb.append("\n\n【近7天趋势】\n");
            for (int i = 0; i < Math.min(7, recent.size()); i++) {
                MetricsDaily d = recent.get(i);
                trendSb.append(String.format("- %s: RAG tokens in=%d out=%d, PPT数=%d\n",
                        d.getDate(), d.getRagTokensInput(), d.getRagTokensOutput(), d.getPptCount()));
            }
        }

        String prompt = "你是系统运维分析师。以下是DocAI系统的使用数据，请用中文给出简洁的分析和建议（200字以内）：\n\n"
                + "【RAG知识库】\n"
                + "- 已索引文档数：" + ragDocs + "\n"
                + "- 总切片数：" + ragSlices + "\n"
                + "- 消耗input tokens：" + ragIn + "\n"
                + "- 消耗output tokens：" + ragOut + "\n\n"
                + "【PPT生成】\n"
                + "- 已生成PPT数：" + pptCount + "\n"
                + "- 消耗input tokens：" + pptIn + "\n"
                + "- 消耗output tokens：" + pptOut + "\n\n"
                + "【实时状态】\n"
                + "- 最近1小时请求数：" + reqLastHour + "\n"
                + trendSb
                + "\n请分析：1) 使用模式 2) 成本分布 3) 优化建议";

        try {
            String analysis = callLlmSimple(prompt, 1000);
            return Result.success(Map.of("analysis", analysis));
        } catch (Exception e) {
            log.error("AI分析失败", e);
            return Result.success(Map.of("analysis", "AI分析暂时不可用：" + e.getMessage()));
        }
    }

    private String callLlmSimple(String prompt, int maxTokens) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        RestTemplate restTemplate = new RestTemplate(factory);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("max_tokens", maxTokens);

        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        body.put("messages", Collections.singletonList(userMessage));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            String url = apiBaseUrl + "/v1/messages";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode contentArray = root.path("content");
            if (contentArray.isArray() && contentArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                return sb.toString();
            }
            return "AI未返回内容";
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
