package com.javaee.docmanager.ai.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块器
 *
 * Markdown 文档采用两层切分：
 *   第一层：提取代码块和表格（原子块，不可切割）
 *   第二层：剩余文本按标题切分 → 累积+重叠切分
 *
 * Word/PDF 文档按段落切分，超长段落再细分
 */
@Component
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);
    private static final int MAX_CHUNK_SIZE = 800;
    private static final int OVERLAP_SIZE = 100;

    // ==================== 结构化粗切分（两阶段策略第一阶段） ====================

    /**
     * 结构化粗切分入口
     * MD/Markdown: 按 h2 标题切分
     * PDF: 按页切分
     * Word: 按标题样式切分
     * 其他: 按段落切分
     */
    public List<String> chunkByStructure(String content, byte[] rawData, String fileType, String fileName) {
        if (content == null || content.isBlank()) return List.of();

        String type = fileType != null ? fileType.toLowerCase() : "";
        String name = fileName != null ? fileName.toLowerCase() : "";

        List<String> structuralChunks;

        if (type.contains("markdown") || type.contains("plain") || name.endsWith(".md") || name.endsWith(".txt")) {
            // Markdown: 先提取原子块，再按 h2 切分
            structuralChunks = splitMarkdownByH2(content);
        } else if (type.contains("pdf") || name.endsWith(".pdf")) {
            // PDF: 按页切分
            structuralChunks = splitPdfByPage(rawData, content);
        } else if (type.contains("word") || type.contains("msword") || name.endsWith(".docx") || name.endsWith(".doc")) {
            // Word: 按标题样式切分
            structuralChunks = splitWordByStructure(rawData, content);
        } else {
            // 其他文档: 按段落切分
            structuralChunks = splitByParagraphGroup(content, 1500);
        }

        // 合并过短的块（< 200字符）到前一个块
        structuralChunks = mergeShortChunks(structuralChunks, 200);

        log.info("结构化粗切分: fileType={}, chunks={}, 平均长度={}字符",
                type, structuralChunks.size(),
                structuralChunks.stream().mapToInt(String::length).average().orElse(0));

        return structuralChunks;
    }

    /**
     * Markdown 按 h2 标题切分（保留原子块占位符，后续还原）
     */
    private List<String> splitMarkdownByH2(String content) {
        // 先提取原子块
        List<AtomicBlock> atomicBlocks = new ArrayList<>();
        String masked = extractAtomicBlocks(content, atomicBlocks);

        // 按 h2 标题切分
        List<String> sections = new ArrayList<>();
        String[] lines = masked.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.matches("##\\s+.*") && current.length() > 0) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }

        // 还原原子块
        return restoreAtomicBlocks(sections, atomicBlocks);
    }

    /**
     * PDF 按页切分
     */
    private List<String> splitPdfByPage(byte[] rawData, String fallbackText) {
        if (rawData == null || rawData.length == 0) {
            return splitByParagraphGroup(fallbackText, 1500);
        }
        try (PDDocument pdf = Loader.loadPDF(rawData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>();
            int totalPages = pdf.getNumberOfPages();
            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(pdf);
                if (pageText != null && !pageText.isBlank()) {
                    pages.add(pageText.trim());
                }
            }
            log.info("PDF按页切分: {}页", pages.size());
            return pages.isEmpty() ? List.of(fallbackText) : pages;
        } catch (Exception e) {
            log.warn("PDF按页切分失败，回退到段落切分: {}", e.getMessage());
            return splitByParagraphGroup(fallbackText, 1500);
        }
    }

    /**
     * Word 按标题样式切分
     */
    private List<String> splitWordByStructure(byte[] rawData, String fallbackText) {
        if (rawData == null || rawData.length == 0) {
            return splitByParagraphGroup(fallbackText, 1500);
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(rawData))) {
            List<String> sections = new ArrayList<>();
            StringBuilder current = new StringBuilder();

            for (XWPFParagraph para : doc.getParagraphs()) {
                String styleName = para.getStyle();
                boolean isHeading = styleName != null &&
                        (styleName.startsWith("Heading") || styleName.startsWith("heading") ||
                         styleName.equals("Title") || styleName.equals("TOC"));

                if (isHeading && current.length() > 0) {
                    sections.add(current.toString().trim());
                    current = new StringBuilder();
                }
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    current.append(text).append("\n");
                }
            }
            if (current.length() > 0) {
                sections.add(current.toString().trim());
            }

            log.info("Word按标题切分: {}个section", sections.size());
            return sections.isEmpty() ? List.of(fallbackText) : sections;
        } catch (Exception e) {
            log.warn("Word按标题切分失败，回退到段落切分: {}", e.getMessage());
            return splitByParagraphGroup(fallbackText, 1500);
        }
    }

    /**
     * 按段落分组切分，每组不超过 maxSize 字符
     */
    private List<String> splitByParagraphGroup(String text, int maxSize) {
        String[] paragraphs = text.split("\n\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            if (current.length() + trimmed.length() > maxSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    /**
     * 合并过短的块到前一个块
     */
    private List<String> mergeShortChunks(List<String> chunks, int minSize) {
        if (chunks.size() <= 1) return chunks;
        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = result.get(result.size() - 1);
            String curr = chunks.get(i);
            if (curr.length() < minSize) {
                result.set(result.size() - 1, prev + "\n\n" + curr);
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    /**
     * Markdown 分块入口（两层切分）
     */
    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        // 第一层：提取代码块和表格，替换为占位符
        List<AtomicBlock> atomicBlocks = new ArrayList<>();
        String masked = extractAtomicBlocks(content, atomicBlocks);

        // 第二层：对剩余文本按标题 → 段落 → 累积切分
        List<String> textChunks = chunkText(masked);

        // 把占位符替换回原子块内容
        return restoreAtomicBlocks(textChunks, atomicBlocks);
    }

    /**
     * Word/PDF 分块入口（按段落切分）
     */
    public List<String> chunkByParagraph(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String[] paragraphs = content.split("\n\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lastTail = "";

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() > MAX_CHUNK_SIZE && current.length() > 0) {
                String chunk = current.toString().trim();
                chunks.add(chunk);
                lastTail = chunk.length() > OVERLAP_SIZE
                        ? chunk.substring(chunk.length() - OVERLAP_SIZE)
                        : chunk;
                current = new StringBuilder();
            }
            if (current.length() == 0 && !lastTail.isEmpty()) {
                current.append(lastTail).append("\n\n");
            }
            if (trimmed.length() > MAX_CHUNK_SIZE) {
                // 超长段落：先输出已积累内容，再硬切当前段落
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                    lastTail = "";
                }
                for (int i = 0; i < trimmed.length(); i += MAX_CHUNK_SIZE) {
                    chunks.add(trimmed.substring(i, Math.min(i + MAX_CHUNK_SIZE, trimmed.length())));
                }
            } else {
                current.append(trimmed).append("\n\n");
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    // ==================== 第一层：提取原子块 ====================

    /**
     * 原子块：代码块或表格，不可切割
     */
    private static class AtomicBlock {
        final String placeholder;
        final String content;

        AtomicBlock(String placeholder, String content) {
            this.placeholder = placeholder;
            this.content = content;
        }
    }

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "(```[\\s\\S]*?```|~~~[\\s\\S]*?~~~)", Pattern.MULTILINE);

    /**
     * 提取代码块和表格，替换为占位符
     */
    private String extractAtomicBlocks(String content, List<AtomicBlock> blocks) {
        // 先提取代码块（正则匹配，跨行）
        StringBuilder result = new StringBuilder();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        int lastEnd = 0;
        while (matcher.find()) {
            // 代码块前的文本，继续检测表格
            String before = content.substring(lastEnd, matcher.start());
            result.append(extractTables(before, blocks));
            // 代码块整体保留
            String placeholder = "\n__ATOMIC_" + blocks.size() + "__\n";
            blocks.add(new AtomicBlock(placeholder, matcher.group()));
            result.append(placeholder);
            lastEnd = matcher.end();
        }
        // 剩余文本，检测表格
        String remaining = content.substring(lastEnd);
        result.append(extractTables(remaining, blocks));
        return result.toString();
    }

    /**
     * 从文本中提取表格，替换为占位符
     */
    private String extractTables(String text, List<AtomicBlock> blocks) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        List<String> tableBuffer = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (isTableLine(trimmed)) {
                tableBuffer.add(lines[i]);
            } else {
                if (!tableBuffer.isEmpty()) {
                    // 输出积累的表格
                    String table = String.join("\n", tableBuffer);
                    String placeholder = "\n__ATOMIC_" + blocks.size() + "__\n";
                    blocks.add(new AtomicBlock(placeholder, table));
                    result.append(placeholder);
                    tableBuffer.clear();
                }
                result.append(lines[i]);
                if (i < lines.length - 1) result.append("\n");
            }
        }
        if (!tableBuffer.isEmpty()) {
            String table = String.join("\n", tableBuffer);
            String placeholder = "\n__ATOMIC_" + blocks.size() + "__\n";
            blocks.add(new AtomicBlock(placeholder, table));
            result.append(placeholder);
        }
        return result.toString();
    }

    // ==================== 第二层：文本切分 ====================

    /**
     * 对去除原子块后的文本做切分：按标题分段 → 累积+重叠
     */
    private List<String> chunkText(String text) {
        List<String> sections = splitByHeading(text);
        List<String> chunks = new ArrayList<>();

        for (String section : sections) {
            if (section.length() <= MAX_CHUNK_SIZE) {
                chunks.add(section.trim());
            } else {
                chunks.addAll(splitByParagraph(section));
            }
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    private List<String> splitByHeading(String text) {
        List<String> sections = new ArrayList<>();
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
        String[] paragraphs = text.split("\n\n+");
        if (paragraphs.length <= 1) {
            paragraphs = text.split("\n");
        }
        return accumulate(paragraphs);
    }

    /**
     * 逐行累积，超长切分，相邻 chunk 重叠
     */
    private List<String> accumulate(String[] lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lastTail = "";

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() > MAX_CHUNK_SIZE && current.length() > 0) {
                String chunk = current.toString().trim();
                chunks.add(chunk);
                lastTail = chunk.length() > OVERLAP_SIZE
                        ? chunk.substring(chunk.length() - OVERLAP_SIZE)
                        : chunk;
                current = new StringBuilder();
            }
            if (current.length() == 0 && !lastTail.isEmpty()) {
                current.append(lastTail).append("\n");
            }
            if (trimmed.length() > MAX_CHUNK_SIZE) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                for (int i = 0; i < trimmed.length(); i += MAX_CHUNK_SIZE) {
                    chunks.add(trimmed.substring(i, Math.min(i + MAX_CHUNK_SIZE, trimmed.length())));
                }
                lastTail = "";
            } else {
                current.append(trimmed).append("\n");
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    // ==================== 恢复原子块 ====================

    /**
     * 将占位符替换回原子块内容
     */
    private List<String> restoreAtomicBlocks(List<String> chunks, List<AtomicBlock> blocks) {
        // 先合并跨 chunk 边界的断裂占位符（如 __ATOMIC_ 结尾 + 44__ 开头）
        List<String> merged = mergeSplitPlaceholders(chunks);

        List<String> result = new ArrayList<>();
        for (String chunk : merged) {
            String restored = chunk;
            for (AtomicBlock block : blocks) {
                if (restored.contains(block.placeholder)) {
                    restored = restored.replace(block.placeholder, block.content);
                }
            }
            if (restored.contains("__ATOMIC_")) {
                for (AtomicBlock block : blocks) {
                    restored = restored.replace(block.placeholder, block.content);
                }
            }
            if (!restored.isBlank()) {
                result.add(restored.trim());
            }
        }

        // 检查是否有原子块没被包含
        for (AtomicBlock block : blocks) {
            boolean found = false;
            String preview = block.content.trim().substring(0, Math.min(50, block.content.trim().length()));
            for (String chunk : result) {
                if (chunk.contains(preview)) {
                    found = true;
                    break;
                }
            }
            if (!found && !block.content.isBlank()) {
                result.add(block.content.trim());
            }
        }

        return result;
    }

    /**
     * 合并因切分导致断裂的占位符
     * 例如：chunk A 以 "...__ATOMIC_" 结尾，chunk B 以 "44__..." 开头
     * 合并为一个 chunk 使占位符完整
     */
    private List<String> mergeSplitPlaceholders(List<String> chunks) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String current = chunks.get(i);
            // 检查当前 chunk 是否以 __ATOMIC_ 的不完整形式结尾
            while (i + 1 < chunks.size()) {
                String tail = getTrailingAtomicPrefix(current);
                if (tail.isEmpty()) break;
                String nextChunk = chunks.get(i + 1);
                String head = getLeadingAtomicSuffix(nextChunk);
                if (head.isEmpty()) break;
                // 拼接后检查是否构成完整的占位符
                String combined = tail + head;
                if (combined.matches("__ATOMIC_\\d+__")) {
                    // 合并两个 chunk
                    current = current + nextChunk;
                    i++;
                } else {
                    break;
                }
            }
            result.add(current);
        }
        return result;
    }

    /**
     * 获取字符串末尾不完整的 __ATOMIC_ 前缀（如 "__ATOMIC_"、"__ATOMIC_4"、"__ATOMIC_44"）
     */
    private String getTrailingAtomicPrefix(String text) {
        // 检查末尾是否有 __ATOMIC_ 的部分（未闭合的占位符）
        for (int len = Math.min(18, text.length()); len >= 9; len--) {
            String suffix = text.substring(text.length() - len);
            if (suffix.startsWith("__ATOMIC_") && !suffix.endsWith("__")) {
                return suffix;
            }
        }
        return "";
    }

    /**
     * 获取字符串开头的数字+__ 或 __ 后缀（如 "44__" 或 "__"）
     */
    private String getLeadingAtomicSuffix(String text) {
        if (text.length() < 2) return "";
        // 情况1: 以 __ 开头（如 "__" 来自 "__ATOMIC_44__" 被切成 "__ATOMIC_44" + "__"）
        if (text.startsWith("__")) {
            return "__";
        }
        // 情况2: 以 digits__ 开头（如 "44__" 来自 "__ATOMIC_44__" 被切成 "__ATOMIC_" + "44__"）
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < Math.min(5, text.length()); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '_' && digits.length() > 0 && i + 1 < text.length() && text.charAt(i + 1) == '_') {
                return digits.toString() + "__";
            } else {
                break;
            }
        }
        return "";
    }

    // ==================== 工具方法 ====================

    private boolean isTableLine(String line) {
        if (line.isEmpty()) return false;
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) return true;
        if (trimmed.matches("^[|:\\-\\s]+$") && trimmed.contains("-")) return true;
        return false;
    }
}
