package com.xjjk.agent.memory.index;

/** 用户记忆索引端口；失败必须抛出异常，由 Outbox worker 决定重试。 */
public interface MemoryIndexGateway {
    void apply(MemoryIndexCommand command);
}
