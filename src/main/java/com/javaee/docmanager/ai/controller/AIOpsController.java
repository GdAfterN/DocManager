package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.aiops.MonitoringService;
import com.javaee.docmanager.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 系统监控控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/aiops")
@Tag(name = "系统监控", description = "RAG/PPT用量统计与AI分析")
@RequiredArgsConstructor
public class AIOpsController {

    private final MonitoringService monitoringService;

    @Value("${ai.anthropic.api-key:}")
    private String apiKey;

    @Value("${ai.anthropic.base-url:https://api.anthropic.com}")
    private String apiBaseUrl;

    @Value("${ai.anthropic.chat.model:claude-sonnet-4-20250514}")
    private String chatModel;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/monitor")
    @Operation(summary = "获取监控指标")
    public Result<Map<String, Object>> getMetrics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // RAG 统计
        stats.put("ragSliceCount", monitoringService.getCounter("rag.slices"));
        stats.put("ragDocCount", monitoringService.getCounter("rag.docs"));
        stats.put("ragTokensInput", monitoringService.getCounter("rag.tokens.input"));
        stats.put("ragTokensOutput", monitoringService.getCounter("rag.tokens.output"));

        // PPT 统计
        stats.put("pptCount", monitoringService.getCounter("ppt.generated"));
        stats.put("pptTokensInput", monitoringService.getCounter("ppt.tokens.input"));
        stats.put("pptTokensOutput", monitoringService.getCounter("ppt.tokens.output"));

        // 总 token
        long totalInput = monitoringService.getCounter("rag.tokens.input")
                + monitoringService.getCounter("ppt.tokens.input");
        long totalOutput = monitoringService.getCounter("rag.tokens.output")
                + monitoringService.getCounter("ppt.tokens.output");
        stats.put("totalTokensInput", totalInput);
        stats.put("totalTokensOutput", totalOutput);

        return Result.success(stats);
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
        // 收集指标
        long ragSlices = monitoringService.getCounter("rag.slices");
        long ragDocs = monitoringService.getCounter("rag.docs");
        long ragIn = monitoringService.getCounter("rag.tokens.input");
        long ragOut = monitoringService.getCounter("rag.tokens.output");
        long pptCount = monitoringService.getCounter("ppt.generated");
        long pptIn = monitoringService.getCounter("ppt.tokens.input");
        long pptOut = monitoringService.getCounter("ppt.tokens.output");

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
                + "请分析：1) 使用模式 2) 成本分布 3) 优化建议";

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
