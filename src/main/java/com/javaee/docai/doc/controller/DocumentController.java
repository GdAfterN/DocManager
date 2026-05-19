package com.javaee.docai.doc.controller;

import com.javaee.docai.common.model.Result;
import com.javaee.docai.doc.entity.DocumentFile;
import com.javaee.docai.doc.entity.DocumentFileVersion;
import com.javaee.docai.doc.service.DocumentFileService;
import com.javaee.docai.file.service.FileService;
import com.javaee.docai.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档管理", description = "文档文件上传、版本管理、恢复等接口")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentFileService documentFileService;
    private final FileService fileService;

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
        return Result.success(doc);
    }

    @PostMapping("/files/{id}/restore/{versionId}")
    @Operation(summary = "恢复文档版本", description = "将文档恢复到指定版本")
    public Result<DocumentFile> restoreVersion(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "版本ID") @PathVariable String versionId) {
        String username = UserContext.getCurrentUsername();
        DocumentFile doc = documentFileService.restoreVersion(id, versionId, username);
        return Result.success(doc);
    }

    @DeleteMapping("/files/{id}")
    @Operation(summary = "删除文档", description = "软删除文档文件")
    public Result<Void> deleteDocument(@Parameter(description = "文档ID") @PathVariable String id) {
        documentFileService.deleteDocument(id);
        return Result.success();
    }
}
