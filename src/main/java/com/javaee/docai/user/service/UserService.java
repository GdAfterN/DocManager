package com.javaee.docai.user.service;

import com.javaee.docai.user.dto.LoginDTO;
import com.javaee.docai.user.dto.RegisterDTO;
import com.javaee.docai.user.entity.User;
import com.javaee.docai.user.vo.LoginVO;
import com.javaee.docai.user.vo.UserVO;

public interface UserService {

    LoginVO login(LoginDTO loginDTO);

    UserVO register(RegisterDTO registerDTO);

    User getUserByUsername(String username);

    UserVO getUserById(Long id);

    String refreshToken(String refreshToken);
}
