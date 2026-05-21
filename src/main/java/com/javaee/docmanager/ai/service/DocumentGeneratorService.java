package com.javaee.docmanager.ai.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
@Service
public class DocumentGeneratorService {

    public byte[] generate(String format, String title, String content) {
        log.info("生成文件: format={}, title={}", format, title);
        return switch (format.toLowerCase()) {
            case "word" -> generateWord(title, content);
            case "pdf" -> generatePdf(title, content);
            case "ppt" -> generatePpt(title, content);
            default -> throw new IllegalArgumentException("不支持的格式: " + format);
        };
    }

    public byte[] generateWord(String title, String content) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 标题
            XWPFParagraph titlePara = doc.createParagraph();
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            // 空行
            doc.createParagraph();

            // 内容
            for (String line : content.split("\n")) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line);
                run.setFontSize(12);
            }

            doc.write(out);
            log.info("Word文件生成成功, size={} bytes", out.size());
            return out.toByteArray();
        } catch (IOException e) {
            log.error("生成Word失败", e);
            throw new RuntimeException("生成Word文件失败: " + e.getMessage(), e);
        }
    }

    public byte[] generatePdf(String title, String content) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            // 标题
            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            document.add(new Paragraph(title, titleFont));
            document.add(new Paragraph(" "));

            // 内容
            Font bodyFont = new Font(Font.HELVETICA, 12, Font.NORMAL);
            for (String line : content.split("\n")) {
                document.add(new Paragraph(line, bodyFont));
            }

            document.close();
            log.info("PDF文件生成成功, size={} bytes", out.size());
            return out.toByteArray();
        } catch (Exception e) {
            log.error("生成PDF失败", e);
            throw new RuntimeException("生成PDF文件失败: " + e.getMessage(), e);
        }
    }

    public byte[] generatePpt(String title, String content) {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 标题页
            XSLFSlide titleSlide = ppt.createSlide();
            XSLFTextBox titleBox = titleSlide.createTextBox();
            titleBox.setAnchor(new java.awt.Rectangle(50, 50, 600, 100));
            XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
            XSLFTextRun titleRun = titlePara.addNewTextRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(24.0);

            // 内容页
            XSLFSlide contentSlide = ppt.createSlide();
            XSLFTextBox contentBox = contentSlide.createTextBox();
            contentBox.setAnchor(new java.awt.Rectangle(50, 50, 600, 400));

            for (String line : content.split("\n")) {
                XSLFTextParagraph para = contentBox.addNewTextParagraph();
                XSLFTextRun run = para.addNewTextRun();
                run.setText(line);
                run.setFontSize(16.0);
            }

            ppt.write(out);
            log.info("PPT文件生成成功, size={} bytes", out.size());
            return out.toByteArray();
        } catch (IOException e) {
            log.error("生成PPT失败", e);
            throw new RuntimeException("生成PPT文件失败: " + e.getMessage(), e);
        }
    }
}
