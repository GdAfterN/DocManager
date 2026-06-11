package com.javaee.docmanager.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Slf4j
@Component
public class TextExtractor {

    private final Tika tika = new Tika();

    public String extract(String fileType, byte[] data) {
        return extract(fileType, null, data);
    }

    public String extract(String fileType, String fileName, byte[] data) {
        if (data == null || data.length == 0) return "";

        String text;
        try {
            // Tika 自动检测文件类型并提取文本
            text = tika.parseToString(new ByteArrayInputStream(data));
        } catch (Exception e) {
            log.error("Tika文本提取失败: fileType={}, fileName={}", fileType, fileName, e);
            return "";
        }

        if (text == null || text.isBlank()) {
            log.warn("文档内容为空: fileType={}, fileName={}", fileType, fileName);
            return "";
        }

        // PDF 段落合并后处理（Tika 提取的 PDF 文本也有换行问题）
        String name = fileName != null ? fileName.toLowerCase() : "";
        String type = fileType != null ? fileType.toLowerCase() : "";
        if (type.contains("pdf") || name.endsWith(".pdf")) {
            text = mergeParagraphs(text);
        }

        log.debug("文本提取成功: fileType={}, fileName={}, length={}", fileType, fileName, text.length());
        return text;
    }

    /**
     * PDF段落合并：连续非空行用空格连接（同段落），空行保留为段落分隔
     */
    private String mergeParagraphs(String raw) {
        String[] lines = raw.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) {
                    sb.append("\n\n");
                }
            } else {
                sb.append(line).append("\n");
            }
        }
        String intermediate = sb.toString();
        StringBuilder result = new StringBuilder();
        String[] paragraphs = intermediate.split("\n\n+");
        for (int i = 0; i < paragraphs.length; i++) {
            String para = paragraphs[i].trim();
            if (para.isEmpty()) continue;
            String merged = para.replace("\n", " ");
            result.append(merged).append("\n\n");
        }
        return result.toString();
    }
}
