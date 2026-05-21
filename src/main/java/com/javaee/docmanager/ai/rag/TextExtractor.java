package com.javaee.docmanager.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class TextExtractor {

    public String extract(String fileType, byte[] data) {
        return extract(fileType, null, data);
    }

    public String extract(String fileType, String fileName, byte[] data) {
        if (fileType == null && fileName == null) return "";
        String type = fileType != null ? fileType.toLowerCase() : "";
        String name = fileName != null ? fileName.toLowerCase() : "";
        if (type.contains("pdf") || name.endsWith(".pdf")) {
            return extractPdf(data);
        } else if (type.contains("word") || type.contains("msword") || name.endsWith(".docx") || name.endsWith(".doc")) {
            return extractWord(data);
        } else if (type.contains("powerpoint") || type.contains("presentation") || name.endsWith(".pptx") || name.endsWith(".ppt")) {
            return extractPpt(data);
        } else if (type.contains("csv") || name.endsWith(".csv")) {
            return extractCsv(data);
        } else if (type.contains("markdown") || type.contains("plain") || name.endsWith(".md")) {
            return new String(data);
        }
        // 兜底：纯文本类型按文本处理
        if (type.startsWith("text/") || type.contains("octet-stream") && (name.endsWith(".md") || name.endsWith(".txt"))) {
            return new String(data);
        }
        log.warn("不支持的文件类型用于文本提取: fileType={}, fileName={}", fileType, fileName);
        return "";
    }

    public String extractPdf(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            log.debug("PDF文本提取成功, length={}", text.length());
            return text;
        } catch (IOException e) {
            log.error("PDF文本提取失败", e);
            return "";
        }
    }

    public String extractWord(byte[] data) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            log.debug("Word文本提取成功, length={}", sb.length());
            return sb.toString();
        } catch (IOException e) {
            log.error("Word文本提取失败", e);
            return "";
        }
    }

    public String extractPpt(byte[] data) {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
            }
            log.debug("PPT文本提取成功, length={}", sb.length());
            return sb.toString();
        } catch (IOException e) {
            log.error("PPT文本提取失败", e);
            return "";
        }
    }

    public String extractCsv(byte[] data) {
        String text = new String(data);
        log.debug("CSV文本提取成功, length={}", text.length());
        return text;
    }
}
