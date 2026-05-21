package com.javaee.docmanager.doc.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentVersion {
    private String id;
    private String documentId;
    private Integer versionNumber;
    private String title;
    private String content;
    private String summary;
    private String keywords;
    private String changeLog;
    private String createdBy;
    private LocalDateTime createTime;
}
