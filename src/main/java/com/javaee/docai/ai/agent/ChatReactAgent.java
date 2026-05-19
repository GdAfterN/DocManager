package com.javaee.docai.ai.agent;

import com.javaee.docai.ai.conversation.ConversationManager;
import com.javaee.docai.ai.conversation.ContextManager;
import com.javaee.docai.ai.rag.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Chat ReAct Agent
 * 基于ReAct模式实现对话功能
 * 支持多轮对话和上下文管理
 * 实现业务咨询、告警自救、工单预处理等场景
 */
@Component
public class ChatReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatReactAgent.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private PromptEngineeringService promptEngineeringService;

    @Autowired
    private ConversationManager conversationManager;

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private KnowledgeBase knowledgeBase;

    /**
     * 处理对话请求
     * @param conversationId 对话ID
     * @param userInput 用户输入
     * @param context 上下文信息
     * @return 对话响应
     */
    public Map<String, Object> chat(String conversationId, String userInput, Map<String, Object> context) {
        log.info("处理对话请求: conversationId={}, userInput={}", conversationId, userInput);

        try {
            List<String> history = conversationManager.getConversationHistory(conversationId);
            String knowledgeContext = retrieveKnowledge(userInput);

            String answer = chatService.callChatApi(
                promptEngineeringService.createQAPrompt(userInput, knowledgeContext, history)
            );

            conversationManager.addMessage(conversationId, userInput, answer);
            contextManager.updateContext(conversationId, context);

            return Map.of(
                "status", "success",
                "conversationId", conversationId,
                "answer", answer,
                "context", contextManager.getContext(conversationId)
            );
        } catch (Exception e) {
            log.error("对话处理失败", e);
            return Map.of(
                "status", "error",
                "message", "对话处理失败: " + e.getMessage()
            );
        }
    }

    /**
     * 开始新对话
     * @param userId 用户ID
     * @return 对话ID
     */
    public String startConversation(String userId) {
        String conversationId = conversationManager.createConversation(userId);
        log.info("创建新对话: userId={}, conversationId={}", userId, conversationId);
        return conversationId;
    }

    /**
     * 结束对话
     * @param conversationId 对话ID
     * @return 是否成功
     */
    public boolean endConversation(String conversationId) {
        conversationManager.deleteConversation(conversationId);
        contextManager.clearContext(conversationId);
        log.info("结束对话: conversationId={}", conversationId);
        return true;
    }

    /**
     * 获取对话历史
     * @param conversationId 对话ID
     * @return 对话历史
     */
    public List<String> getHistory(String conversationId) {
        return conversationManager.getConversationHistory(conversationId);
    }

    /**
     * 检索知识库
     * @param query 查询词
     * @return 知识库上下文
     */
    private String retrieveKnowledge(String query) {
        try {
            List<Map<String, Object>> results = knowledgeBase.search(query, 3);
            StringBuilder context = new StringBuilder();
            for (Map<String, Object> result : results) {
                context.append(result.get("content")).append("\n");
            }
            return context.toString();
        } catch (Exception e) {
            log.warn("知识库检索失败", e);
            return "";
        }
    }
}
