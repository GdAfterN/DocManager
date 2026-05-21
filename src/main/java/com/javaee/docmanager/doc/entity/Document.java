package com.javaee.docmanager.doc.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Document {
    private String id;
    private String title;
    private String content;
    private String summary;
    private String keywords;
    private String fileId;
    private Long userId;
    private String status;
    private Integer version;
    private String category;
    private String tags;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
