package com.xjjk.agent.memory.domain;

/** 通用语义记忆的顶层模式类型，具体属性由 predicate 表示。 */
public enum MemoryType {
    PROFILE,
    COMMUNICATION_PREFERENCE,
    RESPONSE_PREFERENCE,
    WORK_CONTEXT,
    STABLE_PREFERENCE,
    STABLE_USER_FACT
}
