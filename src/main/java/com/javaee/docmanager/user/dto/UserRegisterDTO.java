package com.javaee.docmanager.user.dto;

import lombok.Data;

/**
 * @author qxk
 * @description: 用户注册DTO
 */
@Data
public class UserRegisterDTO {

    private String username;

    private String password;

    private String email;

    private String phone;
}
