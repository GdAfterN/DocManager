package com.javaee.docmanager.ai.service;

import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文件删除服务 - 直接从MinIO删除文件
 */
@Service
public class FileDeleteService {

    private static final Logger log = LoggerFactory.getLogger(FileDeleteService.class);

    private final MinIOService minIOService;

    @Value("${minio.bucket:documents}")
    private String defaultBucket;

    public FileDeleteService(MinIOService minIOService) {
        this.minIOService = minIOService;
    }

    public void deleteFile(String bucketName, String objectName) {
        log.info("删除文件: bucket={}, object={}", bucketName, objectName);
        try {
            minIOService.deleteFile(bucketName != null ? bucketName : defaultBucket, objectName);
            log.info("文件删除成功: {}", objectName);
        } catch (Exception e) {
            log.error("删除文件失败", e);
            throw new RuntimeException("删除文件失败: " + e.getMessage(), e);
        }
    }
}
