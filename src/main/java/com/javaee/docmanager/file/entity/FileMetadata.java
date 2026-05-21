package com.javaee.docmanager.file.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FileMetadata {
    private String id;
    private String fileId;
    private String fileName;
    private String originalFileName;
    private String filePath;
    private String fileType;
    private long fileSize;
    private String md5;
    private String storageType;
    private String bucketName;
    private String objectKey;
    private String status;
    private String createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
