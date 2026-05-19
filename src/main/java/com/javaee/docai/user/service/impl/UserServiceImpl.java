package com.javaee.docai.user.service.impl;

import com.javaee.docai.common.constant.ErrorCodeEnum;
import com.javaee.docai.common.exception.BusinessException;
import com.javaee.docai.common.utils.JwtUtils;
import com.javaee.docai.common.utils.ValidateUtils;
import com.javaee.docai.user.dto.LoginDTO;
import com.javaee.docai.user.dto.RegisterDTO;
import com.javaee.docai.user.entity.User;
import com.javaee.docai.user.mapper.UserMapper;
import com.javaee.docai.user.service.UserService;
import com.javaee.docai.user.vo.LoginVO;
import com.javaee.docai.user.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        ValidateUtils.notEmpty(loginDTO.getUsername(), "用户名不能为空");
        ValidateUtils.notEmpty(loginDTO.getPassword(), "密码不能为空");

        User user = userMapper.selectByUsername(loginDTO.getUsername());
        if (user == null) {
            throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        }
        if (user.getStatus() == 0) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_ERROR);
        }
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCodeEnum.PASSWORD_ERROR);
        }

        String accessToken = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = JwtUtils.generateRefreshToken(user.getId());

        LoginVO loginVO = new LoginVO();
        loginVO.setAccessToken(accessToken);
        loginVO.setRefreshToken(refreshToken);

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        loginVO.setUser(userVO);

        return loginVO;
    }

    @Override
    public UserVO register(RegisterDTO registerDTO) {
        ValidateUtils.notEmpty(registerDTO.getUsername(), "用户名不能为空");
        ValidateUtils.notEmpty(registerDTO.getPassword(), "密码不能为空");
        ValidateUtils.email(registerDTO.getEmail(), "邮箱格式不正确");
        ValidateUtils.phone(registerDTO.getPhone(), "手机号格式不正确");

        if (userMapper.selectByUsername(registerDTO.getUsername()) != null) {
            throw new BusinessException(ErrorCodeEnum.USER_EXISTED);
        }
        if (userMapper.selectByEmail(registerDTO.getEmail()) != null) {
            throw new BusinessException("邮箱已被注册");
        }
        if (userMapper.selectByPhone(registerDTO.getPhone()) != null) {
            throw new BusinessException("手机号已被注册");
        }

        User user = new User();
        user.setUsername(registerDTO.getUsername());
        user.setPassword(passwordEncoder.encode(registerDTO.getPassword()));
        user.setEmail(registerDTO.getEmail());
        user.setPhone(registerDTO.getPhone());
        user.setRole("USER");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        userMapper.insert(user);

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public User getUserByUsername(String username) {
        ValidateUtils.notEmpty(username, "用户名不能为空");
        return userMapper.selectByUsername(username);
    }

    @Override
    public UserVO getUserById(Long id) {
        ValidateUtils.notNull(id, "用户ID不能为空");
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public String refreshToken(String refreshToken) {
        ValidateUtils.notEmpty(refreshToken, "刷新令牌不能为空");
        try {
            Long userId = JwtUtils.getUserId(refreshToken);
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
            }
            return JwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_ERROR);
        }
    }
}
