package com.javaee.docmanager.ai.rag;

import com.javaee.docmanager.ai.aiops.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
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

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DocumentVectorizer vectorizer;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private DocumentChunker chunker;

    @Autowired
    private SemanticChunker semanticChunker;

    @Autowired
    private Reranker reranker;

    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired(required = false)
    private ElasticsearchOperations elasticsearchOperations;

    /**
     * 添加文档到知识库（两阶段分块：结构化粗切分 + 语义细分块）
     */
    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        addDocument(documentId, content, null, metadata);
    }

    /**
     * 添加文档到知识库（支持原始文件数据用于PDF/Word按页/标题切分）
     */
    public void addDocument(String documentId, String content, byte[] rawData, Map<String, Object> metadata) {
        log.info("添加文档到知识库: documentId={}", documentId);

        try {
            String docKey = DOCUMENT_PREFIX + documentId;

            redisTemplate.opsForHash().putAll(docKey, metadata);
            deleteChunks(documentId);

            String fileType = metadata.getOrDefault("fileType", "").toString();
            String fileName = metadata.getOrDefault("fileName", "").toString();

            // === 第一阶段：结构化粗切分 ===
            long t1 = System.currentTimeMillis();
            List<String> structuralChunks = chunker.chunkByStructure(content, rawData, fileType, fileName);
            log.info("[分块 Stage1 结构化切分] 耗时={}ms, 粗块数={}", System.currentTimeMillis() - t1, structuralChunks.size());

            // === 第二阶段：语义细分块 ===
            long t2 = System.currentTimeMillis();
            List<String> chunks = semanticChunker.chunk(structuralChunks);
            log.info("[分块 Stage2 语义切分] 耗时={}ms, 最终块数={}", System.currentTimeMillis() - t2, chunks.size());

            if (chunks.isEmpty()) {
                log.warn("文档分块为空，跳过向量化: documentId={}", documentId);
                return;
            }

            String originalFileName = metadata.getOrDefault("fileName", "").toString();

            // === 向量化并存储 ===
            int successCount = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = documentId + "::" + i;

                indexToEs(chunkId, chunks.get(i), documentId, originalFileName);

                try {
                    float[] vector = vectorizer.vectorize(chunks.get(i));
                    vectorStore.store(chunkId, vector, metadata);
                    successCount++;
                } catch (Exception e) {
                    log.warn("分块向量化失败，跳过: chunkId={}, error={}", chunkId, e.getMessage());
                }
            }

            log.info("文档添加成功: documentId={}, 粗块={}, 最终块={}, vectorized={}",
                    documentId, structuralChunks.size(), chunks.size(), successCount);
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
        // 从 ES 查找该文档的所有 chunk
        List<String> chunkIds = getChunkIdsByDocId(documentId);
        for (String chunkId : chunkIds) {
            // 从 Qdrant 删除向量
            try {
                vectorStore.delete(chunkId);
            } catch (Exception e) {
                log.warn("Qdrant 向量删除失败: chunkId={}", chunkId);
            }
            // 从 ES 删除
            deleteFromEs(chunkId);
        }
    }

    /**
     * 从 ES 查询某文档的所有 chunkId
     */
    private List<String> getChunkIdsByDocId(String documentId) {
        List<String> chunkIds = new ArrayList<>();
        if (elasticsearchOperations == null) return chunkIds;
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.term(t -> t.field("docId").value(documentId)))
                    .withMaxResults(1000)
                    .build();
            SearchHits<ChunkDocument> hits = elasticsearchOperations.search(query, ChunkDocument.class);
            for (SearchHit<ChunkDocument> hit : hits) {
                chunkIds.add(hit.getContent().getChunkId());
            }
        } catch (Exception e) {
            log.warn("ES 查询 chunkId 列表失败: docId={}, error={}", documentId, e.getMessage());
        }
        return chunkIds;
    }

    public String getDocumentContent(String documentId) {
        try {
            // 从 ES 按 docId 查询所有 chunk，按 chunkId 排序拼接
            if (elasticsearchOperations == null) return null;
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.term(t -> t.field("docId").value(documentId)))
                    .withMaxResults(1000)
                    .build();
            SearchHits<ChunkDocument> hits = elasticsearchOperations.search(query, ChunkDocument.class);
            if (hits.isEmpty()) return null;

            // 按 chunkId 中的索引号排序
            List<ChunkDocument> docs = hits.stream()
                    .map(SearchHit::getContent)
                    .sorted(Comparator.comparingInt(d -> {
                        String id = d.getChunkId();
                        int idx = id.indexOf("::");
                        return idx > 0 ? Integer.parseInt(id.substring(idx + 2)) : 0;
                    }))
                    .toList();

            StringBuilder sb = new StringBuilder();
            for (ChunkDocument doc : docs) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(doc.getContent());
            }
            return sb.toString();
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
            // 查询改写：用改写后的中文语义查询做向量检索
            QueryRewriter.QueryParts parts = queryRewriter.extract(query);
            float[] queryVector = vectorizer.vectorize(parts.rewrittenForVector);
            List<Map<String, Object>> rawResults = vectorStore.search(queryVector, topK);
            for (Map<String, Object> r : rawResults) {
                String chunkId = (String) r.get("id");
                r.put("chunkId", chunkId);
                r.put("content", getExpandedContent(chunkId));
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
    private static final float RERANK_SCORE_THRESHOLD = 0.35f;

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
        List<Map<String, Object>> reranked = reranker.rerank(query, hybridResults, strategy, topK);

        // Rerank 后按 rerank 分数阈值过滤
        if (strategy == Reranker.RerankStrategy.DASHSCOPE_RERANK) {
            int before = reranked.size();
            reranked = reranked.stream()
                    .filter(r -> {
                        Object score = r.get("rerankScore");
                        return score != null && ((Number) score).floatValue() >= RERANK_SCORE_THRESHOLD;
                    })
                    .collect(java.util.stream.Collectors.toList());
            log.info("Rerank 阈值过滤: 阈值={}, 前={}, 后={}", RERANK_SCORE_THRESHOLD, before, reranked.size());
        }
        return reranked;
    }

    private static final float MIN_SCORE_THRESHOLD = 0.3f;

    /**
     * 动态计算向量/关键词融合权重
     * 根据查询特征自适应：包含技术词、数字、型号等精确实体时提高关键词权重
     */
    private float[] computeFusionWeights(String query, boolean hasTechTerms) {
        if (query == null || query.isBlank()) {
            return new float[]{0.8f, 0.2f};
        }

        String lower = query.toLowerCase();
        int specificityScore = 0;

        // 包含技术词（由 QueryRewriter 检测的英文标识符、版本号等）
        if (hasTechTerms) {
            specificityScore += 2;
        }
        // 包含数字（型号、版本、规格参数）
        if (lower.matches(".*\\d+.*")) {
            specificityScore += 1;
        }
        // 包含精确查询关键词（按领域分组，覆盖 Java 后端 + Agent 开发）
        String[] specificPatterns = {
                // 通用
                "型号", "版本", "参数", "配置", "价格", "规格", "编号", "方法", "接口", "函数",
                // Java 核心
                "类", "对象", "注解", "异常", "泛型", "枚举", "继承", "实现", "重写", "重载",
                "抽象", "接口", "内部类", "匿名类", "静态", "final", "构造器", "析构", "装箱", "拆箱",
                "集合", "列表", "映射", "集合", "迭代器", "比较器", "流", "Optional", "Record",
                // JVM
                "堆", "栈", "gc", "垃圾回收", "类加载", "双亲委派", "字节码", "jit", "调优", "oom",
                "内存泄漏", "栈溢出", "新生代", "老年代", "可达性分析",
                // 并发
                "线程", "并发", "锁", "队列", "线程池", "死锁", "活锁", "信号量", "屏障", "原子类",
                "volatile", "synchronized", "cas", "aqs", "阻塞", "非阻塞", "协程",
                // Spring
                "启动", "注入", "依赖", "切面", "代理", "反射", "回调", "监听器", "过滤器", "拦截器",
                "bean", "ioc", "aop", "mvc", "boot", "cloud", "starter", "自动装配", "条件注解",
                "事务", "传播", "隔离", "声明式", "编程式",
                // 数据库
                "索引", "查询", "事务", "连接池", "序列化", "慢查询", "执行计划", "分库", "分表",
                "主从", "读写分离", "迁移", "回滚", "死锁", "行锁", "表锁", "mvcc",
                // 缓存/消息
                "缓存", "穿透", "击穿", "雪崩", "淘汰", "过期", "持久化", "哨兵", "集群", "分片",
                "消息", "队列", "topic", "消费", "生产", "ack", "重试", "幂等", "死信", "延迟",
                // 网络/Web
                "请求", "响应", "路由", "中间件", "跨域", "csrf", "xss", "jwt", "oauth", "session",
                "cookie", "token", "刷新", "限流", "降级", "熔断", "负载", "网关",
                // 微服务
                "注册", "发现", "配置中心", "链路追踪", "服务网格", "sidecar", "熔断器", "重试",
                // 运维
                "部署", "容器", "日志", "监控", "告警", "ci", "cd", "流水线", "蓝绿", "金丝雀",
                "健康检查", "优雅停机", "滚动更新",
                // 测试
                "单元测试", "集成测试", "mock", "断言", "覆盖率", "测试用例", "回归", "压测", "基准",
                // Agent/LLM
                "向量", "embedding", "rerank", "重排", "召回", "精度", "切片", "分块", "chunk",
                "prompt", "提示词", "system", "few-shot", "zero-shot", "思维链", "cot",
                "rag", "知识库", "检索增强", "混合检索", "语义", "余弦相似度", "hnsw",
                "agent", "工具调用", "function call", "mcp", "上下文", "记忆", "会话",
                "大模型", "llm", "推理", "微调", "fine-tune", "量化", "蒸馏", "对齐",
                "幻觉", "hallucination", "评测", "benchmark", "ab测试",
                // 工具/框架
                "maven", "gradle", "git", "docker", "k8s", "kubernetes", "nginx", "jmeter",
                "mybatis", "jpa", "hibernate", "redis", "elasticsearch", "kafka", "rabbitmq",
                "minio", "oss", "s3", "prometheus", "grafana", "skywalking"
        };
        for (String pattern : specificPatterns) {
            if (lower.contains(pattern)) {
                specificityScore += 1;
            }
        }
        // 短查询（<=6字）适合关键词匹配
        if (query.length() <= 6) {
            specificityScore += 1;
        }

        if (specificityScore >= 4) {
            return new float[]{0.5f, 0.5f};
        } else if (specificityScore >= 2) {
            return new float[]{0.7f, 0.3f};
        } else {
            return new float[]{0.8f, 0.2f};
        }
    }

    /**
     * 混合检索（向量 + 关键词，返回分块级别的结果）
     */
    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        log.info("===== 混合检索开始: query={}, topK={} =====", query, topK);
        long totalStart = System.currentTimeMillis();

        try {
            // Step 1: 查询改写
            long t1 = System.currentTimeMillis();
            QueryRewriter.QueryParts parts = queryRewriter.extract(query);
            log.info("[Step1 查询改写] 耗时={}ms, 改写后='{}', 技术词={}, keywordQuery='{}'",
                    System.currentTimeMillis() - t1, parts.rewrittenForVector, parts.hasTechTerms(), parts.getKeywordQuery());

            // Step 2: 计算融合权重
            float[] weights = computeFusionWeights(query, parts.hasTechTerms());
            float vecWeight = weights[0];
            float kwWeight = weights[1];
            log.info("[Step2 融合权重] vector={}, keyword={}", vecWeight, kwWeight);

            // Step 3: 向量检索
            long t3 = System.currentTimeMillis();
            float[] queryVector = vectorizer.vectorize(parts.rewrittenForVector);
            List<Map<String, Object>> vectorResults = vectorStore.search(queryVector, topK * 5);
            log.info("[Step3 向量检索] 耆时={}ms, 结果数={}", System.currentTimeMillis() - t3, vectorResults.size());
            for (int i = 0; i < Math.min(3, vectorResults.size()); i++) {
                Map<String, Object> r = vectorResults.get(i);
                log.info("  向量Top{}: chunkId={}, similarity={}", i + 1, r.get("id"), r.get("similarity"));
            }

            // Step 4: 关键词检索
            long t4 = System.currentTimeMillis();
            String kwQuery = parts.hasTechTerms() ? parts.getKeywordQuery() : query;
            List<Map<String, Object>> keywordResults = keywordSearch(kwQuery, topK * 5);
            log.info("[Step4 关键词检索] 耗时={}ms, query='{}', 结果数={}", System.currentTimeMillis() - t4, kwQuery, keywordResults.size());
            for (int i = 0; i < Math.min(3, keywordResults.size()); i++) {
                Map<String, Object> r = keywordResults.get(i);
                log.info("  关键词Top{}: chunkId={}, score={}", i + 1, r.get("id"), r.get("similarity"));
            }

            // Step 5: 融合
            long t5 = System.currentTimeMillis();
            Map<String, Float> scoreMap = new LinkedHashMap<>();
            Map<String, Map<String, Object>> resultMap = new HashMap<>();

            for (Map<String, Object> r : vectorResults) {
                String chunkId = (String) r.get("id");
                float vecScore = ((Number) r.get("similarity")).floatValue();
                scoreMap.put(chunkId, vecScore * vecWeight);
                resultMap.put(chunkId, r);
            }

            for (Map<String, Object> r : keywordResults) {
                String chunkId = (String) r.get("id");
                float kwScore = ((Number) r.get("similarity")).floatValue();
                Float existing = scoreMap.get(chunkId);
                if (existing != null) {
                    scoreMap.put(chunkId, existing + kwScore * kwWeight);
                } else {
                    scoreMap.put(chunkId, kwScore * kwWeight);
                    resultMap.put(chunkId, r);
                }
            }

            List<Map.Entry<String, Float>> sorted = new ArrayList<>(scoreMap.entrySet());
            sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

            List<Map<String, Object>> results = new ArrayList<>();
            int skippedLowScore = 0;
            for (Map.Entry<String, Float> entry : sorted) {
                if (results.size() >= topK) break;
                if (entry.getValue() < MIN_SCORE_THRESHOLD) {
                    skippedLowScore++;
                    continue;
                }

                String chunkId = entry.getKey();
                String docId = extractDocId(chunkId);
                Map<String, Object> item = new HashMap<>(resultMap.get(chunkId));
                item.put("id", docId);
                item.put("chunkId", chunkId);
                item.put("content", getExpandedContent(chunkId));
                item.put("score", entry.getValue());
                item.put("fileName", getDocMetadata(docId, "fileName"));
                results.add(item);
            }

            log.info("[Step5 融合排序] 耆时={}ms, 候选数={}, 低分过滤={}, 阈值={}, 最终结果={}",
                    System.currentTimeMillis() - t5, sorted.size(), skippedLowScore, MIN_SCORE_THRESHOLD, results.size());
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> r = results.get(i);
                String content = (String) r.get("content");
                String preview = content != null ? content.substring(0, Math.min(80, content.length())).replace("\n", " ") : "null";
                log.info("  最终Top{}: chunkId={}, score={}, fileName={}, 内容预览='{}'",
                        i + 1, r.get("chunkId"), r.get("score"), r.get("fileName"), preview);
            }

            log.info("===== 混合检索完成: 总耗时={}ms =====", System.currentTimeMillis() - totalStart);
            return results;

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关键词检索（ES 倒排索引 + IK 分词，O(1) 复杂度）
     */
    private List<Map<String, Object>> keywordSearch(String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (query == null || query.isBlank()) return results;
        if (elasticsearchOperations == null) return results;

        try {
            // 用 bool query: content 字段 match + fileName 字段 boost
            NativeQuery searchQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .should(s -> s.match(m -> m
                                    .field("content")
                                    .query(query)))
                            .should(s -> s.match(m -> m
                                    .field("fileName")
                                    .query(query)
                                    .boost(2.0f)))
                    ))
                    .withMaxResults(topK)
                    .build();

            SearchHits<ChunkDocument> hits = elasticsearchOperations.search(searchQuery, ChunkDocument.class);

            for (SearchHit<ChunkDocument> hit : hits) {
                ChunkDocument doc = hit.getContent();
                Map<String, Object> item = new HashMap<>();
                item.put("id", doc.getChunkId());
                item.put("similarity", hit.getScore());
                results.add(item);
            }

            log.info("ES 关键词检索: query={}, 结果数={}", query, results.size());
        } catch (Exception e) {
            log.warn("ES 检索失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 将 chunk 写入 ES 倒排索引
     */
    private void indexToEs(String chunkId, String content, String docId, String fileName) {
        if (elasticsearchOperations == null) return;
        try {
            ChunkDocument doc = new ChunkDocument(chunkId, content, docId, fileName);
            elasticsearchOperations.save(doc);
            log.debug("[indexToEs] 写入ES: chunkId={}, content长度={}, fileName={}", chunkId, content != null ? content.length() : 0, fileName);
        } catch (Exception e) {
            log.warn("ES 索引写入失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    /**
     * 从 ES 删除 chunk
     */
    private void deleteFromEs(String chunkId) {
        if (elasticsearchOperations == null) return;
        try {
            elasticsearchOperations.delete(chunkId, ChunkDocument.class);
        } catch (Exception e) {
            log.warn("ES 删除失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    private String extractDocId(String chunkId) {
        int idx = chunkId.indexOf("::");
        return idx > 0 ? chunkId.substring(0, idx) : chunkId;
    }

    private String getChunkContent(String chunkId) {
        if (elasticsearchOperations == null) return null;
        try {
            ChunkDocument doc = elasticsearchOperations.get(chunkId, ChunkDocument.class);
            if (doc == null) {
                log.warn("[getChunkContent] ES get返回null: chunkId={}", chunkId);
                return null;
            }
            String content = doc.getContent();
            if (content == null) {
                log.warn("[getChunkContent] ES doc.content为null: chunkId={}, docId={}, fileName={}", chunkId, doc.getDocId(), doc.getFileName());
            }
            return content;
        } catch (Exception e) {
            log.warn("[getChunkContent] ES get异常: chunkId={}, error={}", chunkId, e.getMessage());
            return null;
        }
    }

    /**
     * 扩展上下文：返回当前 chunk + 前后邻居 chunk 的拼接内容
     * 小块检索保证精度，邻块扩展保证上下文完整性
     */
    private String getExpandedContent(String chunkId) {
        String baseContent = getChunkContent(chunkId);
        if (baseContent == null) return null;

        String docId = extractDocId(chunkId);
        int idx = chunkId.indexOf("::");
        if (idx < 0) return baseContent;
        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(chunkId.substring(idx + 2));
        } catch (NumberFormatException e) {
            return baseContent;
        }

        StringBuilder expanded = new StringBuilder();

        // 前一个 chunk
        String prevContent = getChunkContent(docId + "::" + (chunkIndex - 1));
        if (prevContent != null) {
            expanded.append(prevContent).append("\n\n");
        }

        // 当前 chunk
        expanded.append(baseContent);

        // 后一个 chunk
        String nextContent = getChunkContent(docId + "::" + (chunkIndex + 1));
        if (nextContent != null) {
            expanded.append("\n\n").append(nextContent);
        }

        // 上限 6000 字符，确保大 chunk 的关键内容不被截断
        String result = expanded.toString();
        return result.length() > 6000 ? result.substring(0, 6000) : result;
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
        indexDocument(documentId, content, null, fileName, fileType);
    }

    public void indexDocument(String documentId, String content, byte[] rawData, String fileName, String fileType) {
        log.info("索引文档: documentId={}, fileName={}, hasRawData={}", documentId, fileName, rawData != null);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", fileName);
        metadata.put("fileType", fileType);
        metadata.put("uploadTime", System.currentTimeMillis());
        removeDocument(documentId);
        addDocument(documentId, content, rawData, metadata);
    }

    /**
     * 清空所有知识库数据（Redis 元数据 + Qdrant 向量 + ES 索引）
     */
    public void clearAll() {
        log.info("开始清空知识库所有数据");
        // 1. 删除 Redis 中所有 doc:* 元数据
        try {
            Set<String> keys = redisTemplate.keys(DOCUMENT_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("已删除 Redis 文档元数据: {} 条", keys.size());
            }
        } catch (Exception e) {
            log.warn("清理 Redis 文档数据失败: {}", e.getMessage());
        }
        // 2. 删除 Qdrant 集合
        try {
            vectorStore.deleteCollection();
        } catch (Exception e) {
            log.warn("清理 Qdrant 数据失败: {}", e.getMessage());
        }
        // 3. 清空 ES rag_chunks 索引
        try {
            if (elasticsearchOperations != null) {
                var indexOps = elasticsearchOperations.indexOps(ChunkDocument.class);
                if (indexOps.exists()) {
                    indexOps.delete();
                    indexOps.createWithMapping();
                    log.info("已重建 ES 索引 rag_chunks");
                }
            }
        } catch (Exception e) {
            log.warn("清理 ES 数据失败: {}", e.getMessage());
        }
        log.info("知识库数据清空完成");
    }
}
