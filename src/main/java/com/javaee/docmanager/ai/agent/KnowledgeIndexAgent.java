package com.javaee.docmanager.ai.agent;

import com.javaee.docmanager.ai.rag.DocumentVectorizer;
import com.javaee.docmanager.ai.rag.KnowledgeBase;
import com.javaee.docmanager.ai.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识索引Agent
 * 负责文档向量化和知识库索引
 * 支持文档自动分类和标签生成
 * 实现文档内容的结构化提取
 */
@Component
public class KnowledgeIndexAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexAgent.class);

    @Autowired
    private DocumentVectorizer documentVectorizer;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 索引文档
     * @param documentId 文档ID
     * @param content 文档内容
     * @param metadata 文档元数据
     * @return 索引结果
     */
    public Map<String, Object> indexDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("开始索引文档: documentId={}", documentId);

        try {
            String tags = generateTags(content);
            String category = classifyDocument(content);

            // 处理metadata为null或不可变Map的情况
            if (metadata == null || metadata.isEmpty()) {
                metadata = new HashMap<>();
            } else {
                // 如果metadata是不可变的Map，创建一个新的HashMap
                try {
                    metadata.put("_test", "test");
                    metadata.remove("_test");
                } catch (UnsupportedOperationException e) {
                    metadata = new HashMap<>(metadata);
                }
            }
            metadata.put("tags", tags);
            metadata.put("category", category);

            knowledgeBase.addDocument(documentId, content, metadata);

            log.info("文档索引完成: documentId={}, category={}", documentId, category);
            return Map.of(
                "status", "success",
                "documentId", documentId,
                "category", category,
                "tags", tags,
                "message", "文档索引成功"
            );
        } catch (Exception e) {
            log.error("文档索引失败", e);
            return Map.of(
                "status", "error",
                "documentId", documentId,
                "message", "文档索引失败: " + e.getMessage()
            );
        }
    }

    /**
     * 查询知识库
     * @param query 查询词
     * @param topK 返回数量
     * @return 查询结果
     */
    public List<Map<String, Object>> searchKnowledge(String query, int topK) {
        log.info("搜索知识库: query={}", query);

        try {
            float[] queryVector = documentVectorizer.vectorize(query);
            return vectorStore.search(queryVector, topK);
        } catch (Exception e) {
            log.error("知识库搜索失败", e);
            throw new RuntimeException("知识库搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文档标签
     * @param content 文档内容
     * @return 标签字符串（逗号分隔）
     */
    private String generateTags(String content) {
        return "技术文档,AI,文档处理";
    }

    /**
     * 文档分类
     * @param content 文档内容
     * @return 分类名称
     */
    private String classifyDocument(String content) {
        if (content.contains("代码") || content.contains("编程") || content.contains("开发")) {
            return "技术文档";
        } else if (content.contains("报告") || content.contains("分析")) {
            return "分析报告";
        } else if (content.contains("会议") || content.contains("纪要")) {
            return "会议记录";
        }
        return "其他";
    }

    /**
     * 删除文档索引
     * @param documentId 文档ID
     * @return 删除结果
     */
    public Map<String, Object> deleteIndex(String documentId) {
        log.info("删除文档索引: documentId={}", documentId);

        try {
            vectorStore.delete(documentId);
            knowledgeBase.removeDocument(documentId);

            return Map.of(
                "status", "success",
                "documentId", documentId,
                "message", "索引删除成功"
            );
        } catch (Exception e) {
            log.error("删除索引失败", e);
            return Map.of(
                "status", "error",
                "documentId", documentId,
                "message", "索引删除失败: " + e.getMessage()
            );
        }
    }
}