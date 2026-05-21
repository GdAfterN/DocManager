package com.javaee.docmanager.ai.dto;

import lombok.Data;

@Data
public class TextSummarizeDTO {
    private String content;
    private Integer maxLength;
}
