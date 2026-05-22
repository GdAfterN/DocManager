package com.javaee.docmanager.doc.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentFileVersion {
    private String id;
    private String documentId;
    private String branchId;
    private String fileId;
    private String version;
    private String changeLog;
    private String uploadedBy;
    private LocalDateTime uploadTime;
}
