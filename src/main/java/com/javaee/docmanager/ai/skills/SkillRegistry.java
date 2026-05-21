package com.javaee.docmanager.ai.skills;

import com.javaee.docmanager.ai.service.MinIOService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new HashMap<>();

    @Autowired
    public SkillRegistry(MinIOService minIOService) {
        // 注册技能
        registerSkill(new FileUploadSkill(minIOService));
        registerSkill(new FileDownloadSkill(minIOService));
    }

    public void registerSkill(Skill skill) {
        skills.put(skill.getName(), skill);
    }

    public Skill getSkill(String name) {
        return skills.get(name);
    }

    public Map<String, Skill> getAllSkills() {
        return skills;
    }
}
