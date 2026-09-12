package com.xjjk.agent.memory.domain;

/** 当前用户消息经过语义判断后的记忆生命周期决策。 */
public enum MemoryDecision {
    IGNORE,
    SESSION_ONLY,
    LONG_TERM
}
