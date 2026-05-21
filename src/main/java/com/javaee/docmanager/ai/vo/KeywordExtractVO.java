package com.javaee.docmanager.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeywordExtractVO {
    private List<KeywordVO> keywords;
    private Integer totalCount;
}
