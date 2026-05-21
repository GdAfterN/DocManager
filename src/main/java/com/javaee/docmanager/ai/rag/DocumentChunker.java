package com.javaee.docmanager.ai.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块器
 * 将长文档按标题/段落切分为小块，每块单独向量化
 */
@Component
public class DocumentChunker {

    private static final int MAX_CHUNK_SIZE = 800;
    private static final int OVERLAP_SIZE = 100;

    /**
     * 分块入口
     */
    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // 先按 Markdown 标题分块
        List<String> sections = splitByHeading(content);

        // 如果没有标题切分（仅1个section），说明是纯文本/Word文档，直接按段落分块
        if (sections.size() == 1) {
            return splitByParagraph(sections.get(0)).stream()
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        // 对过长的段落再按段落分块
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= MAX_CHUNK_SIZE) {
                chunks.add(section.trim());
            } else {
                chunks.addAll(splitByParagraph(section));
            }
        }
        // 过滤空块
        return chunks.stream()
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<String> splitByHeading(String text) {
        List<String> sections = new ArrayList<>();
        // 支持所有级别标题（# ## ### #### ##### ######）
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.matches("#{1,6}\\s+.*") && current.length() > 0) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        return sections;
    }

    private List<String> splitByParagraph(String text) {
        // 先按双换行切分，如果切不出（如Word文档），再按单换行切分
        String[] paragraphs = text.split("\n\n+");
        if (paragraphs.length <= 1) {
            paragraphs = text.split("\n");
        }
        return splitByLines(paragraphs);
    }

    private List<String> splitByLines(String[] lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // 用于保存上一个chunk的尾部，作为下一个chunk的开头重叠
        String lastTail = "";
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (current.length() + line.length() > MAX_CHUNK_SIZE && current.length() > 0) {
                String chunk = current.toString().trim();
                chunks.add(chunk);
                // 保留尾部作为下一个chunk的重叠
                lastTail = chunk.length() > OVERLAP_SIZE
                        ? chunk.substring(chunk.length() - OVERLAP_SIZE)
                        : chunk;
                current = new StringBuilder();
            }
            // 新chunk开头加上上一个chunk的尾部（重叠）
            if (current.length() == 0 && !lastTail.isEmpty()) {
                current.append(lastTail).append("\n");
            }
            // 单行超过限制，按固定长度硬切
            if (line.length() > MAX_CHUNK_SIZE) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                for (int i = 0; i < line.length(); i += MAX_CHUNK_SIZE) {
                    chunks.add(line.substring(i, Math.min(i + MAX_CHUNK_SIZE, line.length())));
                }
                lastTail = "";
            } else {
                current.append(line).append("\n");
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }
}
