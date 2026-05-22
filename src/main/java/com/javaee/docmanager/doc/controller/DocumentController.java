package com.javaee.docmanager.doc.controller;

import com.javaee.docmanager.ai.rag.KnowledgeBase;
import com.javaee.docmanager.ai.rag.TextExtractor;
import com.javaee.docmanager.common.model.Result;
import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.doc.entity.DocumentFileVersion;
import com.javaee.docmanager.doc.service.DocumentBranchService;
import com.javaee.docmanager.doc.service.DocumentFileService;
import com.javaee.docmanager.file.service.FileService;
import com.javaee.docmanager.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档管理", description = "文档文件上传、版本管理、恢复等接口")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentFileService documentFileService;
    private final DocumentBranchService documentBranchService;
    private final FileService fileService;
    private final KnowledgeBase knowledgeBase;
    private final TextExtractor textExtractor;

    @GetMapping("/files")
    @Operation(summary = "获取文档文件列表", description = "获取所有活跃的文档文件")
    public Result<List<DocumentFile>> getDocumentFiles() {
        List<DocumentFile> docs = documentFileService.getAllDocuments();
        return Result.success(docs);
    }

    @GetMapping("/files/{id}")
    @Operation(summary = "获取文档详情", description = "根据ID获取文档文件详情")
    public Result<DocumentFile> getDocumentFile(@Parameter(description = "文档ID") @PathVariable String id) {
        DocumentFile doc = documentFileService.getById(id);
        return Result.success(doc);
    }

    @GetMapping("/files/{id}/versions")
    @Operation(summary = "获取文档版本历史", description = "获取文档的所有版本记录")
    public Result<List<DocumentFileVersion>> getVersions(@Parameter(description = "文档ID") @PathVariable String id) {
        List<DocumentFileVersion> versions = documentFileService.getVersions(id);
        return Result.success(versions);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传新文档", description = "上传文档文件，自动创建文档记录")
    public Result<DocumentFile> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("version") String version,
            @RequestParam(value = "changeLog", required = false, defaultValue = "") String changeLog) {
        String username = UserContext.getCurrentUsername();
        // 上传文件到存储（校验文档类型）
        String fileId = fileService.uploadDocument(file);
        // 创建文档记录
        DocumentFile doc = documentFileService.createDocument(fileId, file.getOriginalFilename(), version, file.getContentType(), username);
        // 索引到知识库（用文档ID作key，只保留最新版本）
        indexToRag(doc.getId(), fileId, file.getOriginalFilename(), file.getContentType());
        return Result.success(doc);
    }

    @PostMapping(value = "/files/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传新版本", description = "为文档上传新版本文件")
    public Result<DocumentFile> uploadNewVersion(
            @Parameter(description = "文档ID") @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("version") String version,
            @RequestParam(value = "changeLog", required = false, defaultValue = "") String changeLog) {
        String username = UserContext.getCurrentUsername();
        // 上传文件到存储（校验文档类型）
        String fileId = fileService.uploadDocument(file);
        // 添加新版本
        DocumentFile doc = documentFileService.uploadNewVersion(id, fileId, version, changeLog, username);
        // 重新索引（覆盖旧版本）
        indexToRag(id, fileId, file.getOriginalFilename(), file.getContentType());
        return Result.success(doc);
    }

    @PostMapping("/files/{id}/restore/{versionId}")
    @Operation(summary = "恢复文档版本", description = "将文档恢复到指定版本")
    public Result<DocumentFile> restoreVersion(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "版本ID") @PathVariable String versionId) {
        String username = UserContext.getCurrentUsername();
        DocumentFile doc = documentFileService.restoreVersion(id, versionId, username);
        // 重新索引恢复的版本
        indexToRag(id, doc.getCurrentFileId(), doc.getTitle(), doc.getFileType());
        return Result.success(doc);
    }

    @DeleteMapping("/files/{id}")
    @Operation(summary = "删除文档", description = "软删除文档文件")
    public Result<Void> deleteDocument(@Parameter(description = "文档ID") @PathVariable String id) {
        documentFileService.deleteDocument(id);
        // 从知识库移除
        try {
            knowledgeBase.removeDocument(id);
            log.info("文档已从知识库移除: documentId={}", id);
        } catch (Exception e) {
            log.warn("从知识库移除文档失败（不影响删除）: {}", e.getMessage());
        }
        return Result.success();
    }

    /**
     * 将文档索引到知识库
     */
    private void indexToRag(String documentId, String fileId, String fileName, String fileType) {
        try {
            byte[] data = fileService.downloadByName(fileId, fileName);
            String text = textExtractor.extract(fileType, fileName, data);
            if (text != null && !text.isBlank()) {
                knowledgeBase.indexDocument(documentId, text, fileName, fileType);
                log.info("文档索引到知识库成功: documentId={}, fileName={}", documentId, fileName);
            } else {
                log.warn("文档文本为空，跳过索引: documentId={}", documentId);
            }
        } catch (Exception e) {
            log.error("文档索引失败（不影响操作）", e);
        }
    }

    @GetMapping("/{documentId}/branches")
    @Operation(summary = "获取文档分支列表")
    public Result<List<DocumentFile>> getBranches(@PathVariable String documentId) {
        return Result.success(documentBranchService.getBranches(documentId));
    }

    @PostMapping("/{documentId}/branches")
    @Operation(summary = "创建分支")
    public Result<DocumentFile> createBranch(
            @PathVariable String documentId,
            @RequestParam String sourceBranchName,
            @RequestParam String newBranchName) {
        String username = UserContext.getCurrentUsername();
        return Result.success(documentBranchService.createBranch(documentId, sourceBranchName, newBranchName, username));
    }

    @PostMapping("/{documentId}/merge")
    @Operation(summary = "合并分支")
    public Result<DocumentFile> mergeBranch(
            @PathVariable String documentId,
            @RequestParam String sourceBranch,
            @RequestParam String targetBranch) {
        String username = UserContext.getCurrentUsername();
        return Result.success(documentBranchService.mergeBranch(documentId, sourceBranch, targetBranch, username));
    }

    @DeleteMapping("/{documentId}/branches/{branchName}")
    @Operation(summary = "删除分支")
    public Result<Void> deleteBranch(
            @PathVariable String documentId,
            @PathVariable String branchName) {
        documentBranchService.deleteBranch(documentId, branchName);
        return Result.success();
    }
}
