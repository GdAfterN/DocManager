package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.rag.KnowledgeBase;
import com.javaee.docmanager.ai.rag.Reranker;
import com.javaee.docmanager.ai.rag.VectorStore;
import com.javaee.docmanager.ai.agent.ChatService;
import com.javaee.docmanager.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/rag")
@Tag(name = "RAG知识库", description = "知识库索引、搜索、问答接口")
public class RagController {

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private Reranker reranker;

    @Autowired
    private ChatService chatService;

    /**
     * 文档索引
     */
    @PostMapping("/index")
    @Operation(summary = "文档索引", description = "将文档添加到知识库")
    public Result<Void> indexDocument(
            @Parameter(description = "文档ID") @RequestParam String documentId,
            @Parameter(description = "文档内容") @RequestBody String content) {
        knowledgeBase.addDocument(documentId, content, Map.of());
        return Result.success();
    }

    /**
     * 基础向量检索
     */
    @GetMapping("/search")
    @Operation(summary = "基础检索", description = "使用向量相似度搜索知识库")
    public Result<List<Map<String, Object>>> search(
            @Parameter(description = "查询词") @RequestParam String query,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "5") int topK) {
        List<Map<String, Object>> results = knowledgeBase.search(query, topK);
        return Result.success(results);
    }

    /**
     * 混合检索（向量检索 + BM25）
     */
    @GetMapping("/search/hybrid")
    @Operation(summary = "混合检索", description = "使用向量检索和BM25检索的混合方式搜索")
    public Result<List<Map<String, Object>>> hybridSearch(
            @Parameter(description = "查询词") @RequestParam String query,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "5") int topK) {
        List<Map<String, Object>> results = knowledgeBase.hybridSearch(query, topK);
        return Result.success(results);
    }

    /**
     * 混合检索加重排序
     */
    @GetMapping("/search/hybrid/rerank")
    @Operation(summary = "混合检索加重排序", description = "混合检索后使用指定策略进行重排序")
    public Result<List<Map<String, Object>>> hybridSearchWithRerank(
            @Parameter(description = "查询词") @RequestParam String query,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "5") int topK,
            @Parameter(description = "重排序策略: BM25_FUSION, CROSS_ENCODER, HYBRID") 
            @RequestParam(defaultValue = "HYBRID") String strategy) {
        
        Reranker.RerankStrategy rerankStrategy;
        try {
            rerankStrategy = Reranker.RerankStrategy.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.fail("无效的重排序策略: " + strategy);
        }
        
        List<Map<String, Object>> results = knowledgeBase.hybridSearchWithRerank(query, topK, rerankStrategy);
        return Result.success(results);
    }

    /**
     * 获取支持的重排序策略
     */
    @GetMapping("/rerank/strategies")
    @Operation(summary = "获取重排序策略", description = "获取所有支持的重排序策略")
    public Result<List<String>> getRerankStrategies() {
        List<String> strategies = reranker.getSupportedStrategies();
        return Result.success(strategies);
    }

    /**
     * 知识库问答（使用混合检索加重排序）
     */
    @PostMapping("/query")
    @Operation(summary = "知识库问答", description = "基于知识库进行问答，默认使用混合检索加重排序")
    public Result<Map<String, Object>> query(
            @Parameter(description = "问题") @RequestBody String question,
            @Parameter(description = "策略") @RequestParam(defaultValue = "hybrid") String strategy) {

        // 1. 检索相关文档
        List<Map<String, Object>> results;
        if ("rerank".equalsIgnoreCase(strategy)) {
            results = knowledgeBase.hybridSearchWithRerank(question, 5, Reranker.RerankStrategy.HYBRID);
        } else if ("vector".equalsIgnoreCase(strategy)) {
            results = knowledgeBase.search(question, 5);
        } else {
            results = knowledgeBase.hybridSearch(question, 5);
        }

        // 2. 拼接上下文（标注来源文档）
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> result = results.get(i);
            Object content = result.get("content");
            if (content != null) {
                String fileName = (String) result.get("fileName");
                context.append("【片段 ").append(i + 1).append(" - ").append(fileName != null ? fileName : "未知文档").append("】\n");
                context.append(content).append("\n\n");
            }
        }

        String contextStr = context.toString().trim();

        // 3. 调用大模型生成答案
        String answerText;
        if (contextStr.isEmpty()) {
            answerText = "知识库中没有找到与您问题相关的文档内容。请先上传文档到知识库后再提问。";
        } else {
            String prompt = "你是一个严格基于参考资料回答问题的助手。请遵守以下规则：\n"
                    + "1. 只能根据下方【参考资料】中的内容回答，禁止使用你自己的知识\n"
                    + "2. 如果参考资料中没有与问题相关的内容，必须回答\"参考资料中未找到相关信息\"\n"
                    + "3. 回答时引用参考资料中的原文，不要改写或概括\n\n"
                    + "【参考资料】\n" + contextStr + "\n\n"
                    + "【用户问题】" + question + "\n\n"
                    + "【回答】";
            try {
                answerText = chatService.callChatApi(prompt, "rag.tokens");
            } catch (Exception e) {
                answerText = "调用AI模型失败: " + e.getMessage();
            }
        }

        Map<String, Object> answer = new HashMap<>();
        answer.put("question", question);
        answer.put("answer", answerText);
        // 去重来源文档
        Map<String, String> seenDocs = new LinkedHashMap<>();
        for (Map<String, Object> r : results) {
            String docId = (String) r.get("id");
            if (docId != null && !seenDocs.containsKey(docId)) {
                seenDocs.put(docId, (String) r.get("fileName"));
            }
        }
        answer.put("sources", seenDocs.entrySet().stream().map(e -> {
            Map<String, Object> source = new HashMap<>();
            source.put("id", e.getKey());
            source.put("fileName", e.getValue());
            return source;
        }).toList());
        answer.put("strategy", strategy);

        return Result.success(answer);
    }

    /**
     * 获取文档内容
     */
    @GetMapping("/document/{documentId}")
    @Operation(summary = "获取文档内容", description = "获取知识库中的文档内容")
    public Result<String> getDocument(
            @Parameter(description = "文档ID") @PathVariable String documentId) {
        String content = knowledgeBase.getDocumentContent(documentId);
        return Result.success(content);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/document/{documentId}")
    @Operation(summary = "删除文档", description = "从知识库删除文档")
    public Result<Void> deleteDocument(
            @Parameter(description = "文档ID") @PathVariable String documentId) {
        knowledgeBase.removeDocument(documentId);
        return Result.success();
    }

    /**
     * 获取所有文档ID
     */
    @GetMapping("/documents")
    @Operation(summary = "获取文档列表", description = "获取知识库中的所有文档ID")
    public Result<List<String>> getAllDocuments() {
        List<String> documentIds = knowledgeBase.getAllDocumentIds();
        return Result.success(documentIds);
    }

    /**
     * 获取文档元数据
     */
    @GetMapping("/document/{documentId}/metadata")
    @Operation(summary = "获取文档元数据", description = "获取文档的元数据信息")
    public Result<Map<String, Object>> getDocumentMetadata(
            @Parameter(description = "文档ID") @PathVariable String documentId) {
        Map<String, Object> metadata = knowledgeBase.getDocumentMetadata(documentId);
        return Result.success(metadata);
    }
}
