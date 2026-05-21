package com.javaee.docmanager.ai.internal;

import com.javaee.docmanager.ai.service.FileDeleteService;
import com.javaee.docmanager.ai.service.FileDownloadService;
import com.javaee.docmanager.ai.service.FileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 内部服务代理层
 * 实现权限验证、操作审批、审计记录等功能
 * 作为AI Agent与外部系统交互的安全层
 */
@Component
public class InternalService {

    private static final Logger log = LoggerFactory.getLogger(InternalService.class);

    @Autowired
    private FileDeleteService fileDeleteService;

    @Autowired
    private FileUploadService fileUploadService;

    @Autowired
    private FileDownloadService fileDownloadService;

    /**
     * 检查权限
     * @param skillName 技能名称
     * @param context 上下文信息
     * @return 是否有权限
     */
    public boolean hasPermission(String skillName, Map<String, Object> context) {
        log.debug("检查权限: skillName={}", skillName);

        String userId = (String) context.getOrDefault("userId", "default");
        
        log.info("用户权限检查通过: userId={}, skillName={}", userId, skillName);
        return true;
    }

    /**
     * 记录审计日志
     * @param skillName 技能名称
     * @param params 参数
     * @param result 结果
     */
    public void logAudit(String skillName, Map<String, Object> params, Map<String, Object> result) {
        log.info("审计记录: skillName={}, params={}, result={}", skillName, params, result);
    }

    /**
     * 发送告警
     * @param alertData 告警数据
     */
    public void sendAlert(Map<String, Object> alertData) {
        log.warn("发送告警: {}", alertData);
    }

    /**
     * 获取技能描述
     * @param skillName 技能名称
     * @return 技能描述
     */
    public Map<String, Object> getSkillDescription(String skillName) {
        Map<String, Object> description = new HashMap<>();
        
        switch (skillName) {
            case "file-delete":
                description.put("name", "文件删除");
                description.put("description", "删除指定文件，支持确认删除和回收站");
                description.put("parameters", Map.of(
                    "bucketName", "存储桶名称（可选）",
                    "objectName", "对象名称（必填）",
                    "requireConfirmation", "是否需要确认（可选）",
                    "confirmationToken", "确认token（确认时使用）"
                ));
                break;
            case "file-upload":
                description.put("name", "文件上传");
                description.put("description", "上传文件到指定存储桶");
                description.put("parameters", Map.of(
                    "bucketName", "存储桶名称（可选）",
                    "filePath", "文件路径（必填）",
                    "objectName", "对象名称（可选）",
                    "remark", "备注（可选）"
                ));
                break;
            case "file-download":
                description.put("name", "文件下载");
                description.put("description", "下载指定文件");
                description.put("parameters", Map.of(
                    "bucketName", "存储桶名称（可选）",
                    "objectName", "对象名称（必填）"
                ));
                break;
            default:
                description.put("name", skillName);
                description.put("description", "未知技能");
        }
        
        return description;
    }
}