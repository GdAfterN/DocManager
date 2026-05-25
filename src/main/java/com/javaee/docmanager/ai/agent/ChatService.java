package com.javaee.docmanager.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.docmanager.ai.aiops.MonitoringService;
import com.javaee.docmanager.ai.mcp.McpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 自定义Chat服务
 * 适配Anthropic兼容接口协议
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.anthropic.api-key}")
    private String apiKey;

    @Value("${ai.anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${ai.anthropic.chat.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${ai.anthropic.max-tokens:4096}")
    private int maxTokens;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private McpClient mcpClient;

    /**
     * 带Function Calling的Chat API调用
     * 如果配置了外部MCP Server，会自动注入工具列表
     * Claude决定是否调用工具，本方法自动处理tool_use循环
     */
    public String callChatApiWithTools(String prompt) {
        List<Map<String, Object>> tools = mcpClient.getAllTools();
        if (tools.isEmpty()) {
            // 没有外部工具，退化为普通调用
            return callChatApi(prompt, "chat.tokens");
        }

        log.info("带工具调用Chat API: tools={}", tools.size());

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);

        // 最多循环5轮（防止无限循环）
        for (int round = 0; round < 5; round++) {
            Map<String, Object> response = doCallWithTools(messages, tools);

            JsonNode root;
            try {
                root = objectMapper.readTree((String) response.get("body"));
            } catch (Exception e) {
                throw new RuntimeException("解析API响应失败: " + e.getMessage(), e);
            }

            // 记录 token 用量
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            monitoringService.incrementCounter("chat.tokens.input", inputTokens);
            monitoringService.incrementCounter("chat.tokens.output", outputTokens);

            JsonNode contentArray = root.path("content");
            if (!contentArray.isArray() || contentArray.size() == 0) {
                throw new RuntimeException("Chat API返回结果为空");
            }

            // 检查是否包含 tool_use
            List<Map<String, Object>> toolUseBlocks = new ArrayList<>();
            StringBuilder textResult = new StringBuilder();

            for (JsonNode block : contentArray) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    textResult.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    Map<String, Object> toolUse = new LinkedHashMap<>();
                    toolUse.put("id", block.path("id").asText());
                    toolUse.put("name", block.path("name").asText());
                    toolUse.put("input", objectMapper.convertValue(block.path("input"), Map.class));
                    toolUseBlocks.add(toolUse);
                }
            }

            // 没有工具调用，返回文本
            if (toolUseBlocks.isEmpty()) {
                return textResult.toString();
            }

            // 有工具调用：把 assistant 的 tool_use 请求加入消息历史
            List<Map<String, Object>> assistantContent = new ArrayList<>();
            for (JsonNode block : contentArray) {
                assistantContent.add(objectMapper.convertValue(block, Map.class));
            }
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantContent);
            messages.add(assistantMsg);

            // 执行每个工具调用，收集结果
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (Map<String, Object> toolUse : toolUseBlocks) {
                String toolId = (String) toolUse.get("id");
                String toolName = (String) toolUse.get("name");
                Map<String, Object> arguments = (Map<String, Object>) toolUse.get("input");

                log.info("执行工具调用: {}({})", toolName, arguments);
                String result = mcpClient.callTool(toolName, arguments);

                Map<String, Object> toolResult = new LinkedHashMap<>();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", toolId);
                toolResult.put("content", result);
                toolResults.add(toolResult);
            }

            Map<String, Object> toolResultMsg = new LinkedHashMap<>();
            toolResultMsg.put("role", "user");
            toolResultMsg.put("content", toolResults);
            messages.add(toolResultMsg);
        }

        return "工具调用次数超限，请简化问题后重试";
    }

    private Map<String, Object> doCallWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        RestTemplate restTemplate = new RestTemplate();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        body.put("tools", tools);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            String url = baseUrl + "/v1/messages";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statusCode", response.getStatusCode().value());
            result.put("body", response.getBody());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("调用Chat API失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用Anthropic兼容Chat API
     * @param prompt 用户提示词
     * @return 响应内容
     */
    public String callChatApi(String prompt) {
        return callChatApi(prompt, "chat.tokens");
    }

    public String callChatApi(String prompt, String counterPrefix) {
        log.info("调用Anthropic Chat API: model={}, prompt length={}", model, prompt.length());

        try {
            RestTemplate restTemplate = new RestTemplate();

            // 构建请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);

            Map<String, String> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            body.put("messages", Collections.singletonList(userMessage));

            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            String requestBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            String url = baseUrl + "/v1/messages";
            log.debug("请求URL: {}", url);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            // 解析响应
            JsonNode root = objectMapper.readTree(response.getBody());

            // 记录 token 用量
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            monitoringService.incrementCounter(counterPrefix + ".input", inputTokens);
            monitoringService.incrementCounter(counterPrefix + ".output", outputTokens);

            System.out.println("========== Chat响应内容开始 ==========");
            System.out.println(response.getBody());
            System.out.println("========== Chat响应内容结束 ==========");

            // 提取文本内容
            JsonNode contentArray = root.path("content");
            if (contentArray.isArray() && contentArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                String result = sb.toString();
                return result.isEmpty() ? "" : result;
            }

            throw new RuntimeException("Chat API返回结果为空");

        } catch (Exception e) {
            log.error("调用Chat API失败", e);
            throw new RuntimeException("调用Chat API失败: " + e.getMessage(), e);
        }
    }
}
