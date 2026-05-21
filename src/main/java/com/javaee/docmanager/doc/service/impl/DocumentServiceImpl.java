package com.javaee.docmanager.doc.service.impl;

import com.javaee.docmanager.common.exception.BusinessException;
import com.javaee.docmanager.doc.dto.DocumentCreateDTO;
import com.javaee.docmanager.doc.dto.DocumentQueryDTO;
import com.javaee.docmanager.doc.dto.DocumentUpdateDTO;
import com.javaee.docmanager.doc.entity.Document;
import com.javaee.docmanager.doc.entity.DocumentVersion;
import com.javaee.docmanager.doc.mapper.DocumentMapper;
import com.javaee.docmanager.doc.mapper.DocumentVersionMapper;
import com.javaee.docmanager.doc.service.DocumentContentService;
import com.javaee.docmanager.doc.service.DocumentService;
import com.javaee.docmanager.doc.util.DocumentParserUtil;
import com.javaee.docmanager.doc.vo.DocumentVO;
import com.javaee.docmanager.file.mapper.FileMetadataMapper;
import com.javaee.docmanager.file.service.FileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentVersionMapper documentVersionMapper;

    @Autowired
    private DocumentContentService documentContentService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional
    public DocumentVO create(DocumentCreateDTO dto, Long userId) {
        Document document = new Document();
        document.setId(UUID.randomUUID().toString());
        document.setTitle(dto.getTitle());
        document.setFileId(dto.getFileId());
        document.setCategory(dto.getCategory());
        document.setTags(convertListToJson(dto.getTags()));
        document.setUserId(userId);
        document.setStatus("active");
        document.setVersion(1);
        document.setCreatedBy(String.valueOf(userId));
        document.setCreateTime(LocalDateTime.now());
        document.setUpdateTime(LocalDateTime.now());

        documentMapper.insert(document);

        // 通过fileId获取文件内容并解析
        String content = "";
        String fileName = "";
        if (dto.getFileId() != null && !dto.getFileId().isEmpty()) {
            try {
                var metadata = fileMetadataMapper.selectByFileId(dto.getFileId());
                fileName = metadata != null ? metadata.getOriginalFileName() : "unknown";

                byte[] fileContent = fileService.download(dto.getFileId());
                if (fileContent != null && fileContent.length > 0) {
                    content = DocumentParserUtil.parseDocument(fileContent, fileName);
                    log.info("文档解析成功: fileId={}, fileName={}, contentLength={}",
                            dto.getFileId(), fileName, content.length());
                }
            } catch (Exception e) {
                log.error("获取文件失败: fileId={}", dto.getFileId(), e);
                throw new BusinessException("获取文件内容失败: " + e.getMessage());
            }
        }

        if (content != null && !content.isEmpty()) {
            documentContentService.saveContent(document.getId(), content);
            document.setSummary(DocumentParserUtil.getSummary(content, 200));
            documentMapper.updateById(document);
        }

        saveVersion(document, "初始版本", content);

        DocumentVO vo = convertToVO(document);
        vo.setContent(content);
        return vo;
    }

    @Override
    @Transactional
    public DocumentVO update(String id, DocumentUpdateDTO dto, Long userId) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }

        String currentContent = documentContentService.getContent(id);
        saveVersion(document, dto.getChangeLog(), currentContent);

        document.setTitle(dto.getTitle());
        document.setCategory(dto.getCategory());
        document.setTags(convertListToJson(dto.getTags()));
        document.setVersion(document.getVersion() + 1);
        document.setUpdateTime(LocalDateTime.now());

        documentMapper.updateById(document);

        if (dto.getContent() != null && !dto.getContent().isEmpty()) {
            documentContentService.updateContent(id, dto.getContent());
            document.setSummary(DocumentParserUtil.getSummary(dto.getContent(), 200));
            documentMapper.updateById(document);
        }

        DocumentVO vo = convertToVO(document);
        vo.setContent(dto.getContent());
        return vo;
    }

    @Override
    @Transactional
    public void delete(String id, Long userId) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        document.setStatus("deleted");
        document.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(document);
        documentContentService.deleteContent(id);
    }

    @Override
    public DocumentVO getById(String id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        DocumentVO vo = convertToVO(document);
        String content = documentContentService.getContent(id);
        vo.setContent(content);
        return vo;
    }

    @Override
    public List<DocumentVO> getByUserId(Long userId) {
        List<Document> documents = documentMapper.selectByUserId(userId);
        return documents.stream().map(this::convertToVOWithoutContent).collect(Collectors.toList());
    }

    @Override
    public List<DocumentVO> search(DocumentQueryDTO dto) {
        List<Document> documents;
        if (dto.getKeyword() != null && !dto.getKeyword().isEmpty()) {
            documents = documentMapper.searchByKeyword(dto.getKeyword());
        } else if (dto.getCategory() != null && !dto.getCategory().isEmpty()) {
            documents = documentMapper.selectByCategory(dto.getCategory());
        } else {
            documents = documentMapper.selectByStatus("active");
        }
        return documents.stream().map(this::convertToVOWithoutContent).collect(Collectors.toList());
    }

    @Override
    public List<DocumentVersion> getVersions(String documentId) {
        return documentVersionMapper.selectByDocumentId(documentId);
    }

    @Override
    @Transactional
    public DocumentVO restoreVersion(String documentId, Integer versionNumber, Long userId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }

        DocumentVersion version = documentVersionMapper.selectByDocumentIdAndVersion(documentId, versionNumber);
        if (version == null) {
            throw new BusinessException("版本不存在");
        }

        String currentContent = documentContentService.getContent(documentId);
        saveVersion(document, "恢复到版本" + versionNumber, currentContent);

        document.setTitle(version.getTitle());
        document.setSummary(version.getSummary());
        document.setKeywords(version.getKeywords());
        document.setVersion(document.getVersion() + 1);
        document.setUpdateTime(LocalDateTime.now());

        documentMapper.updateById(document);
        documentContentService.updateContent(documentId, version.getContent());

        DocumentVO vo = convertToVO(document);
        vo.setContent(version.getContent());
        return vo;
    }

    private void saveVersion(Document document, String changeLog, String content) {
        DocumentVersion version = new DocumentVersion();
        version.setId(UUID.randomUUID().toString());
        version.setDocumentId(document.getId());
        version.setVersionNumber(document.getVersion());
        version.setTitle(document.getTitle());
        version.setContent(content);
        version.setSummary(document.getSummary());
        version.setKeywords(document.getKeywords());
        version.setChangeLog(changeLog);
        version.setCreatedBy(document.getCreatedBy());
        version.setCreateTime(LocalDateTime.now());

        documentVersionMapper.insert(version);
    }

    private DocumentVO convertToVO(Document document) {
        DocumentVO vo = new DocumentVO();
        vo.setId(document.getId());
        vo.setTitle(document.getTitle());
        vo.setSummary(document.getSummary());
        vo.setKeywords(convertJsonToList(document.getKeywords()));
        vo.setFileId(document.getFileId());
        vo.setCategory(document.getCategory());
        vo.setTags(convertJsonToList(document.getTags()));
        vo.setVersion(document.getVersion());
        vo.setStatus(document.getStatus());
        vo.setCreatedBy(document.getCreatedBy());
        vo.setCreateTime(document.getCreateTime());
        vo.setUpdateTime(document.getUpdateTime());
        return vo;
    }

    private DocumentVO convertToVOWithoutContent(Document document) {
        return convertToVO(document);
    }

    private String convertListToJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> convertJsonToList(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return Arrays.asList(objectMapper.readValue(json, String[].class));
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}
