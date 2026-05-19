package com.javaee.docai.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 知识库
 * 管理文档内容和元数据
 * 支持增量学习、知识更新和混合检索
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final String DOCUMENT_PREFIX = "doc:";
    private static final String CONTENT_PREFIX = "content:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DocumentVectorizer vectorizer;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private Reranker reranker;

    /**
     * 添加文档到知识库
     * @param documentId 文档ID
     * @param content 文档内容
     * @param metadata 元数据
     */
    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("添加文档到知识库: documentId={}", documentId);

        try {
            String docKey = DOCUMENT_PREFIX + documentId;
            String contentKey = CONTENT_PREFIX + documentId;

            redisTemplate.opsForValue().set(contentKey, content);
            redisTemplate.opsForHash().putAll(docKey, metadata);

            float[] vector = vectorizer.vectorize(content);
            vectorStore.store(documentId, vector, metadata);

            log.info("文档添加成功: documentId={}", documentId);
        } catch (Exception e) {
            log.error("添加文档失败", e);
            throw new RuntimeException("添加文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从知识库移除文档
     * @param documentId 文档ID
     */
    public void removeDocument(String documentId) {
        log.info("从知识库移除文档: documentId={}", documentId);

        try {
            redisTemplate.delete(DOCUMENT_PREFIX + documentId);
            redisTemplate.delete(CONTENT_PREFIX + documentId);
            vectorStore.delete(documentId);

            log.info("文档移除成功: documentId={}", documentId);
        } catch (Exception e) {
            log.error("移除文档失败", e);
            throw new RuntimeException("移除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取文档内容
     * @param documentId 文档ID
     * @return 文档内容
     */
    public String getDocumentContent(String documentId) {
        try {
            return (String) redisTemplate.opsForValue().get(CONTENT_PREFIX + documentId);
        } catch (Exception e) {
            log.warn("获取文档内容失败", e);
            return null;
        }
    }

    /**
     * 获取文档元数据
     * @param documentId 文档ID
     * @return 元数据
     */
    public Map<String, Object> getDocumentMetadata(String documentId) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(DOCUMENT_PREFIX + documentId);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            log.warn("获取文档元数据失败", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 搜索知识库（基础向量检索）
     * @param query 查询词
     * @param topK 返回数量
     * @return 搜索结果
     */
    public List<Map<String, Object>> search(String query, int topK) {
        log.info("搜索知识库: query={}, topK={}", query, topK);

        try {
            float[] queryVector = vectorizer.vectorize(query);
            List<Map<String, Object>> results = vectorStore.search(queryVector, topK);

            for (Map<String, Object> result : results) {
                String id = (String) result.get("id");
                String content = getDocumentContent(id);
                result.put("content", content);
            }

            return results;
        } catch (Exception e) {
            log.error("知识库搜索失败", e);
            throw new RuntimeException("知识库搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 混合检索（向量检索 + BM25检索）
     * @param query 查询词
     * @param topK 返回数量
     * @return 搜索结果
     */
    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        log.info("混合检索: query={}, topK={}", query, topK);

        try {
            // 第一阶段：向量检索获取候选集（扩大范围）
            float[] queryVector = vectorizer.vectorize(query);
            List<Map<String, Object>> vectorResults = vectorStore.search(queryVector, topK * 3);

            // 第二阶段：BM25检索
            List<Map<String, Object>> bm25Results = bm25Search(query, topK * 3);

            // 第三阶段：融合结果（去重）
            Set<String> seenIds = new HashSet<>();
            List<Map<String, Object>> combinedResults = new ArrayList<>();

            for (Map<String, Object> result : vectorResults) {
                String id = (String) result.get("id");
                if (!seenIds.contains(id)) {
                    seenIds.add(id);
                    result.put("content", getDocumentContent(id));
                    result.put("source", "vector");
                    combinedResults.add(result);
                }
            }

            for (Map<String, Object> result : bm25Results) {
                String id = (String) result.get("id");
                if (!seenIds.contains(id)) {
                    seenIds.add(id);
                    result.put("content", getDocumentContent(id));
                    result.put("source", "bm25");
                    combinedResults.add(result);
                }
            }

            return combinedResults.subList(0, Math.min(topK, combinedResults.size()));

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 混合检索加重排序
     * @param query 查询词
     * @param topK 返回数量
     * @param strategy 重排序策略
     * @return 搜索结果
     */
    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK, 
                                                             Reranker.RerankStrategy strategy) {
        log.info("混合检索加重排序: query={}, topK={}, strategy={}", query, topK, strategy);

        try {
            // 第一阶段：混合检索获取候选集
            List<Map<String, Object>> candidates = hybridSearch(query, topK * 3);

            // 第二阶段：重排序
            List<Map<String, Object>> results = reranker.rerank(query, candidates, strategy, topK);

            return results;
        } catch (Exception e) {
            log.error("混合检索加重排序失败", e);
            throw new RuntimeException("混合检索加重排序失败: " + e.getMessage(), e);
        }
    }

    /**
     * BM25检索（简化实现）
     */
    private List<Map<String, Object>> bm25Search(String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> docKeys = redisTemplate.keys(CONTENT_PREFIX + "*");

        if (docKeys == null || docKeys.isEmpty()) {
            return results;
        }

        for (String key : docKeys) {
            String docId = key.substring(CONTENT_PREFIX.length());
            String content = (String) redisTemplate.opsForValue().get(key);

            if (content != null) {
                float score = computeBM25(query, content);
                if (score > 0) {
                    results.add(Map.of(
                        "id", docId,
                        "similarity", score
                    ));
                }
            }
        }

        results.sort((a, b) -> Float.compare(
            ((Number) b.get("similarity")).floatValue(),
            ((Number) a.get("similarity")).floatValue()
        ));

        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 计算BM25分数
     */
    private float computeBM25(String query, String document) {
        if (query == null || document == null) {
            return 0.0f;
        }

        String[] queryTerms = query.toLowerCase().split("\\s+");
        String[] docTerms = document.toLowerCase().split("\\s+");

        int docLength = docTerms.length;
        if (docLength == 0) {
            return 0.0f;
        }

        float score = 0.0f;
        for (String term : queryTerms) {
            if (term.isEmpty()) continue;
            
            int termFreq = 0;
            for (String docTerm : docTerms) {
                if (docTerm.contains(term) || term.contains(docTerm)) {
                    termFreq++;
                }
            }

            if (termFreq > 0) {
                float tf = (float) termFreq / docLength;
                float bm25 = (float)(tf * (2.2 + 1) / (tf + 2.2));
                score += bm25;
            }
        }

        return score / queryTerms.length;
    }

    /**
     * 获取所有文档ID
     * @return 文档ID列表
     */
    public List<String> getAllDocumentIds() {
        try {
            Set<String> keys = redisTemplate.keys(DOCUMENT_PREFIX + "*");
            if (keys == null) {
                return Collections.emptyList();
            }
            return keys.stream()
                .map(key -> key.substring(DOCUMENT_PREFIX.length()))
                .toList();
        } catch (Exception e) {
            log.warn("获取文档ID列表失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 更新文档
     * @param documentId 文档ID
     * @param content 新内容
     * @param metadata 新元数据
     */
    public void updateDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("更新文档: documentId={}", documentId);
        removeDocument(documentId);
        addDocument(documentId, content, metadata);
    }
}
