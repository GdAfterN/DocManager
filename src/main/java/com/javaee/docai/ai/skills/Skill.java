package com.javaee.docai.ai.skills;

public interface Skill {
    String getName();
    String getDescription();
    Object execute(Object... parameters);
}
