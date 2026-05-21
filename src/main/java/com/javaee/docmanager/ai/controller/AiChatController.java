package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.entity.GeneratedFile;
import com.javaee.docmanager.ai.service.AiChatService;
import com.javaee.docmanager.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai/chat")
@Tag(name = "AI对话", description = "AI对话与文件生成")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping
    @Operation(summary = "AI对话", description = "与大模型对话，返回回复")
    public Result<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        String reply = aiChatService.chat(message);
        return Result.success(Map.of("reply", reply));
    }

    @PostMapping("/generate-ppt")
    @Operation(summary = "生成PPT", description = "使用PPT Skill生成横向翻页HTML PPT")
    public Result<Map<String, Object>> generatePpt(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        GeneratedFile gf = aiChatService.generatePpt(message);
        Map<String, Object> data = Map.of(
                "fileId", gf.getId(),
                "fileName", gf.getTitle(),
                "format", gf.getFileFormat()
        );
        return Result.success(data);
    }

    @PostMapping("/ppt")
    @Operation(summary = "多轮对话生成PPT", description = "AI先与用户交流确认需求，最多5轮后自动生成")
    public Result<Map<String, Object>> chatForPpt(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String conversationId = body.get("conversationId");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = java.util.UUID.randomUUID().toString();
        }

        GeneratedFile gf = aiChatService.chatForPpt(conversationId, message);

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (gf != null) {
            // 已生成文件
            data.put("type", "file");
            data.put("fileId", gf.getId());
            data.put("fileName", gf.getTitle());
            data.put("format", gf.getFileFormat());
            data.put("conversationId", conversationId);
        } else {
            // AI 在提问
            data.put("type", "question");
            data.put("question", aiChatService.getLastPptQuestion(conversationId));
            data.put("conversationId", conversationId);
        }
        return Result.success(data);
    }

    @PostMapping("/generate")
    @Operation(summary = "生成文件", description = "让大模型生成内容并保存为Word/PDF/PPT文件")
    public Result<Map<String, Object>> generateFile(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String format = body.get("format");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        if (format == null || format.isBlank()) {
            return Result.fail("文件格式不能为空");
        }
        GeneratedFile gf = aiChatService.generateFile(message, format);
        Map<String, Object> data = Map.of(
                "fileId", gf.getId(),
                "fileName", gf.getTitle(),
                "format", gf.getFileFormat()
        );
        return Result.success(data);
    }

    @GetMapping("/files")
    @Operation(summary = "最近生成文件", description = "获取最近10条生成的文件")
    public Result<List<GeneratedFile>> getRecentFiles() {
        return Result.success(aiChatService.getRecentFiles(10));
    }

    @GetMapping("/files/{id}/download")
    @Operation(summary = "下载生成文件")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String id) {
        GeneratedFile gf = aiChatService.getFile(id);
        if (gf == null) return ResponseEntity.notFound().build();
        byte[] data = aiChatService.downloadFile(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(getMediaType(gf.getFileFormat()));
        String encodedName = URLEncoder.encode(gf.getTitle(), StandardCharsets.UTF_8);
        headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + encodedName);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    @GetMapping("/files/{id}/preview")
    @Operation(summary = "预览生成文件", description = "支持PDF和HTML PPT在线预览")
    public ResponseEntity<byte[]> previewFile(@PathVariable String id) {
        GeneratedFile gf = aiChatService.getFile(id);
        if (gf == null) return ResponseEntity.notFound().build();
        byte[] data = aiChatService.previewFile(id);
        HttpHeaders headers = new HttpHeaders();
        if ("ppt".equals(gf.getFileFormat())) {
            headers.setContentType(MediaType.TEXT_HTML);
        } else if ("pdf".equals(gf.getFileFormat())) {
            headers.setContentType(MediaType.APPLICATION_PDF);
        } else {
            return ResponseEntity.badRequest().build();
        }
        String encodedName = URLEncoder.encode(gf.getTitle(), StandardCharsets.UTF_8);
        headers.set("Content-Disposition", "inline; filename*=UTF-8''" + encodedName);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    @DeleteMapping("/files/{id}")
    @Operation(summary = "删除生成文件")
    public Result<Void> deleteFile(@PathVariable String id) {
        aiChatService.deleteFile(id);
        return Result.success();
    }

    private MediaType getMediaType(String format) {
        return switch (format.toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "word" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "ppt" -> MediaType.TEXT_HTML;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
