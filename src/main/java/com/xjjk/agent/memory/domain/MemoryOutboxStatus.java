package com.xjjk.agent.memory.domain;

/** 记忆索引 Outbox 的持久化任务状态。 */
public enum MemoryOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY,
    DONE,
    DEAD
}
