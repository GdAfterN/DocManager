package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.rag.KnowledgeBase;
import com.javaee.docmanager.ai.rag.Reranker;
import com.javaee.docmanager.ai.rag.TextExtractor;
import com.javaee.docmanager.ai.rag.VectorStore;
import com.javaee.docmanager.ai.rag.DashScopeChatService;
import com.javaee.docmanager.ai.agent.ChatService;
import com.javaee.docmanager.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
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

    @Autowired
    private DashScopeChatService dashScopeChatService;

    @Autowired
    private TextExtractor textExtractor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 文档索引
     */
    @PostMapping("/index")
    @Operation(summary = "文档索引", description = "将文档添加到知识库")
    public Result<Void> indexDocument(
            @Parameter(description = "文档ID") @RequestParam String documentId,
            @Parameter(description = "文档内容") @RequestBody String content,
            @Parameter(description = "文件名") @RequestParam(value = "fileName", required = false) String fileName) {
        Map<String, Object> metadata = new HashMap<>();
        if (fileName != null && !fileName.isBlank()) {
            metadata.put("fileName", fileName);
            metadata.put("fileType", deriveFileType(fileName));
        }
        knowledgeBase.addDocument(documentId, content, metadata);
        return Result.success();
    }

    /**
     * 批量索引：直接传文件内容，跳过 multipart 文件名编码问题
     */
    @PostMapping("/index/batch")
    @Operation(summary = "批量索引文档", description = "直接传内容和文件名，用于脚本批量导入")
    public Result<Map<String, Object>> indexBatch(@RequestBody List<Map<String, String>> docs) {
        int success = 0, fail = 0;
        for (Map<String, String> doc : docs) {
            try {
                String documentId = doc.getOrDefault("documentId", UUID.randomUUID().toString());
                String content = doc.get("content");
                String fileName = doc.getOrDefault("fileName", "unknown");
                if (content == null || content.isBlank()) { fail++; continue; }
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("fileName", fileName);
                metadata.put("fileType", deriveFileType(fileName));
                knowledgeBase.addDocument(documentId, content, metadata);
                success++;
            } catch (Exception e) {
                log.warn("批量索引失败: {}", e.getMessage());
                fail++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("fail", fail);
        return Result.success(result);
    }

    /**
     * 上传文档到知识库（仅支持 .md, .doc, .docx, .pdf）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档到知识库", description = "上传文件并自动向量化，支持常见文档格式（PDF、Word、PPT、Excel、CSV、HTML、Markdown、TXT等）")
    public Result<Map<String, Object>> uploadToKnowledgeBase(
            @RequestParam("file") MultipartFile file) {
        String fileName = fixEncoding(file.getOriginalFilename());
        String contentType = file.getContentType();

        // 校验文件类型：排除图片、视频、音频、压缩包等二进制文件
        if (!isRagAllowedType(contentType, fileName)) {
            return Result.fail("不支持该文件格式，请上传文档类文件（PDF、Word、PPT、Excel、CSV、HTML、Markdown、TXT等）");
        }

        try {
            String documentId = UUID.randomUUID().toString();
            String fileType = deriveFileType(fileName);
            // 直接从 MultipartFile 提取文本，不存储到 MinIO
            byte[] data = file.getBytes();
            String text = textExtractor.extract(contentType, fileName, data);
            if (text == null || text.isBlank()) {
                return Result.fail("文档内容为空，无法索引");
            }
            // 索引到 ES + Qdrant（传入原始数据用于PDF/Word按页/标题切分）
            knowledgeBase.indexDocument(documentId, text, data, fileName, fileType);

            Map<String, Object> result = new HashMap<>();
            result.put("documentId", documentId);
            result.put("fileName", fileName);
            result.put("fileType", fileType);
            return Result.success(result);
        } catch (Exception e) {
            return Result.fail("上传索引失败: " + e.getMessage());
        }
    }

    private boolean isRagAllowedType(String contentType, String fileName) {
        // 优先用文件扩展名判断（更可靠，curl 对未知类型发 application/octet-stream）
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            // 明确允许的文档类型
            if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".doc")
                    || lower.endsWith(".docx") || lower.endsWith(".pdf") || lower.endsWith(".ppt")
                    || lower.endsWith(".pptx") || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                    || lower.endsWith(".csv") || lower.endsWith(".html") || lower.endsWith(".htm")
                    || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".rst")
                    || lower.endsWith(".adoc")) {
                return true;
            }
            // 排除常见二进制/媒体文件
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".svg")
                    || lower.endsWith(".mp3") || lower.endsWith(".mp4") || lower.endsWith(".avi")
                    || lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv")
                    || lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
                    || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".exe")
                    || lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".bin")) {
                return false;
            }
        }
        // 扩展名不在白名单也不在黑名单时，用 contentType 做宽松判断
        if (contentType != null) {
            String type = contentType.toLowerCase();
            if (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 修复 Windows curl 上传时文件名编码问题（GBK → UTF-8）
     */
    private String fixEncoding(String fileName) {
        if (fileName == null) return null;
        try {
            byte[] bytes = fileName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            String converted = new String(bytes, "GBK");
            if (!converted.contains("�") && converted.length() > 0) {
                return converted;
            }
        } catch (Exception ignored) {}
        return fileName;
    }

    /**
     * 根据文件扩展名返回人类可读的文件类型
     */
    private String deriveFileType(String fileName) {
        if (fileName == null) return "未知";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "Markdown";
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".doc")) return "Word";
        if (lower.endsWith(".docx")) return "Word";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".ppt")) return "PPT";
        if (lower.endsWith(".pptx")) return "PPT";
        if (lower.endsWith(".xls")) return "Excel";
        if (lower.endsWith(".xlsx")) return "Excel";
        if (lower.endsWith(".csv")) return "CSV";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
        if (lower.endsWith(".json")) return "JSON";
        if (lower.endsWith(".xml")) return "XML";
        return "未知";
    }
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
            @Parameter(description = "重排序策略: BM25_FUSION, CROSS_ENCODER, HYBRID, DASHSCOPE_RERANK")
            @RequestParam(defaultValue = "DASHSCOPE_RERANK") String strategy) {
        
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

        log.info("===== RAG问答开始: question='{}', strategy={} =====", question, strategy);
        long queryStart = System.currentTimeMillis();

        // 1. 检索（查询改写已在 KnowledgeBase 内部完成）
        long t1 = System.currentTimeMillis();
        List<Map<String, Object>> results;
        if ("rerank".equalsIgnoreCase(strategy)) {
            results = knowledgeBase.hybridSearchWithRerank(question, 8, Reranker.RerankStrategy.DASHSCOPE_RERANK);
        } else if ("vector".equalsIgnoreCase(strategy)) {
            results = knowledgeBase.search(question, 8);
        } else {
            results = knowledgeBase.hybridSearch(question, 8);
        }
        log.info("[RAG Step1 检索] 耗时={}ms, 结果数={}", System.currentTimeMillis() - t1, results.size());

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
        log.info("[RAG Step2 上下文拼接] 片段数={}, 上下文长度={}字符", results.size(), contextStr.length());
        if (!contextStr.isEmpty()) {
            log.info("[RAG Step2 上下文预览] 前200字符: {}", contextStr.substring(0, Math.min(200, contextStr.length())).replace("\n", "\\n"));
        }

        // 3. 调用大模型生成答案
        String answerText;
        if (contextStr.isEmpty()) {
            answerText = "知识库中没有找到与您问题相关的文档内容。请先上传文档到知识库后再提问。";
            log.info("[RAG Step3 LLM] 上下文为空，跳过LLM调用");
        } else {
            String prompt = "你是一个严格基于参考资料回答问题的助手。请遵守以下规则：\n"
                    + "1. 只能根据下方【参考资料】中的内容回答，禁止使用你自己的知识\n"
                    + "2. 如果参考资料中没有与问题相关的内容，必须回答\"参考资料中未找到相关信息\"\n"
                    + "3. 回答时引用参考资料中的原文，不要改写或概括\n\n"
                    + "【参考资料】\n" + contextStr + "\n\n"
                    + "【用户问题】" + question + "\n\n"
                    + "【回答】";
            log.info("[RAG Step3 LLM] prompt长度={}字符", prompt.length());
            long t3 = System.currentTimeMillis();
            try {
                answerText = chatService.callChatApi(prompt, "rag.tokens");
                log.info("[RAG Step3 LLM] 耆时={}ms, 回答长度={}字符", System.currentTimeMillis() - t3, answerText.length());
                log.info("[RAG Step3 LLM] 回答预览: {}", answerText.substring(0, Math.min(200, answerText.length())).replace("\n", "\\n"));
            } catch (Exception e) {
                answerText = "调用AI模型失败: " + e.getMessage();
                log.error("[RAG Step3 LLM] 调用失败", e);
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

        log.info("===== RAG问答完成: 总耗时={}ms, 来源文档={} =====", System.currentTimeMillis() - queryStart, seenDocs.keySet());
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
     * 获取知识库文档列表（带元数据）
     */
    @GetMapping("/documents/list")
    @Operation(summary = "获取知识库文档列表", description = "获取所有已索引文档的详细信息")
    public Result<List<Map<String, Object>>> listDocumentsWithMetadata() {
        List<String> ids = knowledgeBase.getAllDocumentIds();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> meta = knowledgeBase.getDocumentMetadata(id);
            meta.put("documentId", id);
            list.add(meta);
        }
        return Result.success(list);
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

    /**
     * 清空知识库所有数据
     */
    @DeleteMapping("/cleanup")
    @Operation(summary = "清空知识库", description = "清空所有已索引的文档数据（Redis元数据 + Qdrant向量 + ES索引）")
    public Result<Void> cleanup() {
        knowledgeBase.clearAll();
        return Result.success();
    }

    /**
     * 知识库问答（流式输出）
     * 检索同步执行，答案通过 SSE 逐 token 返回
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "知识库问答(流式)", description = "基于知识库进行问答，答案流式输出")
    public SseEmitter queryStream(
            @Parameter(description = "问题") @RequestBody String question,
            @Parameter(description = "策略") @RequestParam(defaultValue = "hybrid") String strategy) {

        SseEmitter emitter = new SseEmitter(120000L); // 2分钟超时

        // 异步执行，不阻塞 Servlet 线程
        CompletableFuture.runAsync(() -> {
            try {
                log.info("===== RAG流式问答开始: question='{}', strategy={} =====", question, strategy);
                long queryStart = System.currentTimeMillis();

                // Step 1: 检索（同步，~2-3s）
                long t1 = System.currentTimeMillis();
                List<Map<String, Object>> results;
                if ("rerank".equalsIgnoreCase(strategy)) {
                    results = knowledgeBase.hybridSearchWithRerank(question, 8, Reranker.RerankStrategy.DASHSCOPE_RERANK);
                } else if ("vector".equalsIgnoreCase(strategy)) {
                    results = knowledgeBase.search(question, 8);
                } else {
                    results = knowledgeBase.hybridSearch(question, 8);
                }
                log.info("[Stream Step1 检索] 耗时={}ms, 结果数={}", System.currentTimeMillis() - t1, results.size());

                // Step 2: 拼接上下文
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

                // 发送 sources 事件
                Map<String, String> seenDocs = new LinkedHashMap<>();
                for (Map<String, Object> r : results) {
                    String docId = (String) r.get("id");
                    if (docId != null && !seenDocs.containsKey(docId)) {
                        seenDocs.put(docId, (String) r.get("fileName"));
                    }
                }
                List<Map<String, Object>> sources = seenDocs.entrySet().stream().map(e -> {
                    Map<String, Object> source = new HashMap<>();
                    source.put("id", e.getKey());
                    source.put("fileName", e.getValue());
                    return source;
                }).toList();

                Map<String, Object> sourcesEvent = new HashMap<>();
                sourcesEvent.put("type", "sources");
                sourcesEvent.put("sources", sources);
                sourcesEvent.put("strategy", strategy);
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(sourcesEvent)));

                // Step 3: 流式生成答案
                if (contextStr.isEmpty()) {
                    Map<String, Object> doneEvent = new HashMap<>();
                    doneEvent.put("type", "token");
                    doneEvent.put("content", "知识库中没有找到与您问题相关的文档内容。请先上传文档到知识库后再提问。");
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(doneEvent)));
                } else {
                    String prompt = "你是一个严格基于参考资料回答问题的助手。请遵守以下规则：\n"
                            + "1. 只能根据下方【参考资料】中的内容回答，禁止使用你自己的知识\n"
                            + "2. 如果参考资料中没有与问题相关的内容，必须回答\"参考资料中未找到相关信息\"\n"
                            + "3. 回答时引用参考资料中的原文，不要改写或概括\n\n"
                            + "【参考资料】\n" + contextStr + "\n\n"
                            + "【用户问题】" + question + "\n\n"
                            + "【回答】";

                    long t3 = System.currentTimeMillis();
                    dashScopeChatService.chatStream(prompt, token -> {
                        try {
                            Map<String, Object> tokenEvent = new HashMap<>();
                            tokenEvent.put("type", "token");
                            tokenEvent.put("content", token);
                            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(tokenEvent)));
                        } catch (Exception e) {
                            log.warn("SSE发送token失败: {}", e.getMessage());
                        }
                    }, () -> {
                        log.info("[Stream Step3 LLM] 流式生成耗时={}ms", System.currentTimeMillis() - t3);
                    });
                }

                // 发送完成事件
                Map<String, Object> doneEvent = new HashMap<>();
                doneEvent.put("type", "done");
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(doneEvent)));
                emitter.complete();

                log.info("===== RAG流式问答完成: 总耗时={}ms =====", System.currentTimeMillis() - queryStart);

            } catch (Exception e) {
                log.error("RAG流式问答失败", e);
                try {
                    Map<String, Object> errorEvent = new HashMap<>();
                    errorEvent.put("type", "error");
                    errorEvent.put("message", e.getMessage());
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(errorEvent)));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
