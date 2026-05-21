package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.dto.*;
import com.javaee.docmanager.ai.service.*;
import com.javaee.docmanager.ai.vo.*;
import com.javaee.docmanager.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * AI控制器
 * 提供AI处理和文件管理相关的REST API接口
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI处理", description = "文档摘要、关键词提取、文档分析等AI处理接口")
public class AIController {

    @Autowired
    private AIService aiService;

    @Autowired
    private FileUploadService fileUploadService;

    @Autowired
    private FileDownloadService fileDownloadService;

    @Autowired
    private FileVersionService fileVersionService;

    /**
     * 文档摘要
     * @param dto 摘要请求参数
     * @return 摘要结果
     */
    @PostMapping("/summarize")
    @Operation(summary = "文档摘要", description = "对文档进行智能摘要")
    public Result<TextSummarizeVO> summarize(@RequestBody TextSummarizeDTO dto) {
        TextSummarizeVO vo = aiService.summarize(dto);
        return Result.success(vo);
    }

    /**
     * 关键词提取
     * @param dto 关键词提取请求参数
     * @return 关键词结果
     */
    @PostMapping("/keywords")
    @Operation(summary = "关键词提取", description = "从文档中提取关键词")
    public Result<KeywordExtractVO> extractKeywords(@RequestBody KeywordExtractDTO dto) {
        KeywordExtractVO vo = aiService.extractKeywords(dto);
        return Result.success(vo);
    }

    /**
     * 文档分析
     * @param dto 文档分析请求参数
     * @return 分析结果
     */
    @PostMapping("/analyze")
    @Operation(summary = "文档分析", description = "对文档进行深度分析")
    public Result<TextAnalyzeVO> analyze(@RequestBody TextAnalyzeDTO dto) {
        TextAnalyzeVO vo = aiService.analyze(dto);
        return Result.success(vo);
    }

    /**
     * 文件上传接口
     * @param file 上传的文件
     * @param dto 上传参数
     * @return 文件上传结果
     */
    @PostMapping("/upload")
    @Operation(summary = "文件上传", description = "将文件上传至MinIO服务器")
    public Result<FileUploadVO> uploadFile(
            @Parameter(description = "上传的文件") @RequestParam("file") MultipartFile file,
            FileUploadDTO dto) {
        FileUploadVO vo = fileUploadService.uploadFile(file, dto);
        return Result.success(vo);
    }

    /**
     * 文件下载接口（直接下载）
     * @param dto 下载参数
     * @return 文件流
     */
    @GetMapping("/download")
    @Operation(summary = "文件下载", description = "从MinIO服务器下载文件")
    public ResponseEntity<org.springframework.core.io.Resource> downloadFile(FileDownloadDTO dto) {
        try {
            InputStream inputStream = fileDownloadService.downloadFile(dto);
            
            String filename = dto.getObjectName();
            if (filename.contains("/")) {
                filename = filename.substring(filename.lastIndexOf("/") + 1);
            }
            
            org.springframework.core.io.InputStreamResource resource = 
                new org.springframework.core.io.InputStreamResource(inputStream);
            
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + URLEncoder.encode(filename, StandardCharsets.UTF_8) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取文件访问URL接口
     * @param dto 下载参数
     * @return 文件访问URL
     */
    @GetMapping("/download/url")
    @Operation(summary = "获取文件URL", description = "获取MinIO中文件的预签名访问URL")
    public Result<FileDownloadVO> getFileUrl(FileDownloadDTO dto) {
        FileDownloadVO vo = fileDownloadService.getFileUrl(dto);
        return Result.success(vo);
    }

    /**
     * 获取文件版本列表接口
     * @param dto 查询参数
     * @return 文件版本信息
     */
    @GetMapping("/files/versions")
    @Operation(summary = "文件版本列表", description = "获取文件的所有版本")
    public Result<FileVersionVO> getVersions(FileVersionDTO dto) {
        FileVersionVO vo = fileVersionService.getVersions(dto);
        return Result.success(vo);
    }

    /**
     * 切换文件版本接口
     * @param dto 切换参数
     * @return 切换后的版本信息
     */
    @PostMapping("/files/versions/switch")
    @Operation(summary = "切换文件版本", description = "切换到指定版本")
    public Result<FileVersionVO.VersionInfo> switchVersion(@RequestBody FileVersionSwitchDTO dto) {
        FileVersionVO.VersionInfo vo = fileVersionService.switchVersion(dto);
        return Result.success(vo);
    }
}
