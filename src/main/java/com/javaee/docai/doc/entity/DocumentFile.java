package com.javaee.docai.doc.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentFile {
    private String id;
    private String title;
    private String currentFileId;
    private String currentVersion;
    private String fileType;
    private String status;
    private String createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
