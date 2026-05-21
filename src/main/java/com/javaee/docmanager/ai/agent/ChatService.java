package com.javaee.docmanager.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.docmanager.ai.aiops.MonitoringService;
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
