package com.javaee.docmanager.ai.agent;

import com.javaee.docmanager.ai.internal.InternalService;
import com.javaee.docmanager.ai.internal.SkillExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Plan-Execute-Replan Agent
 * 基于Plan-Execute模式实现智能规划
 * 支持复杂任务的分解和执行
 * 实现自动调整和优化执行计划
 */
@Component
public class PlanExecuteAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private SkillExecutor skillExecutor;

    @Autowired
    private InternalService internalService;

    /**
     * 执行规划任务
     * @param task 任务描述
     * @param context 上下文信息
     * @return 执行结果
     */
    public Map<String, Object> execute(String task, Map<String, Object> context) {
        log.info("开始执行规划任务: task={}", task);

        try {
            List<String> plan = planTask(task, context);
            log.info("任务规划完成，共{}个步骤", plan.size());

            Map<String, Object> results = new LinkedHashMap<>();
            boolean success = true;
            String errorMessage = "";

            for (int i = 0; i < plan.size(); i++) {
                String step = plan.get(i);
                log.info("执行步骤 {}/{}: {}", i + 1, plan.size(), step);

                try {
                    Map<String, Object> stepResult = executeStep(step, context);
                    results.put("step_" + (i + 1), stepResult);

                    if (!"success".equals(stepResult.get("status"))) {
                        success = false;
                        errorMessage = (String) stepResult.get("message");
                        break;
                    }

                    context.put("lastStepResult", stepResult);
                } catch (Exception e) {
                    log.error("步骤执行失败: {}", step, e);
                    success = false;
                    errorMessage = e.getMessage();
                    break;
                }
            }

            if (!success) {
                results = replanAndExecute(task, plan, context, errorMessage);
            }

            return Map.of(
                "status", success ? "success" : "partial",
                "plan", plan,
                "results", results,
                "message", success ? "任务执行完成" : "任务部分执行完成"
            );

        } catch (Exception e) {
            log.error("规划执行失败", e);
            return Map.of(
                "status", "error",
                "message", "任务执行失败: " + e.getMessage()
            );
        }
    }

    /**
     * 规划任务
     */
    private List<String> planTask(String task, Map<String, Object> context) {
        String prompt = String.format(
            "请将以下任务分解为可执行的步骤列表：\n\n任务：%s\n\n上下文：%s\n\n请返回步骤列表，每行一个步骤，用数字序号开头。",
            task, context
        );

        String response = chatService.callChatApi(prompt, "rag.tokens");

        List<String> steps = new ArrayList<>();
        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.matches("^\\d+\\..*")) {
                steps.add(trimmed.replaceAll("^\\d+\\.?\\s*", ""));
            }
        }

        return steps;
    }

    /**
     * 执行单个步骤
     */
    private Map<String, Object> executeStep(String step, Map<String, Object> context) {
        String skillName = identifySkill(step);

        if (skillName != null) {
            return executeSkill(skillName, step, context);
        }

        return executeDirect(step, context);
    }

    /**
     * 识别技能
     */
    private String identifySkill(String step) {
        if (step.contains("删除") || step.contains("删除文件")) {
            return "file-delete";
        } else if (step.contains("上传") || step.contains("上传文件")) {
            return "file-upload";
        } else if (step.contains("下载") || step.contains("下载文件")) {
            return "file-download";
        }
        return null;
    }

    /**
     * 执行技能
     */
    private Map<String, Object> executeSkill(String skillName, String step, Map<String, Object> context) {
        log.info("执行技能: skillName={}, step={}", skillName, step);

        Map<String, Object> params = extractParameters(step, context);

        if (!internalService.hasPermission(skillName, context)) {
            return Map.of(
                "status", "error",
                "message", "没有执行此操作的权限"
            );
        }

        return skillExecutor.execute(skillName, params);
    }

    /**
     * 提取参数
     */
    private Map<String, Object> extractParameters(String step, Map<String, Object> context) {
        Map<String, Object> params = new HashMap<>();
        params.put("step", step);
        params.put("context", context);
        return params;
    }

    /**
     * 直接执行
     */
    private Map<String, Object> executeDirect(String step, Map<String, Object> context) {
        String prompt = String.format(
            "请执行以下步骤并返回结果：\n\n步骤：%s\n\n上下文：%s\n\n请以JSON格式返回结果，包含status和message字段。",
            step, context
        );

        String response = chatService.callChatApi(prompt, "rag.tokens");
        return Map.of("status", "success", "message", response);
    }

    /**
     * 重新规划并执行
     */
    private Map<String, Object> replanAndExecute(String task, List<String> plan, Map<String, Object> context, String error) {
        log.info("重新规划任务，原步骤失败: {}", error);

        String prompt = String.format(
            "任务执行失败，失败步骤：%s\n失败原因：%s\n请重新规划执行步骤：\n\n原任务：%s\n\n请返回新的步骤列表。",
            plan.get(plan.size() - 1), error, task
        );

        String response = chatService.callChatApi(prompt, "rag.tokens");

        List<String> newPlan = new ArrayList<>();
        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.matches("^\\d+\\..*")) {
                newPlan.add(trimmed.replaceAll("^\\d+\\.?\\s*", ""));
            }
        }

        Map<String, Object> results = new LinkedHashMap<>();
        for (int i = 0; i < newPlan.size(); i++) {
            try {
                Map<String, Object> stepResult = executeStep(newPlan.get(i), context);
                results.put("replan_step_" + (i + 1), stepResult);
            } catch (Exception e) {
                log.error("重新规划步骤执行失败", e);
            }
        }

        return Map.of(
            "status", "retry",
            "originalPlan", plan,
            "newPlan", newPlan,
            "results", results,
            "message", "重新规划后执行完成"
        );
    }
}
