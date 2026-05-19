package com.javaee.docai.ai.dto;

import lombok.Data;

@Data
public class TextSummarizeDTO {
    private String content;
    private Integer maxLength;
}
