package com.javaee.docai.file.controller;

import com.javaee.docai.common.model.PageResult;
import com.javaee.docai.common.model.Result;
import com.javaee.docai.file.entity.FileMetadata;
import com.javaee.docai.file.service.FileMetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
@Tag(name = "文件元数据", description = "文件元数据查询接口")
public class FileMetadataController {

    @Autowired
    private FileMetadataService fileMetadataService;

    @GetMapping("/metadata/{fileId}")
    @Operation(summary = "获取文件元数据")
    public Result<FileMetadata> getMetadata(@PathVariable String fileId) {
        FileMetadata metadata = fileMetadataService.getMetadata(fileId);
        return Result.success(metadata);
    }

    @GetMapping("/list")
    @Operation(summary = "获取文件列表", description = "支持分页和排序")
    public Result<PageResult<FileMetadata>> getFileList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createTime") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String fileType) {
        PageResult<FileMetadata> result;
        if (fileType != null && !fileType.isEmpty()) {
            result = fileMetadataService.getFileListByType(fileType, page, size, sortBy, direction);
        } else {
            result = fileMetadataService.getFileList(page, size, sortBy, direction);
        }
        return Result.success(result);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索文件")
    public Result<PageResult<FileMetadata>> searchFiles(
            @RequestParam("keyword") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResult<FileMetadata> result = fileMetadataService.searchFiles(keyword, page, size);
        return Result.success(result);
    }

    @GetMapping("/directory")
    @Operation(summary = "获取目录结构")
    public Result<Object> getDirectoryStructure(@RequestParam(defaultValue = "/") String path) {
        return Result.success(fileMetadataService.getDirectoryStructure(path));
    }

    @GetMapping("/info/{fileId}/name")
    @Operation(summary = "获取文件名")
    public Result<String> getFileName(@PathVariable String fileId) {
        FileMetadata metadata = fileMetadataService.getMetadata(fileId);
        return Result.success(metadata != null ? metadata.getFileName() : null);
    }

    @GetMapping("/info/{fileId}/type")
    @Operation(summary = "获取文件类型")
    public Result<String> getFileType(@PathVariable String fileId) {
        FileMetadata metadata = fileMetadataService.getMetadata(fileId);
        return Result.success(metadata != null ? metadata.getFileType() : null);
    }
}
