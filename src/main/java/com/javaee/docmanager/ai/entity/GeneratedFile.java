package com.javaee.docmanager.ai.entity;

import lombok.Data;
import java.util.Date;

@Data
public class GeneratedFile {
    private String id;
    private String title;
    private String fileFormat;  // word/pdf/ppt
    private String fileId;      // MinIO object key
    private String objectKey;
    private String createBy;
    private Date createTime;
}
