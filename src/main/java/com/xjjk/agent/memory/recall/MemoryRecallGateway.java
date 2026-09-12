package com.xjjk.agent.memory.recall;

public interface MemoryRecallGateway {
    MemoryRecallGatewayResult retrieve(
            long tenantId, long userId, long generation, String query);
}
