package com.javaee.docai.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 向量存储
 * 使用Redis实现向量存储和检索
 * 支持近似最近邻搜索
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private static final String VECTOR_PREFIX = "vector:";
    private static final String METADATA_PREFIX = "metadata:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 存储向量
     * @param id 文档ID
     * @param vector 向量
     * @param metadata 元数据
     */
    public void store(String id, float[] vector, Map<String, Object> metadata) {
        log.info("存储向量: id={}, dimension={}", id, vector.length);

        try {
            String vectorKey = VECTOR_PREFIX + id;
            String metadataKey = METADATA_PREFIX + id;

            // 将float[]转换为List<Float>以便Redis序列化
            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            redisTemplate.opsForValue().set(vectorKey, vectorList);
            redisTemplate.opsForHash().putAll(metadataKey, metadata);

            log.info("向量存储成功: id={}", id);
        } catch (Exception e) {
            log.error("向量存储失败", e);
            throw new RuntimeException("向量存储失败: " + e.getMessage(), e);
        }
    }

    /**
     * 搜索相似向量
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        log.info("搜索相似向量: topK={}", topK);

        try {
            Set<String> keys = redisTemplate.keys(VECTOR_PREFIX + "*");
            
            if (keys == null || keys.isEmpty()) {
                log.warn("没有找到任何向量数据");
                return Collections.emptyList();
            }

            List<SearchResult> results = new ArrayList<>();
            
            for (String key : keys) {
                Object storedObj = redisTemplate.opsForValue().get(key);
                float[] storedVector = convertToFloatArray(storedObj);
                if (storedVector != null) {
                    float similarity = cosineSimilarity(queryVector, storedVector);
                    String id = key.substring(VECTOR_PREFIX.length());
                    results.add(new SearchResult(id, similarity));
                }
            }

            results.sort((a, b) -> Float.compare(b.similarity, a.similarity));
            
            List<Map<String, Object>> finalResults = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, results.size()); i++) {
                SearchResult result = results.get(i);
                Map<String, Object> item = new HashMap<>();
                item.put("id", result.id);
                item.put("similarity", result.similarity);
                item.putAll(getMetadata(result.id));
                finalResults.add(item);
            }

            log.info("搜索完成，找到{}个结果", finalResults.size());
            return finalResults;
        } catch (Exception e) {
            log.error("向量搜索失败", e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除向量
     * @param id 文档ID
     */
    public void delete(String id) {
        log.info("删除向量: id={}", id);

        try {
            redisTemplate.delete(VECTOR_PREFIX + id);
            redisTemplate.delete(METADATA_PREFIX + id);
            log.info("向量删除成功: id={}", id);
        } catch (Exception e) {
            log.error("向量删除失败", e);
            throw new RuntimeException("向量删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取元数据
     */
    private Map<String, Object> getMetadata(String id) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(METADATA_PREFIX + id);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            log.warn("获取元数据失败", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 计算余弦相似度
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0f;
        }

        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0.0f;
        }

        return dotProduct / (float)(Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 将对象转换为float数组
     * Redis存储时可能被序列化为ArrayList
     */
    private float[] convertToFloatArray(Object obj) {
        if (obj == null) {
            log.warn("存储的向量为空");
            return null;
        }
        
        if (obj instanceof float[]) {
            return (float[]) obj;
        }
        
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Float) {
                    result[i] = (Float) item;
                } else if (item instanceof Double) {
                    result[i] = ((Double) item).floatValue();
                } else if (item instanceof Number) {
                    result[i] = ((Number) item).floatValue();
                } else {
                    log.warn("无法转换向量元素: {}", item);
                    result[i] = 0.0f;
                }
            }
            return result;
        }
        
        log.warn("无法转换向量对象: {}", obj.getClass().getName());
        return null;
    }

    /**
     * 搜索结果内部类
     */
    private static class SearchResult {
        String id;
        float similarity;

        SearchResult(String id, float similarity) {
            this.id = id;
            this.similarity = similarity;
        }
    }
}