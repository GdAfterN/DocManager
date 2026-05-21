package com.javaee.docmanager.user.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String email;
    private String phone;
    private String role;
    private Integer status;
    private Long ragTokensInput;
    private Long ragTokensOutput;
    private Long pptTokensInput;
    private Long pptTokensOutput;
    private Long ragDocCount;
    private Long ragSliceCount;
    private Long pptCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
