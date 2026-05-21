package com.javaee.docmanager.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextAnalyzeVO {
    private Integer totalCharacters;
    private Integer chineseCharacters;
    private Integer englishCharacters;
    private Integer digits;
    private Integer spaces;
    private Integer punctuations;
    private Integer lines;
    private Integer words;
    private Integer sentences;
}
