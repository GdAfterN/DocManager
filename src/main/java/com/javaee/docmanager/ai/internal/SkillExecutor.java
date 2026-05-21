package com.javaee.docmanager.ai.internal;

import com.javaee.docmanager.ai.dto.FileDownloadDTO;
import com.javaee.docmanager.ai.service.FileDeleteService;
import com.javaee.docmanager.ai.service.FileDownloadService;
import com.javaee.docmanager.ai.vo.FileDownloadVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 技能执行器
 * 执行各种技能操作
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    @Autowired
    private InternalService internalService;

    @Autowired
    private FileDeleteService fileDeleteService;

    @Autowired
    private FileDownloadService fileDownloadService;

    public Map<String, Object> execute(String skillName, Map<String, Object> params) {
        log.info("执行技能: skillName={}, params={}", skillName, params);

        try {
            Map<String, Object> result;

            switch (skillName) {
                case "file-delete":
                    fileDeleteService.deleteFile(
                        (String) params.get("bucketName"),
                        (String) params.get("objectName")
                    );
                    result = Map.of("status", "success", "message", "文件已删除");
                    break;
                case "file-download":
                    result = executeFileDownload(params);
                    break;
                default:
                    result = Map.of("status", "error", "message", "未知技能: " + skillName);
            }

            internalService.logAudit(skillName, params, result);
            return result;

        } catch (Exception e) {
            log.error("技能执行失败", e);
            Map<String, Object> errorResult = Map.of("status", "error", "message", e.getMessage());
            internalService.logAudit(skillName, params, errorResult);
            return errorResult;
        }
    }

    private Map<String, Object> executeFileDownload(Map<String, Object> params) {
        FileDownloadDTO dto = new FileDownloadDTO();
        dto.setBucketName((String) params.get("bucketName"));
        dto.setObjectName((String) params.get("objectName"));

        FileDownloadVO vo = fileDownloadService.getFileUrl(dto);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("fileUrl", vo.getFileUrl());
        result.put("bucketName", vo.getBucketName());
        result.put("objectName", vo.getObjectName());
        result.put("expirySeconds", vo.getExpirySeconds());
        return result;
    }

    public Map<String, Map<String, Object>> listSkills() {
        Map<String, Map<String, Object>> skills = new HashMap<>();
        skills.put("file-delete", internalService.getSkillDescription("file-delete"));
        skills.put("file-download", internalService.getSkillDescription("file-download"));
        return skills;
    }
}
