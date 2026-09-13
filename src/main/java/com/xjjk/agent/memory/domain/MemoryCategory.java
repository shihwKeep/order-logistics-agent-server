package com.xjjk.agent.memory.domain;

/** 第一版允许进入跨会话记忆的封闭类别集合。 */
public enum MemoryCategory {

    PROFILE_PREFERRED_NAME("profile.preferred_name"),
    PROFILE_PERSONAL_FACT("profile.personal"),
    PREFERENCE_LANGUAGE("preference.language"),
    PREFERENCE_ANSWER_STYLE("preference.answer_style"),
    WORK_COMMON_SCOPE("work.common_scope");

    private final String keyPrefix;

    MemoryCategory(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String keyPrefix() {
        return keyPrefix;
    }
}
