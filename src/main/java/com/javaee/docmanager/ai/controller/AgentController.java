package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.agent.ChatReactAgent;
import com.javaee.docmanager.ai.agent.KnowledgeIndexAgent;
import com.javaee.docmanager.ai.agent.PlanExecuteAgent;
import com.javaee.docmanager.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent控制器
 * 提供AI Agent相关的REST API接口
 */
@RestController
@RequestMapping("/api/ai/agent")
@Tag(name = "AI Agent", description = "AI Agent相关接口")
public class AgentController {

    @Autowired
    private ChatReactAgent chatReactAgent;

    @Autowired
    private KnowledgeIndexAgent knowledgeIndexAgent;

    @Autowired
    private PlanExecuteAgent planExecuteAgent;

    /**
     * 对话Agent - 开始对话
     */
    @PostMapping("/chat/start")
    @Operation(summary = "开始对话", description = "创建新的对话会话")
    public Result<String> startConversation(
            @Parameter(description = "用户ID") @RequestParam(required = false, defaultValue = "default") String userId) {
        String conversationId = chatReactAgent.startConversation(userId);
        return Result.success(conversationId);
    }

    /**
     * 对话Agent - 发送消息
     */
    @PostMapping("/chat")
    @Operation(summary = "发送消息", description = "向对话Agent发送消息")
    public Result<Map<String, Object>> chat(
            @Parameter(description = "对话ID") @RequestParam String conversationId,
            @Parameter(description = "用户输入") @RequestBody String userInput) {
        Map<String, Object> result = chatReactAgent.chat(conversationId, userInput, Map.of());
        return Result.success(result);
    }

    /**
     * 对话Agent - 结束对话
     */
    @PostMapping("/chat/{conversationId}/end")
    @Operation(summary = "结束对话", description = "结束指定对话")
    public Result<Boolean> endConversation(
            @Parameter(description = "对话ID") @PathVariable String conversationId) {
        boolean success = chatReactAgent.endConversation(conversationId);
        return Result.success(success);
    }

    /**
     * 对话Agent - 获取对话历史
     */
    @GetMapping("/chat/{conversationId}/history")
    @Operation(summary = "获取对话历史", description = "获取对话的消息历史")
    public Result<List<String>> getConversationHistory(
            @Parameter(description = "对话ID") @PathVariable String conversationId) {
        List<String> history = chatReactAgent.getHistory(conversationId);
        return Result.success(history);
    }

    /**
     * 知识索引Agent - 索引文档
     */
    @PostMapping("/knowledge/index")
    @Operation(summary = "索引文档", description = "将文档添加到知识库索引")
    public Result<Map<String, Object>> indexDocument(
            @Parameter(description = "文档ID") @RequestParam String documentId,
            @Parameter(description = "文档内容") @RequestBody String content) {
        Map<String, Object> result = knowledgeIndexAgent.indexDocument(documentId, content, Map.of());
        return Result.success(result);
    }

    /**
     * 知识索引Agent - 搜索知识库
     */
    @GetMapping("/knowledge/search")
    @Operation(summary = "搜索知识库", description = "搜索知识库中的相关文档")
    public Result<List<Map<String, Object>>> searchKnowledge(
            @Parameter(description = "查询词") @RequestParam String query,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "5") int topK) {
        List<Map<String, Object>> results = knowledgeIndexAgent.searchKnowledge(query, topK);
        return Result.success(results);
    }

    /**
     * 知识索引Agent - 删除索引
     */
    @DeleteMapping("/knowledge/index/{documentId}")
    @Operation(summary = "删除索引", description = "删除文档索引")
    public Result<Map<String, Object>> deleteIndex(
            @Parameter(description = "文档ID") @PathVariable String documentId) {
        Map<String, Object> result = knowledgeIndexAgent.deleteIndex(documentId);
        return Result.success(result);
    }

    /**
     * 规划执行Agent - 执行任务
     */
    @PostMapping("/plan/execute")
    @Operation(summary = "执行规划任务", description = "使用Plan-Execute Agent执行复杂任务")
    public Result<Map<String, Object>> executePlan(
            @Parameter(description = "任务描述") @RequestBody String task) {
        Map<String, Object> result = planExecuteAgent.execute(task, Map.of());
        return Result.success(result);
    }
}
