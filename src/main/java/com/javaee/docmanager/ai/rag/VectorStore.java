package com.javaee.docmanager.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * 向量存储
 * 使用 Qdrant REST API 实现向量存储和 HNSW 索引检索
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private static final String COLLECTION = "documents_v2";
    private static final int VECTOR_SIZE = 1024;

    @Autowired
    private RestClient qdrantRestClient;

    @Autowired
    private ObjectMapper objectMapper;

    private boolean collectionReady = false;

    /**
     * 确保集合存在
     */
    private void ensureCollection() {
        if (collectionReady) return;
        try {
            // 创建集合（幂等，已存在则忽略）
            String body = """
                {
                    "vectors": {
                        "size": %d,
                        "distance": "Cosine"
                    }
                }
                """.formatted(VECTOR_SIZE);

            String response = qdrantRestClient.put()
                    .uri("/collections/" + COLLECTION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.info("Qdrant 集合 '{}' 就绪", COLLECTION);
            collectionReady = true;
        } catch (Exception e) {
            log.warn("Qdrant 集合创建失败（可能已存在）: {}", e.getMessage());
            collectionReady = true;
        }
    }

    /**
     * 从 chunkId 生成确定性 UUID（相同输入总是相同输出）
     */
    private UUID toPointId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes());
    }

    /**
     * 存储向量
     */
    public void store(String id, float[] vector, Map<String, Object> metadata) {
        ensureCollection();
        log.info("存储向量到 Qdrant: chunkId={}, dimension={}", id, vector.length);

        try {
            UUID pointId = toPointId(id);

            // 构建 payload JSON（包含 chunkId 用于搜索时反查）
            StringBuilder payloadJson = new StringBuilder("{");
            payloadJson.append("\"chunkId\":\"").append(escapeJson(id)).append("\"");
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                payloadJson.append(",");
                payloadJson.append("\"").append(entry.getKey()).append("\":");
                Object val = entry.getValue();
                if (val instanceof String) {
                    payloadJson.append("\"").append(escapeJson((String) val)).append("\"");
                } else if (val instanceof Number || val instanceof Boolean) {
                    payloadJson.append(val);
                } else {
                    payloadJson.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
                }
            }
            payloadJson.append("}");

            // 构建向量 JSON
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) vectorJson.append(",");
                vectorJson.append(vector[i]);
            }
            vectorJson.append("]");

            String body = """
                {
                    "points": [
                        {
                            "id": "%s",
                            "vector": %s,
                            "payload": %s
                        }
                    ]
                }
                """.formatted(pointId, vectorJson, payloadJson);

            qdrantRestClient.put()
                    .uri("/collections/" + COLLECTION + "/points")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("向量存储成功: chunkId={}, pointId={}", id, pointId);
        } catch (Exception e) {
            log.error("向量存储失败", e);
            throw new RuntimeException("向量存储失败: " + e.getMessage(), e);
        }
    }

    /**
     * 搜索相似向量（HNSW 索引，O(log n)）
     */
    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        ensureCollection();
        log.info("Qdrant 搜索相似向量: topK={}", topK);

        try {
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < queryVector.length; i++) {
                if (i > 0) vectorJson.append(",");
                vectorJson.append(queryVector[i]);
            }
            vectorJson.append("]");

            String body = """
                {
                    "vector": %s,
                    "limit": %d,
                    "with_payload": true
                }
                """.formatted(vectorJson, topK);

            String response = qdrantRestClient.post()
                    .uri("/collections/" + COLLECTION + "/points/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode resultNode = root.get("result");

            List<Map<String, Object>> results = new ArrayList<>();
            if (resultNode != null && resultNode.isArray()) {
                for (JsonNode point : resultNode) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("similarity", point.get("score").floatValue());

                    // 提取 payload，用 chunkId 作为结果 id
                    JsonNode payload = point.get("payload");
                    if (payload != null) {
                        JsonNode chunkIdNode = payload.get("chunkId");
                        item.put("id", chunkIdNode != null ? chunkIdNode.asText() : point.get("id").asText());

                        Iterator<String> fields = payload.fieldNames();
                        while (fields.hasNext()) {
                            String field = fields.next();
                            if ("chunkId".equals(field)) continue;
                            JsonNode val = payload.get(field);
                            if (val.isTextual()) {
                                item.put(field, val.asText());
                            } else if (val.isNumber()) {
                                item.put(field, val.asDouble());
                            } else if (val.isBoolean()) {
                                item.put(field, val.asBoolean());
                            } else {
                                item.put(field, val.asText());
                            }
                        }
                    } else {
                        item.put("id", point.get("id").asText());
                    }

                    results.add(item);
                }
            }

            log.info("搜索完成，找到 {} 个结果", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败", e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除向量
     */
    public void delete(String chunkId) {
        ensureCollection();
        UUID pointId = toPointId(chunkId);
        log.info("从 Qdrant 删除向量: chunkId={}, pointId={}", chunkId, pointId);

        try {
            String body = """
                {
                    "points": ["%s"]
                }
                """.formatted(pointId);

            qdrantRestClient.post()
                    .uri("/collections/" + COLLECTION + "/points/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("向量删除成功: chunkId={}, pointId={}", chunkId, pointId);
        } catch (Exception e) {
            log.error("向量删除失败", e);
            throw new RuntimeException("向量删除失败: " + e.getMessage(), e);
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
