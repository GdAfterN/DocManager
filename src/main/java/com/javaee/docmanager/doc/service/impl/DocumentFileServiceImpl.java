package com.javaee.docmanager.doc.service.impl;

import com.javaee.docmanager.cache.CacheHelper;
import com.javaee.docmanager.common.constant.RedisKeyEnum;
import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.doc.entity.DocumentFileVersion;
import com.javaee.docmanager.doc.mapper.DocumentBranchMapper;
import com.javaee.docmanager.doc.mapper.DocumentFileMapper;
import com.javaee.docmanager.doc.mapper.DocumentFileVersionMapper;
import com.javaee.docmanager.doc.service.DocumentFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentFileServiceImpl implements DocumentFileService {

    private final DocumentFileMapper documentFileMapper;
    private final DocumentFileVersionMapper documentFileVersionMapper;
    private final DocumentBranchMapper documentBranchMapper;
    private final CacheHelper cacheHelper;

    private static final int MAX_VERSIONS = 10;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public DocumentFile createDocument(String fileId, String title, String version, String fileType, String createBy) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        DocumentFile doc = new DocumentFile();
        doc.setId(id);
        doc.setDocumentId(id);
        doc.setBranchName("main");
        doc.setTitle(title);
        doc.setCurrentFileId(fileId);
        doc.setCurrentVersion(version);
        doc.setFileType(fileType);
        doc.setStatus("active");
        doc.setCreateBy(createBy);
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        documentFileMapper.insert(doc);

        // 保存初始版本
        DocumentFileVersion ver = new DocumentFileVersion();
        ver.setId(UUID.randomUUID().toString());
        ver.setDocumentId(id);
        ver.setBranchId(id);
        ver.setFileId(fileId);
        ver.setVersion(version);
        ver.setChangeLog("初始版本");
        ver.setUploadedBy(createBy);
        ver.setUploadTime(now);
        documentFileVersionMapper.insert(ver);

        // 删除缓存
        cacheHelper.deleteAfterUpdate(
                RedisKeyEnum.DOC_FILE_LIST.getKey(),
                RedisKeyEnum.DOC_FILE.getKey(id)
        );

        log.info("创建文档文件记录: {}, 版本: {}", title, version);
        return doc;
    }

    @Override
    @Transactional
    public DocumentFile uploadNewVersion(String documentId, String fileId, String version, String changeLog, String uploadedBy) {
        DocumentFile doc = documentFileMapper.selectById(documentId);
        if (doc == null) {
            throw new RuntimeException("文档不存在");
        }

        LocalDateTime now = LocalDateTime.now();

        // 保存新版本记录
        DocumentFileVersion ver = new DocumentFileVersion();
        ver.setId(UUID.randomUUID().toString());
        ver.setDocumentId(documentId);
        ver.setBranchId(documentId);
        ver.setFileId(fileId);
        ver.setVersion(version);
        ver.setChangeLog(changeLog);
        ver.setUploadedBy(uploadedBy);
        ver.setUploadTime(now);
        documentFileVersionMapper.insert(ver);

        // 更新文档当前版本
        documentFileMapper.updateCurrentFile(documentId, fileId, version, now.format(FMT));

        // 超过最大版本数时删除最老的
        int count = documentFileVersionMapper.countByBranchId(documentId);
        while (count > MAX_VERSIONS) {
            documentFileVersionMapper.deleteOldestVersionByBranch(documentId);
            count--;
        }

        // 删除缓存
        cacheHelper.deleteAfterUpdate(
                RedisKeyEnum.DOC_FILE_LIST.getKey(),
                RedisKeyEnum.DOC_FILE.getKey(documentId),
                RedisKeyEnum.DOC_FILE_VERSIONS.getKey(documentId)
        );

        log.info("文档 {} 上传新版本: {}", doc.getTitle(), version);
        return documentFileMapper.selectById(documentId);
    }

    @Override
    public List<DocumentFile> getAllDocuments() {
        return documentFileMapper.selectAllMainBranches();
    }

    @Override
    public List<DocumentFileVersion> getVersions(String documentId) {
        return documentFileVersionMapper.selectByBranchId(documentId);
    }

    @Override
    @Transactional
    public DocumentFile restoreVersion(String documentId, String versionId, String restoreBy) {
        DocumentFileVersion targetVersion = documentFileVersionMapper.selectById(versionId);
        if (targetVersion == null || !targetVersion.getDocumentId().equals(documentId)) {
            throw new RuntimeException("版本不存在");
        }

        LocalDateTime now = LocalDateTime.now();

        // 更新文档当前指向
        documentFileMapper.updateCurrentFile(documentId, targetVersion.getFileId(), targetVersion.getVersion(), now.format(FMT));

        // 记录恢复操作作为新版本
        DocumentFileVersion ver = new DocumentFileVersion();
        ver.setId(UUID.randomUUID().toString());
        ver.setDocumentId(documentId);
        ver.setBranchId(documentId);
        ver.setFileId(targetVersion.getFileId());
        ver.setVersion(targetVersion.getVersion());
        ver.setChangeLog("恢复到版本 " + targetVersion.getVersion());
        ver.setUploadedBy(restoreBy);
        ver.setUploadTime(now);
        documentFileVersionMapper.insert(ver);

        // 超过最大版本数时删除最老的
        int count = documentFileVersionMapper.countByBranchId(documentId);
        while (count > MAX_VERSIONS) {
            documentFileVersionMapper.deleteOldestVersionByBranch(documentId);
            count--;
        }

        // 删除缓存
        cacheHelper.deleteAfterUpdate(
                RedisKeyEnum.DOC_FILE_LIST.getKey(),
                RedisKeyEnum.DOC_FILE.getKey(documentId),
                RedisKeyEnum.DOC_FILE_VERSIONS.getKey(documentId)
        );

        log.info("文档 {} 已恢复到版本 {}", documentId, targetVersion.getVersion());
        return documentFileMapper.selectById(documentId);
    }

    @Override
    @Transactional
    public void deleteDocument(String documentId) {
        documentBranchMapper.deleteBranchesByDocumentId(documentId);

        // 删除缓存
        cacheHelper.deleteAfterUpdate(
                RedisKeyEnum.DOC_FILE_LIST.getKey(),
                RedisKeyEnum.DOC_FILE.getKey(documentId),
                RedisKeyEnum.DOC_FILE_VERSIONS.getKey(documentId)
        );
    }

    @Override
    public DocumentFile getById(String id) {
        return documentFileMapper.selectById(id);
    }
}
