package com.javaee.docai.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 重排序器
 * 支持多种重排序策略：BM25融合、Cross-Encoder、自定义规则等
 */
@Component
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    /**
     * 重排序策略枚举
     */
    public enum RerankStrategy {
        BM25_FUSION,      // BM25与向量相似度融合
        CROSS_ENCODER,    // Cross-Encoder重排序
        HYBRID            // 混合策略
    }

    /**
     * 重排序结果
     */
    public static class RerankResult {
        private String documentId;
        private float originalScore;
        private float rerankScore;
        private float finalScore;

        public RerankResult(String documentId, float originalScore, float rerankScore, float finalScore) {
            this.documentId = documentId;
            this.originalScore = originalScore;
            this.rerankScore = rerankScore;
            this.finalScore = finalScore;
        }

        public String getDocumentId() { return documentId; }
        public float getOriginalScore() { return originalScore; }
        public float getRerankScore() { return rerankScore; }
        public float getFinalScore() { return finalScore; }
    }

    /**
     * 对检索结果进行重排序
     * @param query 查询词
     * @param candidates 候选结果列表（包含id, similarity, content）
     * @param strategy 重排序策略
     * @param topK 返回数量
     * @return 重排序后的结果
     */
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> candidates, 
                                            RerankStrategy strategy, int topK) {
        log.info("开始重排序: strategy={}, candidates={}", strategy, candidates.size());

        List<RerankResult> rerankResults = new ArrayList<>();

        for (Map<String, Object> candidate : candidates) {
            String docId = (String) candidate.get("id");
            float originalScore = ((Number) candidate.get("similarity")).floatValue();
            String content = (String) candidate.get("content");

            float rerankScore = 0.0f;
            float finalScore = originalScore;

            switch (strategy) {
                case BM25_FUSION:
                    rerankScore = computeBM25(query, content);
                    finalScore = fuseScores(originalScore, rerankScore, 0.6f, 0.4f);
                    break;
                case CROSS_ENCODER:
                    rerankScore = computeCrossEncoder(query, content);
                    finalScore = rerankScore;
                    break;
                case HYBRID:
                    float bm25Score = computeBM25(query, content);
                    float ceScore = computeCrossEncoder(query, content);
                    rerankScore = (bm25Score + ceScore) / 2;
                    finalScore = fuseScores(originalScore, rerankScore, 0.5f, 0.5f);
                    break;
            }

            rerankResults.add(new RerankResult(docId, originalScore, rerankScore, finalScore));
        }

        // 按最终分数排序
        rerankResults.sort((a, b) -> Float.compare(b.getFinalScore(), a.getFinalScore()));

        // 构建返回结果
        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(topK, rerankResults.size());
        
        for (int i = 0; i < limit; i++) {
            RerankResult result = rerankResults.get(i);
            Map<String, Object> candidate = findCandidateById(candidates, result.getDocumentId());
            
            Map<String, Object> finalResult = new HashMap<>(candidate);
            finalResult.put("originalScore", result.getOriginalScore());
            finalResult.put("rerankScore", result.getRerankScore());
            finalResult.put("finalScore", result.getFinalScore());
            results.add(finalResult);
        }

        log.info("重排序完成，返回{}条结果", results.size());
        return results;
    }

    /**
     * 计算BM25分数（简化实现）
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
        int totalDocs = 1000; // 假设总文档数
        
        for (String term : queryTerms) {
            if (term.isEmpty()) continue;
            
            int termFreq = 0;
            for (String docTerm : docTerms) {
                if (docTerm.contains(term) || term.contains(docTerm)) {
                    termFreq++;
                }
            }

            if (termFreq > 0) {
                float idf = (float) Math.log((totalDocs + 0.5) / (1 + 0.5));
                float tf = (float) termFreq / docLength;
                float bm25 = (float)(idf * tf * (2.2 + 1) / (tf + 2.2 * (1 - 0.75 + 0.75 * docLength / 200)));
                score += bm25;
            }
        }

        // 归一化到[0,1]
        return Math.min(1.0f, score / queryTerms.length);
    }

    /**
     * 计算Cross-Encoder分数（模拟实现）
     * 实际应调用专门的Cross-Encoder模型
     */
    private float computeCrossEncoder(String query, String document) {
        if (query == null || document == null) {
            return 0.0f;
        }

        // 简单模拟：基于关键词匹配和语义重叠
        String queryLower = query.toLowerCase();
        String docLower = document.toLowerCase();

        int matchCount = 0;
        String[] queryTerms = queryLower.split("\\s+");
        
        for (String term : queryTerms) {
            if (docLower.contains(term)) {
                matchCount++;
            }
        }

        // 计算位置权重（关键词出现在文档开头权重更高）
        float positionBonus = 0.0f;
        for (String term : queryTerms) {
            int idx = docLower.indexOf(term);
            if (idx >= 0 && idx < docLower.length() / 3) {
                positionBonus += 0.1f;
            }
        }

        float baseScore = (float) matchCount / queryTerms.length;
        return Math.min(1.0f, baseScore + positionBonus);
    }

    /**
     * 融合两个分数
     * @param score1 分数1
     * @param score2 分数2
     * @param weight1 分数1权重
     * @param weight2 分数2权重
     * @return 融合后的分数
     */
    private float fuseScores(float score1, float score2, float weight1, float weight2) {
        return score1 * weight1 + score2 * weight2;
    }

    /**
     * 根据ID查找候选结果
     */
    private Map<String, Object> findCandidateById(List<Map<String, Object>> candidates, String id) {
        return candidates.stream()
            .filter(c -> id.equals(c.get("id")))
            .findFirst()
            .orElse(null);
    }

    /**
     * 获取所有支持的重排序策略
     */
    public List<String> getSupportedStrategies() {
        List<String> strategies = new ArrayList<>();
        for (RerankStrategy strategy : RerankStrategy.values()) {
            strategies.add(strategy.name());
        }
        return strategies;
    }
}
