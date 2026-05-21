package com.javaee.docmanager.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeywordVO {
    private String word;
    private Double score;
    private String type;
}
