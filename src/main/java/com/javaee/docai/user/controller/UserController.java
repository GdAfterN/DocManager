package com.javaee.docai.user.controller;

import com.javaee.docai.common.model.Result;
import com.javaee.docai.limiter.RateLimit;
import com.javaee.docai.user.dto.LoginDTO;
import com.javaee.docai.user.dto.RegisterDTO;
import com.javaee.docai.user.service.UserService;
import com.javaee.docai.user.vo.LoginVO;
import com.javaee.docai.user.vo.UserVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户登录、注册、信息管理等接口")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/login")
    @RateLimit(timeWindow = 60, maxRequests = 10)
    @Operation(summary = "用户登录", description = "根据用户名和密码进行登录，返回访问令牌和刷新令牌")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        log.info("用户登录: {}", loginDTO.getUsername());
        LoginVO loginVO = userService.login(loginDTO);

        sendUserOperateLog(loginVO.getUser().getId(), "LOGIN", "用户 " + loginVO.getUser().getUsername() + " 登录成功");

        return Result.success(loginVO);
    }

    @PostMapping("/register")
    @RateLimit(timeWindow = 60, maxRequests = 5)
    @Operation(summary = "用户注册", description = "创建新用户，返回用户信息")
    public Result<UserVO> register(@RequestBody RegisterDTO registerDTO) {
        log.info("用户注册: {}", registerDTO.getUsername());
        UserVO userVO = userService.register(registerDTO);

        try {
            Map<String, Object> registerMessage = new HashMap<>();
            registerMessage.put("userId", userVO.getId());
            registerMessage.put("username", userVO.getUsername());
            registerMessage.put("email", userVO.getEmail());
            registerMessage.put("timestamp", LocalDateTime.now().toString());

            log.info("发送用户注册消息到 Kafka");
            kafkaTemplate.send("user-register", objectMapper.writeValueAsString(registerMessage));
        } catch (Exception e) {
            log.error("发送注册消息失败", e);
        }

        sendUserOperateLog(userVO.getId(), "REGISTER", "用户 " + userVO.getUsername() + " 注册成功");

        return Result.success(userVO);
    }

    private void sendUserOperateLog(Long userId, String operation, String description) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("userId", userId);
            message.put("operation", operation);
            message.put("description", description);
            message.put("timestamp", LocalDateTime.now().toString());

            log.info("发送用户操作日志消息到 Kafka");
            kafkaTemplate.send("user-operate-log", objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("发送操作日志消息失败", e);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户信息", description = "根据用户ID获取用户详细信息")
    public Result<UserVO> getUserById(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("获取用户信息: {}", id);
        UserVO userVO = userService.getUserById(id);
        return Result.success(userVO);
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
    public Result<String> refreshToken(@Parameter(description = "刷新令牌") @RequestParam String refreshToken) {
        log.info("刷新令牌");
        String accessToken = userService.refreshToken(refreshToken);
        return Result.success(accessToken);
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "用户登出操作")
    public Result<Void> logout() {
        log.info("用户登出");
        return Result.success();
    }
}
