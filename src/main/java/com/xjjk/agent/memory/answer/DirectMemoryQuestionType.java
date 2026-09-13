package com.xjjk.agent.memory.answer;

import com.xjjk.agent.memory.domain.MemoryCategory;

import java.util.List;

/** 可由应用层确定性回答的封闭用户记忆问题类型。 */
public enum DirectMemoryQuestionType {
    PREFERRED_NAME(MemoryCategory.PROFILE_PREFERRED_NAME, "preferred_name"),
    PROGRAMMING_LANGUAGE(MemoryCategory.WORK_COMMON_SCOPE,
            "primary_programming_language"),
    CURRENT_EMPLOYER(MemoryCategory.WORK_COMMON_SCOPE, "current_employer"),
    WORK_SCOPE(MemoryCategory.WORK_COMMON_SCOPE,
            "occupation", "common_scope", "technology_stack"),
    ANSWER_LANGUAGE(MemoryCategory.PREFERENCE_LANGUAGE, "answer_language"),
    ANSWER_STYLE(MemoryCategory.PREFERENCE_ANSWER_STYLE, "answer_style");

    private final MemoryCategory memoryCategory;
    private final List<String> predicateNames;

    DirectMemoryQuestionType(MemoryCategory memoryCategory, String... predicateNames) {
        this.memoryCategory = memoryCategory;
        this.predicateNames = List.of(predicateNames);
    }

    public MemoryCategory memoryCategory() {
        return memoryCategory;
    }

    public List<String> predicateNames() {
        return predicateNames;
    }
}
