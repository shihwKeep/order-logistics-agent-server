package com.xjjk.agent.memory.domain;

/** 记忆索引需要执行的幂等操作。 */
public enum MemoryOutboxOperation {
    UPSERT,
    DELETE,
    DELETE_EXPLICIT_SCOPE,
    CLEAR_GENERATION
}
