package com.javaee.docmanager.ai.rag;

import com.javaee.docmanager.ai.aiops.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 知识库
 * 管理文档内容和元数据
 * 支持文档分块、混合检索
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
    private DocumentChunker chunker;

    @Autowired
    private Reranker reranker;

    @Autowired
    private MonitoringService monitoringService;

    /**
     * 添加文档到知识库（分块存储）
     */
    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("添加文档到知识库: documentId={}", documentId);

        try {
            String docKey = DOCUMENT_PREFIX + documentId;

            redisTemplate.opsForHash().putAll(docKey, metadata);
            deleteChunks(documentId);

            List<String> chunks = chunker.chunk(content);
            if (chunks.isEmpty()) {
                log.warn("文档分块为空，跳过向量化: documentId={}", documentId);
                return;
            }

            int successCount = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = documentId + "::" + i;
                String chunkKey = CONTENT_PREFIX + chunkId;
                redisTemplate.opsForValue().set(chunkKey, chunks.get(i));

                try {
                    float[] vector = vectorizer.vectorize(chunks.get(i));
                    vectorStore.store(chunkId, vector, metadata);
                    successCount++;
                } catch (Exception e) {
                    log.warn("分块向量化失败，跳过: chunkId={}, error={}", chunkId, e.getMessage());
                }
            }

            log.info("文档添加成功: documentId={}, chunks={}, vectorized={}", documentId, chunks.size(), successCount);
            monitoringService.incrementCounter("rag.docs");
            monitoringService.incrementCounter("rag.slices", successCount);
        } catch (Exception e) {
            log.error("添加文档失败", e);
            throw new RuntimeException("添加文档失败: " + e.getMessage(), e);
        }
    }

    public void removeDocument(String documentId) {
        log.info("从知识库移除文档: documentId={}", documentId);
        try {
            redisTemplate.delete(DOCUMENT_PREFIX + documentId);
            deleteChunks(documentId);
            log.info("文档移除成功: documentId={}", documentId);
        } catch (Exception e) {
            log.error("移除文档失败", e);
            throw new RuntimeException("移除文档失败: " + e.getMessage(), e);
        }
    }

    private void deleteChunks(String documentId) {
        // 从 Redis content key 找到所有 chunk
        String contentPattern = CONTENT_PREFIX + documentId + "::*";
        Set<String> contentKeys = redisTemplate.keys(contentPattern);
        if (contentKeys != null) {
            for (String contentKey : contentKeys) {
                String chunkId = contentKey.substring(CONTENT_PREFIX.length());
                // 从 Qdrant 删除向量
                try {
                    vectorStore.delete(chunkId);
                } catch (Exception e) {
                    log.warn("Qdrant 向量删除失败: chunkId={}", chunkId);
                }
                // 从 Redis 删除 content 和 metadata
                redisTemplate.delete(contentKey);
                redisTemplate.delete("metadata:" + chunkId);
            }
        }
    }

    public String getDocumentContent(String documentId) {
        try {
            // 从分块拼接全文，不再存储冗余的全文副本
            List<String> chunks = new ArrayList<>();
            int i = 0;
            while (true) {
                String chunkKey = CONTENT_PREFIX + documentId + "::" + i;
                String chunk = (String) redisTemplate.opsForValue().get(chunkKey);
                if (chunk == null) break;
                chunks.add(chunk);
                i++;
            }
            return chunks.isEmpty() ? null : String.join("\n", chunks);
        } catch (Exception e) {
            log.warn("获取文档内容失败", e);
            return null;
        }
    }

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
     * 基础向量检索
     */
    public List<Map<String, Object>> search(String query, int topK) {
        log.info("向量检索: query={}, topK={}", query, topK);
        try {
            float[] queryVector = vectorizer.vectorize(query);
            List<Map<String, Object>> rawResults = vectorStore.search(queryVector, topK);
            for (Map<String, Object> r : rawResults) {
                String chunkId = (String) r.get("id");
                r.put("chunkId", chunkId);
                r.put("content", getChunkContent(chunkId));
                r.put("fileName", getDocMetadata(extractDocId(chunkId), "fileName"));
            }
            return rawResults;
        } catch (Exception e) {
            log.error("向量检索失败", e);
            throw new RuntimeException("向量检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 混合检索加重排序
     */
    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                             Reranker.RerankStrategy strategy) {
        log.info("混合检索加重排序: query={}, topK={}, strategy={}", query, topK, strategy);
        List<Map<String, Object>> hybridResults = hybridSearch(query, topK * 3);
        // 确保有 similarity 字段供 Reranker 使用
        for (Map<String, Object> r : hybridResults) {
            if (!r.containsKey("similarity")) {
                r.put("similarity", r.getOrDefault("score", 0.0f));
            }
        }
        return reranker.rerank(query, hybridResults, strategy, topK);
    }

    private static final float MIN_SCORE_THRESHOLD = 0.15f;

    /**
     * 混合检索（向量 + 关键词，返回分块级别的结果）
     */
    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        log.info("混合检索: query={}, topK={}", query, topK);

        try {
            // 向量检索
            float[] queryVector = vectorizer.vectorize(query);
            List<Map<String, Object>> vectorResults = vectorStore.search(queryVector, topK * 5);

            // 关键词检索
            List<Map<String, Object>> keywordResults = keywordSearch(query, topK * 5);

            // 融合：先加向量结果，再补关键词命中的
            Map<String, Float> scoreMap = new LinkedHashMap<>();
            Map<String, Map<String, Object>> resultMap = new HashMap<>();

            // 向量结果：直接用原始余弦相似度，不归一化
            for (Map<String, Object> r : vectorResults) {
                String chunkId = (String) r.get("id");
                float vecScore = ((Number) r.get("similarity")).floatValue();
                scoreMap.put(chunkId, vecScore * 0.6f);
                resultMap.put(chunkId, r);
            }

            // 关键词结果融合
            for (Map<String, Object> r : keywordResults) {
                String chunkId = (String) r.get("id");
                float kwScore = ((Number) r.get("similarity")).floatValue();
                Float existing = scoreMap.get(chunkId);
                if (existing != null) {
                    scoreMap.put(chunkId, existing + kwScore * 0.4f);
                } else {
                    scoreMap.put(chunkId, kwScore * 0.4f);
                    resultMap.put(chunkId, r);
                }
            }

            // 按综合分数排序
            List<Map.Entry<String, Float>> sorted = new ArrayList<>(scoreMap.entrySet());
            sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

            // 过滤低分结果，不再按文档去重（允许同一文档的多个chunk同时命中）
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map.Entry<String, Float> entry : sorted) {
                if (results.size() >= topK) break;
                if (entry.getValue() < MIN_SCORE_THRESHOLD) continue;

                String chunkId = entry.getKey();
                String docId = extractDocId(chunkId);
                Map<String, Object> item = new HashMap<>(resultMap.get(chunkId));
                item.put("id", docId);
                item.put("chunkId", chunkId);
                item.put("content", getChunkContent(chunkId));
                item.put("score", entry.getValue());
                item.put("fileName", getDocMetadata(docId, "fileName"));
                results.add(item);
            }

            log.info("混合检索完成: 原始结果={}, 过滤后={}, 阈值={}", sorted.size(), results.size(), MIN_SCORE_THRESHOLD);
            return results;

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关键词检索（直接文本匹配，支持中英文 + bigram）
     */
    private List<Map<String, Object>> keywordSearch(String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (query == null || query.isBlank()) return results;

        // 提取关键词：英文按标点分词，中文按 bigram 切词
        Set<String> keywords = extractKeywords(query);

        Set<String> contentKeys = redisTemplate.keys(CONTENT_PREFIX + "*");
        if (contentKeys == null) return results;

        for (String key : contentKeys) {
            String chunkId = key.substring(CONTENT_PREFIX.length());
            if (!chunkId.contains("::")) continue;

            String content = (String) redisTemplate.opsForValue().get(key);
            if (content == null) continue;

            String contentLower = content.toLowerCase();
            int matchCount = 0;
            for (String kw : keywords) {
                if (contentLower.contains(kw)) {
                    matchCount++;
                }
            }
            if (matchCount > 0) {
                float score = (float) matchCount / keywords.size();
                // 标题命中加权
                String firstLine = contentLower.split("\n", 2)[0];
                for (String kw : keywords) {
                    if (firstLine.contains(kw)) {
                        score = Math.min(1.0f, score + 0.15f);
                        break;
                    }
                }
                Map<String, Object> item = new HashMap<>();
                item.put("id", chunkId);
                item.put("similarity", score);
                results.add(item);
            }
        }

        results.sort((a, b) -> Float.compare(
                ((Number) b.get("similarity")).floatValue(),
                ((Number) a.get("similarity")).floatValue()
        ));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 从查询中提取关键词：英文分词 + 中文 bigram + 完整查询
     */
    private Set<String> extractKeywords(String query) {
        Set<String> keywords = new HashSet<>();
        String lower = query.toLowerCase();

        // 英文分词
        for (String part : lower.split("[\\s\\p{Punct}]+")) {
            if (part.length() >= 2) {
                keywords.add(part);
            }
        }

        // 中文 bigram：提取连续的中文字符，每两个相邻字符组成一个 bigram
        StringBuilder chineseChars = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chineseChars.append(c);
            } else {
                // 遇到非中文字符，对已积累的中文做 bigram
                addBigrams(chineseChars.toString(), keywords);
                chineseChars.setLength(0);
            }
        }
        addBigrams(chineseChars.toString(), keywords);

        // 完整查询（去空格）作为整体匹配
        keywords.add(query.toLowerCase().replaceAll("\\s+", ""));

        return keywords;
    }

    private void addBigrams(String chinese, Set<String> keywords) {
        for (int i = 0; i < chinese.length() - 1; i++) {
            keywords.add(chinese.substring(i, i + 2));
        }
        // 单字也加入（处理只有一个中文关键词的情况）
        if (chinese.length() == 1) {
            keywords.add(chinese);
        }
    }

    private String extractDocId(String chunkId) {
        int idx = chunkId.indexOf("::");
        return idx > 0 ? chunkId.substring(0, idx) : chunkId;
    }

    private String getChunkContent(String chunkId) {
        return (String) redisTemplate.opsForValue().get(CONTENT_PREFIX + chunkId);
    }

    private String getDocMetadata(String docId, String key) {
        Object val = redisTemplate.opsForHash().get(DOCUMENT_PREFIX + docId, key);
        return val != null ? val.toString() : "";
    }

    public List<String> getAllDocumentIds() {
        try {
            Set<String> keys = redisTemplate.keys(DOCUMENT_PREFIX + "*");
            if (keys == null) return Collections.emptyList();
            return keys.stream()
                .map(key -> key.substring(DOCUMENT_PREFIX.length()))
                .toList();
        } catch (Exception e) {
            log.warn("获取文档ID列表失败", e);
            return Collections.emptyList();
        }
    }

    public void updateDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("更新文档: documentId={}", documentId);
        removeDocument(documentId);
        addDocument(documentId, content, metadata);
    }

    public void indexDocument(String documentId, String content, String fileName, String fileType) {
        log.info("索引文档: documentId={}, fileName={}", documentId, fileName);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", fileName);
        metadata.put("fileType", fileType);
        metadata.put("uploadTime", System.currentTimeMillis());
        updateDocument(documentId, content, metadata);
    }
}
