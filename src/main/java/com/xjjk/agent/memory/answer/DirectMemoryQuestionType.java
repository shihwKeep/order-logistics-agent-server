package com.xjjk.agent.memory.answer;

import com.xjjk.agent.memory.domain.MemoryCategory;

/** 可由应用层确定性回答的封闭用户记忆问题类型。 */
public enum DirectMemoryQuestionType {
    PREFERRED_NAME(MemoryCategory.PROFILE_PREFERRED_NAME),
    PROGRAMMING_LANGUAGE(MemoryCategory.WORK_COMMON_SCOPE),
    WORK_SCOPE(MemoryCategory.WORK_COMMON_SCOPE),
    ANSWER_LANGUAGE(MemoryCategory.PREFERENCE_LANGUAGE),
    ANSWER_STYLE(MemoryCategory.PREFERENCE_ANSWER_STYLE);

    private final MemoryCategory memoryCategory;

    DirectMemoryQuestionType(MemoryCategory memoryCategory) {
        this.memoryCategory = memoryCategory;
    }

    public MemoryCategory memoryCategory() {
        return memoryCategory;
    }
}
