package com.xjjk.agent.memory.answer;

import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;

import java.util.List;

/** 可由应用层确定性回答的封闭用户记忆问题类型。 */
public enum DirectMemoryQuestionType {
    PREFERRED_NAME(MemoryCategory.PROFILE_PREFERRED_NAME,
            MemoryTemporalScope.CURRENT, "preferred_name"),
    AGE(MemoryCategory.PROFILE_PERSONAL_FACT,
            MemoryTemporalScope.CURRENT, "age"),
    PROGRAMMING_LANGUAGE(MemoryCategory.WORK_COMMON_SCOPE,
            MemoryTemporalScope.CURRENT, "primary_programming_language"),
    CURRENT_EMPLOYER(MemoryCategory.WORK_COMMON_SCOPE,
            MemoryTemporalScope.CURRENT, "current_employer"),
    CURRENT_OCCUPATION(MemoryCategory.WORK_COMMON_SCOPE,
            MemoryTemporalScope.CURRENT, "occupation"),
    HISTORICAL_OCCUPATION(MemoryCategory.WORK_COMMON_SCOPE,
            MemoryTemporalScope.HISTORICAL, "occupation"),
    WORK_SCOPE(MemoryCategory.WORK_COMMON_SCOPE,
            MemoryTemporalScope.CURRENT, "common_scope", "technology_stack"),
    ANSWER_LANGUAGE(MemoryCategory.PREFERENCE_LANGUAGE,
            MemoryTemporalScope.CURRENT, "answer_language"),
    ANSWER_STYLE(MemoryCategory.PREFERENCE_ANSWER_STYLE,
            MemoryTemporalScope.CURRENT, "answer_style");

    private final MemoryCategory memoryCategory;
    private final MemoryTemporalScope temporalScope;
    private final List<String> predicateNames;

    DirectMemoryQuestionType(
            MemoryCategory memoryCategory,
            MemoryTemporalScope temporalScope,
            String... predicateNames) {
        this.memoryCategory = memoryCategory;
        this.temporalScope = temporalScope;
        this.predicateNames = List.of(predicateNames);
    }

    public MemoryCategory memoryCategory() {
        return memoryCategory;
    }

    public List<String> predicateNames() {
        return predicateNames;
    }

    public MemoryTemporalScope temporalScope() {
        return temporalScope;
    }
}
