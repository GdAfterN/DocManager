package com.javaee.docmanager.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextSummarizeVO {
    private String summary;
    private Integer originalLength;
    private Integer summaryLength;
    private Double compressionRatio;
}
